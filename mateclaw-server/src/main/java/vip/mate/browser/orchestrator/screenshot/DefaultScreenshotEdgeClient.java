package vip.mate.browser.orchestrator.screenshot;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.socket.TextMessage;
import reactor.core.publisher.Mono;
import vip.mate.browser.edge.action.TabRef;
import vip.mate.browser.edge.protocol.EdgeMessage;
import vip.mate.browser.edge.protocol.EdgeMessageKind;
import vip.mate.browser.edge.session.BrowserSession;
import vip.mate.browser.orchestrator.domain.Viewport;

import java.time.Clock;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

/**
 * Concrete {@link ScreenshotEdgeClient} that issues
 * {@code screenshot.capture.request} envelopes on the session's Edge
 * WebSocket and awaits the matching {@code screenshot.capture.response}
 * by {@code in_reply_to} correlation.
 *
 * <p>Mirrors {@code DefaultSnapshotEdgeClient}'s pending-future pattern
 * (Wave 2.1-A) line for line: publish the slot into {@link #pending}
 * BEFORE sending the envelope so an immediate response cannot be lost;
 * deadline timer cancels the future if no response arrives in
 * {@link #DEFAULT_TIMEOUT}.
 *
 * <p>Registered as a {@code @Service} — the
 * {@link ScreenshotEdgeClientFallbackConfig} fallback's
 * {@code @ConditionalOnMissingBean} automatically steps aside.
 */
@Slf4j
@Service
public class DefaultScreenshotEdgeClient implements ScreenshotEdgeClient {

    /** Wire-spec timeout — fast enough to surface "extension is wedged" but
     *  generous enough for slow first-page hydration. Tunable later. */
    public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(10);

    /** Default capture format. Extension currently only supports PNG. */
    private static final String DEFAULT_FORMAT = "png";

    private final ObjectMapper mapper;
    private final Clock clock;
    private final ScheduledExecutorService deadlines;

    /** msgId -> pending slot. */
    private final ConcurrentHashMap<String, Pending> pending = new ConcurrentHashMap<>();

    @Autowired
    public DefaultScreenshotEdgeClient(ObjectMapper mapper, Clock clock) {
        this(mapper, clock, Executors.newSingleThreadScheduledExecutor(daemonFactory()));
    }

    DefaultScreenshotEdgeClient(ObjectMapper mapper,
                                Clock clock,
                                ScheduledExecutorService deadlines) {
        this.mapper = mapper;
        this.clock = clock;
        this.deadlines = deadlines;
    }

    @Override
    public Mono<PageScreenshot> request(BrowserSession session, TabRef tabRef, int scaleFactor) {
        String msgId = UUID.randomUUID().toString();
        CompletableFuture<PageScreenshot> future = new CompletableFuture<>();
        Pending slot = new Pending(future, session.getId());

        Pending winner = pending.putIfAbsent(msgId, slot);
        // UUID collision is astronomically rare; on the off chance, recurse.
        if (winner != null) {
            return request(session, tabRef, scaleFactor);
        }

        int normalizedScale = scaleFactor <= 0 ? 1 : scaleFactor;

        ScheduledFuture<?> timeoutTask = deadlines.schedule(() -> {
            if (pending.remove(msgId, slot)) {
                slot.future.completeExceptionally(new ScreenshotTimeoutException(
                        "screenshot.capture.request timed out after " + DEFAULT_TIMEOUT));
            }
        }, DEFAULT_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);

        try {
            EdgeMessage envelope = EdgeMessage.builder()
                    .v(1)
                    .msgId(msgId)
                    .kind(EdgeMessageKind.SCREENSHOT_CAPTURE_REQUEST)
                    .ts(clock.instant().toEpochMilli())
                    .traceId(UUID.randomUUID().toString())
                    .sessionId(session.getId())
                    .payload(Map.of(
                            "tab_ref", mapper.convertValue(tabRef, Object.class),
                            "format", DEFAULT_FORMAT,
                            "scale_factor", normalizedScale))
                    .build();
            session.getWs().sendMessage(new TextMessage(mapper.writeValueAsString(envelope)));
        } catch (Exception e) {
            timeoutTask.cancel(false);
            if (pending.remove(msgId, slot)) {
                slot.future.completeExceptionally(new IllegalStateException(
                        "failed to send screenshot.capture.request: " + e.getMessage(), e));
            }
        }

        return Mono.fromFuture(future);
    }

