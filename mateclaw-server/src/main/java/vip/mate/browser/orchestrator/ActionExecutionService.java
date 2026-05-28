package vip.mate.browser.orchestrator;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.socket.TextMessage;
import reactor.core.publisher.Mono;
import vip.mate.browser.edge.action.ActionRequest;
import vip.mate.browser.edge.action.ActionResult;
import vip.mate.browser.edge.action.TabRef;
import vip.mate.browser.edge.protocol.EdgeMessage;
import vip.mate.browser.edge.protocol.EdgeMessageKind;
import vip.mate.browser.edge.session.BrowserSession;
import vip.mate.browser.edge.session.BrowserSessionRegistry;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

@Slf4j
@Service
public class ActionExecutionService {

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    private final BrowserSessionRegistry registry;
    private final ObjectMapper mapper;
    private final Clock clock;
    private final ScheduledExecutorService deadlines;

    /** msgId -> state slot for an in-flight request. */
    private final ConcurrentHashMap<String, PendingRequest> pending = new ConcurrentHashMap<>();
    /** sessionId -> msgId for the current in-flight request on that session. */
    private final ConcurrentHashMap<String, String> bySession = new ConcurrentHashMap<>();

    @Autowired
    public ActionExecutionService(BrowserSessionRegistry registry, ObjectMapper mapper, Clock clock) {
        this(registry, mapper, clock, Executors.newSingleThreadScheduledExecutor(daemonFactory()));
    }

    ActionExecutionService(BrowserSessionRegistry registry,
                           ObjectMapper mapper,
                           Clock clock,
                           ScheduledExecutorService deadlines) {
        this.registry = registry;
        this.mapper = mapper;
        this.clock = clock;
        this.deadlines = deadlines;
    }

    /**
     * Send action.execute on the session's WebSocket and return a Mono that
     * completes when action.result with matching in_reply_to arrives.
     *
     * <p>Idempotent: same msgId returns the existing Mono and does not resend
     * the envelope. The pending slot is published before the WebSocket send so
     * an immediate action.result cannot be lost.
     */
    public Mono<ActionResult> execute(BrowserSession session, ActionRequest req) {
        PendingRequest existing = pending.get(req.msgId());
        if (existing != null) {
            if (!existing.sessionId().equals(session.getId())) {
                throw new IllegalStateException("action msgId is already in-flight on another session");
            }
            return existing.mono();
        }

        CompletableFuture<ActionResult> future = new CompletableFuture<>();
        PendingRequest created = new PendingRequest(
                new AtomicReference<>(State.INFLIGHT),
                future,
                clock.instant().plusMillis(req.deadlineMs()),
                session.getId(),
                req.msgId(),
                req.tabRef(),
                Mono.fromFuture(future).cache(),
                new AtomicReference<>());

        PendingRequest winner = pending.putIfAbsent(req.msgId(), created);
        if (winner != null) {
            if (!winner.sessionId().equals(session.getId())) {
                throw new IllegalStateException("action msgId is already in-flight on another session");
            }
            return winner.mono();
        }
        if (!reserveSession(session, req, created)) {
            return pending.get(req.msgId()).mono();
        }

        PendingRequest registered = created;
        ScheduledFuture<?> deadlineTask = deadlines.schedule(
                () -> completeWithFailure(
                        registered,
                        "DEADLINE_EXCEEDED",
                        "action deadline exceeded after " + req.deadlineMs() + "ms",
                        true),
                req.deadlineMs(),
                TimeUnit.MILLISECONDS);
        registered.deadlineTask().set(deadlineTask);
        if (registered.state().get() == State.DONE) {
            deadlineTask.cancel(false);
        }

        try {
            sendActionExecute(session, req);
        } catch (Exception e) {
            completeWithFailure(registered, "SEND_FAILED",
                    "failed to send action.execute: " + e.getMessage(), true);
        }

        return registered.mono();
    }

    /**
     * Atomically transition the in-flight request to CANCELLING.
     */
    public Mono<Void> cancel(BrowserSession session, String msgId, String reason) {
        PendingRequest slot = pending.get(msgId);
        if (slot == null || !slot.sessionId().equals(session.getId())) {
            return Mono.empty();
        }
        if (!slot.state().compareAndSet(State.INFLIGHT, State.CANCELLING)) {
            return Mono.empty();
        }

        sendActionCancel(session, slot, reason);
        completeCancelling(slot, reason);
        return Mono.empty();
    }

    /**
     * Called by the WebSocket handler when an action.result envelope arrives.
     */
    public void deliverResult(String msgId, ActionResult result) {
        if (msgId == null) {
            return;
        }
        PendingRequest slot = pending.get(msgId);
        if (slot == null) {
            log.debug("[browser-action] dropping unmatched action.result msgId={}", msgId);
            return;
        }
        if (!slot.state().compareAndSet(State.INFLIGHT, State.DONE)) {
            return;
        }
        complete(slot, result);
    }

    /**
     * Called by the WebSocket handler when an indicator.stop_clicked envelope arrives.
     */
    public void handleStopClicked(BrowserSession session) {
        String msgId = bySession.get(session.getId());
        if (msgId == null) {
            return;
        }
        cancel(session, msgId, "user_stop").subscribe();
    }

    /**
     * Called by the WebSocket handler when the underlying socket is closed.
     */
    public void sessionClosed(String sessionId) {
        String msgId = bySession.get(sessionId);
        if (msgId == null) {
            return;
        }
        PendingRequest slot = pending.get(msgId);
        if (slot == null) {
            bySession.remove(sessionId, msgId);
            return;
        }
        completeWithFailure(slot, "SESSION_DETACHED", "browser session detached", false);
    }

    private boolean reserveSession(BrowserSession session, ActionRequest req, PendingRequest created) {
        String liveMsgId = bySession.putIfAbsent(session.getId(), req.msgId());
        if (liveMsgId == null || liveMsgId.equals(req.msgId())) {
            return true;
        }

        pending.remove(req.msgId(), created);
        PendingRequest live = pending.get(liveMsgId);
        if (live != null) {
            throw new IllegalStateException(
                    "an action is already in-flight on session " + session.getId());
        }
        bySession.remove(session.getId(), liveMsgId);
        return reserveSession(session, req, created);
    }

    private void sendActionExecute(BrowserSession session, ActionRequest req) throws Exception {
        EdgeMessage message = EdgeMessage.builder()
                .v(1)
                .msgId(req.msgId())
                .kind(EdgeMessageKind.ACTION_EXECUTE)
                .ts(clock.instant().toEpochMilli())
                .traceId(UUID.randomUUID().toString())
                .sessionId(session.getId())
                .payload(Map.of(
                        "tab_ref", mapper.convertValue(req.tabRef(), Object.class),
                        "kind", req.kind().wire(),
                        "params", mapper.convertValue(req.params(), MAP_TYPE),
                        "deadline_ms", req.deadlineMs()))
                .build();
        session.getWs().sendMessage(new TextMessage(mapper.writeValueAsString(message)));
    }

    private void sendActionCancel(BrowserSession session, String msgId, String reason) {
        PendingRequest slot = pending.get(msgId);
        if (slot == null) {
            return;
        }
        sendActionCancel(session, slot, reason);
    }

    private void sendActionCancel(BrowserSession session, PendingRequest slot, String reason) {
        try {
            EdgeMessage message = EdgeMessage.builder()
                    .v(1)
                    .msgId(UUID.randomUUID().toString())
                    .kind(EdgeMessageKind.ACTION_CANCEL)
                    .ts(clock.instant().toEpochMilli())
                    .traceId(UUID.randomUUID().toString())
                    .sessionId(session.getId())
                    .inReplyTo(slot.msgId())
                    .payload(Map.of(
                            "tab_ref", mapper.convertValue(slot.tabRef(), Object.class),
                            "reason", reason))
                    .build();
            session.getWs().sendMessage(new TextMessage(mapper.writeValueAsString(message)));
        } catch (Exception e) {
            log.debug("[browser-action] failed to send action.cancel msgId={}: {}",
                    slot.msgId(), e.getMessage());
        }
    }

    private void completeCancelling(PendingRequest slot, String reason) {
        if (!slot.state().compareAndSet(State.CANCELLING, State.DONE)) {
            return;
        }
        complete(slot, new ActionResult.Failure(
                "CANCELLED",
                "action cancelled: " + reason,
                false));
    }

    private void completeWithFailure(PendingRequest slot, String code, String message, boolean retryable) {
        if (!slot.state().compareAndSet(State.INFLIGHT, State.DONE)) {
            return;
        }
        complete(slot, new ActionResult.Failure(code, message, retryable));
    }

    private void complete(PendingRequest slot, ActionResult result) {
        ScheduledFuture<?> task = slot.deadlineTask().get();
        if (task != null) {
            task.cancel(false);
        }
        pending.remove(slot.msgId(), slot);
        bySession.remove(slot.sessionId(), slot.msgId());
        slot.future().complete(result);
    }

    private static ThreadFactory daemonFactory() {
        return runnable -> {
            Thread thread = new Thread(runnable, "browser-action-deadline");
            thread.setDaemon(true);
            return thread;
        };
    }

    private enum State {
        INFLIGHT,
        CANCELLING,
        DONE
    }

    private record PendingRequest(
            AtomicReference<State> state,
            CompletableFuture<ActionResult> future,
            Instant deadlineAt,
            String sessionId,
            String msgId,
            TabRef tabRef,
            Mono<ActionResult> mono,
            AtomicReference<ScheduledFuture<?>> deadlineTask
    ) {
    }
}