    /**
     * Called by {@code EdgeWebSocketHandler} when a
     * {@code screenshot.capture.response} envelope arrives. Looks up the
     * pending slot by {@code in_reply_to} and resolves its future with a
     * parsed {@link PageScreenshot}. No-op when the slot is gone (timed out
     * / cancelled).
     */
    @Override
    public void deliverScreenshot(String inReplyTo, Map<String, Object> payload) {
        if (inReplyTo == null) {
            log.debug("[browser-screenshot] dropping screenshot.capture.response with no in_reply_to");
            return;
        }
        Pending slot = pending.remove(inReplyTo);
        if (slot == null) {
            log.debug("[browser-screenshot] dropping unmatched screenshot.capture.response msgId={}",
                    inReplyTo);
            return;
        }
        try {
            slot.future.complete(parseScreenshot(payload));
        } catch (Exception e) {
            slot.future.completeExceptionally(e);
        }
    }

    /**
     * Called by {@code EdgeWebSocketHandler.afterConnectionClosed}. Any
     * in-flight screenshot fetch on the closed session fails with
     * {@code SESSION_DETACHED}.
     */
    @Override
    public void sessionClosed(String sessionId) {
        pending.entrySet().removeIf(e -> {
            if (!e.getValue().sessionId.equals(sessionId)) return false;
            e.getValue().future.completeExceptionally(new SessionDetachedException(
                    "browser session detached before screenshot.capture.response arrived"));
            return true;
        });
    }

    private PageScreenshot parseScreenshot(Map<String, Object> payload) {
        // Surface a typed extension-side failure (SCREENSHOT_TOO_LARGE /
        // PERMISSION_DENIED / NO_TARGET_TAB) with its REAL code + message, rather
        // than letting readBase64 below mask it as a generic "missing data_base64".
        if (payload.get("error") instanceof Map<?, ?> err) {
            Object code = err.get("code");
            Object message = err.get("message");
            throw new IllegalStateException("screenshot.capture failed: "
                    + (code == null ? "ERROR" : code)
                    + (message == null ? "" : " — " + message));
        }
        String snapshotId = readString(payload, "snapshot_id", "");
        long capturedAt = readLong(payload, "captured_at_ms", clock.instant().toEpochMilli());
        long tabRef = readLong(payload, "tab_ref", -1L);
        Viewport viewport = readViewport(payload, "viewport", 1280, 800);
        // actual_dimensions falls back to viewport — covers the case where a
        // scale_factor of 1 was used and the extension can save bytes by
        // omitting the duplicate field.
        Viewport actualDimensions = readViewport(payload, "actual_dimensions", viewport.w(), viewport.h());
        byte[] pngBytes = readBase64(payload, "data_base64");

        return new PageScreenshot(
                snapshotId.isBlank() ? UUID.randomUUID().toString() : snapshotId,
                capturedAt > 0 ? capturedAt : 1L,
                tabRef,
                pngBytes,
                viewport,
                actualDimensions);
    }

    private static byte[] readBase64(Map<String, Object> payload, String key) {
        Object raw = payload.get(key);
        if (!(raw instanceof String s) || s.isBlank()) {
            throw new IllegalStateException(
                    "screenshot.capture.response payload missing required `" + key + "` field");
        }
        try {
            return Base64.getDecoder().decode(s);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException(
                    "screenshot.capture.response `" + key + "` is not valid base64: " + e.getMessage(), e);
        }
    }

    @SuppressWarnings("unchecked")
    private Viewport readViewport(Map<String, Object> payload, String key, int fallbackW, int fallbackH) {
        Object raw = payload.get(key);
        if (raw instanceof Map<?, ?> m) {
            Map<String, Object> v = (Map<String, Object>) m;
            int w = (int) readLong(v, "w", fallbackW);
            int h = (int) readLong(v, "h", fallbackH);
            return new Viewport(w, h);
        }
        return new Viewport(fallbackW, fallbackH);
    }

    private static String readString(Map<String, Object> map, String key, String fallback) {
        Object v = map.get(key);
        return v == null ? fallback : v.toString();
    }

    private static long readLong(Map<String, Object> map, String key, long fallback) {
        Object v = map.get(key);
        if (v instanceof Number n) return n.longValue();
        if (v instanceof String s) {
            try { return Long.parseLong(s); } catch (NumberFormatException ignore) { return fallback; }
        }
        return fallback;
    }

    private static ThreadFactory daemonFactory() {
        return r -> {
            Thread t = new Thread(r, "browser-screenshot-deadline");
            t.setDaemon(true);
            return t;
        };
    }

    private record Pending(CompletableFuture<PageScreenshot> future, String sessionId) {}

    /** Thrown when the response window expires. Wraps as Mono error. */
    public static class ScreenshotTimeoutException extends RuntimeException {
        public ScreenshotTimeoutException(String message) { super(message); }
    }

    /** Thrown when the underlying session closes mid-fetch. */
    public static class SessionDetachedException extends RuntimeException {
        public SessionDetachedException(String message) { super(message); }
    }
}
