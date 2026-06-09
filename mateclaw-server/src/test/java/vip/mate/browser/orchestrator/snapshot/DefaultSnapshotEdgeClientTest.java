package vip.mate.browser.orchestrator.snapshot;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import vip.mate.browser.edge.action.TabRef;
import vip.mate.browser.edge.protocol.EdgeMessage;
import vip.mate.browser.edge.protocol.EdgeMessageKind;
import vip.mate.browser.edge.session.BrowserSession;
import vip.mate.browser.orchestrator.domain.PageSnapshot;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * Coverage for the Wave 2.1-A concrete {@link DefaultSnapshotEdgeClient}.
 *
 * <p>The pending-future + msgId-correlation pattern is borrowed verbatim
 * from Wave 1's {@code ActionExecutionService} — these tests pin the
 * happy/timeout/detach contract so a future refactor doesn't regress.
 */
class DefaultSnapshotEdgeClientTest {

    private static final Clock FIXED_CLOCK =
            Clock.fixed(Instant.parse("2026-05-29T00:00:00Z"), ZoneId.of("UTC"));

    private ObjectMapper mapper;
    private ManualScheduler scheduler;
    private DefaultSnapshotEdgeClient client;
    private BrowserSession session;
    private WebSocketSession ws;

    @BeforeEach
    void setUp() {
        mapper = new ObjectMapper();
        scheduler = new ManualScheduler();
        client = new DefaultSnapshotEdgeClient(mapper, FIXED_CLOCK, scheduler);
        ws = mock(WebSocketSession.class);
        session = BrowserSession.builder()
                .id("sess-1")
                .subject("alice")
                .agentVersion("0.1.0")
                .ws(ws)
                .lastHeartbeatAt(FIXED_CLOCK.instant())
                .build();
    }

    // -----------------------------------------------------------------
    // Happy path
    // -----------------------------------------------------------------

    @Test
    void request_sendsA11ySnapshotRequestEnvelopeOnTheSessionWs() throws Exception {
        var future = client.request(session, new TabRef.Main(), "interactive").toFuture();

        var captor = org.mockito.ArgumentCaptor.forClass(TextMessage.class);
        verify(ws).sendMessage(captor.capture());
        EdgeMessage envelope = mapper.readValue(captor.getValue().getPayload(), EdgeMessage.class);

        assertThat(envelope.getKind()).isEqualTo(EdgeMessageKind.A11Y_SNAPSHOT_REQUEST);
        assertThat(envelope.getSessionId()).isEqualTo("sess-1");
        assertThat(envelope.getPayload()).containsKeys("tab_ref", "filter", "depth", "max_chars");
        assertThat(envelope.getPayload()).containsEntry("tab_ref", "main");
        assertThat(envelope.getPayload().get("filter")).isEqualTo("interactive");
        assertThat(envelope.getPayload()).containsEntry("depth", 15);
        assertThat(envelope.getPayload()).containsEntry("max_chars", 200_000);

        // future not yet completed
        assertThat(future.isDone()).isFalse();
    }

    @Test
    void deliverSnapshot_resolvesFutureWithParsedPageSnapshot() throws Exception {
        var future = client.request(session, new TabRef.Main(), "interactive").toFuture();
        String outboundMsgId = capturedEnvelopeMsgId();

        client.deliverSnapshot(outboundMsgId, Map.of(
                "snapshot_id", "snap-xyz",
                "captured_at_ms", 1_730_000_000_123L,
                "tab_ref", 42L,
                "tree", "Button[ref=ref_1]: Submit @{100,200 80x32}",
                "viewport", Map.of("w", 1280, "h", 800),
                "url", "https://example.com/page",
                "title", "Example page"));

        PageSnapshot snap = future.get(500, TimeUnit.MILLISECONDS);
        assertThat(snap.snapshotId()).isEqualTo("snap-xyz");
        assertThat(snap.capturedAtMs()).isEqualTo(1_730_000_000_123L);
        assertThat(snap.resolvedTabId()).isEqualTo(42L);
        assertThat(snap.viewport().w()).isEqualTo(1280);
        assertThat(snap.tree()).contains("Button");
        // The new url + title fields round-trip through parseSnapshot (used by
        // ExtensionBrowserTool.extension_browser_observe so the LLM can detect
        // navigation between observes — the search-loop fix).
        assertThat(snap.url()).isEqualTo("https://example.com/page");
        assertThat(snap.title()).isEqualTo("Example page");
        // The tree round-trips through PageSnapshot.lines() correctly.
        assertThat(snap.lines()).hasSize(1);
    }

    @Test
    void deliverSnapshot_omittedUrlTitle_defaultsToEmpty() throws Exception {
        // Older extensions (pre-2026-05-30) didn't emit url + title in the
        // a11y.snapshot.response payload. parseSnapshot must tolerate that and
        // default both to "" so PageSnapshot's canonical ctor doesn't throw.
        var future = client.request(session, new TabRef.Main(), "interactive").toFuture();
        client.deliverSnapshot(capturedEnvelopeMsgId(), Map.of(
                "snapshot_id", "snap-legacy",
                "captured_at_ms", 1L,
                "tab_ref", 42L,
                "tree", "Button[ref=ref_1]: x @{0,0 1x1}",
                "viewport", Map.of("w", 800, "h", 600)));
        PageSnapshot snap = future.get(500, TimeUnit.MILLISECONDS);
        assertThat(snap.url()).isEqualTo("");
        assertThat(snap.title()).isEqualTo("");
    }

    @Test
    void deliverSnapshot_blankSnapshotId_generatesUuidFallback() throws Exception {
        var future = client.request(session, new TabRef.Main(), "interactive").toFuture();
        client.deliverSnapshot(capturedEnvelopeMsgId(), Map.of(
                "snapshot_id", "",
                "captured_at_ms", 1L,
                "tab_ref", 1L,
                "tree", "",
                "viewport", Map.of("w", 800, "h", 600)));
        PageSnapshot snap = future.get(500, TimeUnit.MILLISECONDS);
        assertThat(snap.snapshotId()).isNotBlank();
    }

    @Test
    void deliverSnapshot_errorPayload_failsFutureWithSnapshotFailure() throws Exception {
        var future = client.request(session, new TabRef.Main(), "interactive").toFuture();

        client.deliverSnapshot(capturedEnvelopeMsgId(), Map.of(
                "snapshot_id", "snap-failed",
                "captured_at_ms", 1L,
                "tab_ref", -1L,
                "tree", "",
                "viewport", Map.of("w", 0, "h", 0),
                "error", Map.of(
                        "code", "SNAPSHOT_FAILED",
                        "message", "a11y.snapshot.request payload was malformed",
                        "retryable", false)));

        assertThatThrownBy(() -> future.get(500, TimeUnit.MILLISECONDS))
                .isInstanceOf(ExecutionException.class)
                .hasCauseInstanceOf(DefaultSnapshotEdgeClient.SnapshotFailureException.class)
                .hasMessageContaining("SNAPSHOT_FAILED");
    }

    @Test
    void deliverSnapshot_unknownMsgId_isNoOp() {
        // No throws; future for any in-flight remains pending.
        var future = client.request(session, new TabRef.Main(), "interactive").toFuture();
        client.deliverSnapshot("not-our-msg-id", Map.of(
                "snapshot_id", "x", "captured_at_ms", 1L, "tab_ref", 1L,
                "tree", "", "viewport", Map.of("w", 800, "h", 600)));
        assertThat(future.isDone()).isFalse();
    }

    @Test
    void deliverSnapshot_nullInReplyTo_isNoOp() {
        client.deliverSnapshot(null, Map.of()); // must not throw
    }

    // -----------------------------------------------------------------
    // Timeout
    // -----------------------------------------------------------------

    @Test
    void timeout_fires_completesFutureExceptionally() {
        var future = client.request(session, new TabRef.Main(), "interactive").toFuture();
        scheduler.advanceAndRunPending();   // run the scheduled timeout task

        assertThatThrownBy(() -> future.get(500, TimeUnit.MILLISECONDS))
                .isInstanceOf(ExecutionException.class)
                .hasCauseInstanceOf(DefaultSnapshotEdgeClient.SnapshotTimeoutException.class);
    }

    @Test
    void deliverAfterTimeout_isNoOp() throws Exception {
        var future = client.request(session, new TabRef.Main(), "interactive").toFuture();
        String msgId = capturedEnvelopeMsgId();
        scheduler.advanceAndRunPending();

        // Even though we now deliver a response, the slot is already gone;
        // the future remains in the timeout-failed state (we don't double-complete).
        client.deliverSnapshot(msgId, Map.of(
                "snapshot_id", "late", "captured_at_ms", 1L, "tab_ref", 1L,
                "tree", "", "viewport", Map.of("w", 800, "h", 600)));

        assertThatThrownBy(() -> future.get(50, TimeUnit.MILLISECONDS))
                .isInstanceOf(ExecutionException.class)
                .hasCauseInstanceOf(DefaultSnapshotEdgeClient.SnapshotTimeoutException.class);
    }

    // -----------------------------------------------------------------
    // Session detach
    // -----------------------------------------------------------------

    @Test
    void sessionClosed_failsInflightWithSessionDetached() {
        var future = client.request(session, new TabRef.Main(), "interactive").toFuture();
        client.sessionClosed(session.getId());

        assertThatThrownBy(() -> future.get(500, TimeUnit.MILLISECONDS))
                .isInstanceOf(ExecutionException.class)
                .hasCauseInstanceOf(DefaultSnapshotEdgeClient.SessionDetachedException.class);
    }

    @Test
    void sessionClosed_otherSession_doesNotAffectInflightOnOurs() throws Exception {
        var future = client.request(session, new TabRef.Main(), "interactive").toFuture();
        client.sessionClosed("some-other-session-id");

        assertThat(future.isDone()).isFalse();
        client.deliverSnapshot(capturedEnvelopeMsgId(), Map.of(
                "snapshot_id", "ok", "captured_at_ms", 1L, "tab_ref", 1L,
                "tree", "", "viewport", Map.of("w", 800, "h", 600)));
        assertThat(future.get(200, TimeUnit.MILLISECONDS).snapshotId()).isEqualTo("ok");
    }

    // -----------------------------------------------------------------
    // Send failure
    // -----------------------------------------------------------------

    @Test
    void sendMessageThrows_failsFutureImmediately() throws Exception {
        doAnswer(inv -> { throw new java.io.IOException("WS dead"); })
                .when(ws).sendMessage(any());

        var future = client.request(session, new TabRef.Main(), "interactive").toFuture();

        assertThatThrownBy(() -> future.get(500, TimeUnit.MILLISECONDS))
                .isInstanceOf(ExecutionException.class)
                .hasMessageContaining("WS dead");
    }

    // -----------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------

    private String capturedEnvelopeMsgId() throws Exception {
        var captor = org.mockito.ArgumentCaptor.forClass(TextMessage.class);
        verify(ws).sendMessage(captor.capture());
        return mapper.readValue(captor.getValue().getPayload(), EdgeMessage.class).getMsgId();
    }

    /**
     * Manual scheduler so timeout tests are instantaneous instead of waiting
     * 10s of wall-clock. Captures the most recent scheduled task; advance()
     * runs it synchronously.
     */
    private static final class ManualScheduler
            extends java.util.concurrent.ScheduledThreadPoolExecutor
            implements ScheduledExecutorService {
        private Runnable pending;

        ManualScheduler() { super(1); }

        @Override
        public ScheduledFuture<?> schedule(Runnable command, long delay, TimeUnit unit) {
            this.pending = command;
            // Return a no-op ScheduledFuture — production code only calls cancel().
            CompletableFuture<Void> noop = new CompletableFuture<>();
            return new ScheduledFuture<>() {
                @Override public long getDelay(TimeUnit u) { return 0; }
                @Override public int compareTo(java.util.concurrent.Delayed o) { return 0; }
                @Override public boolean cancel(boolean mi) {
                    pending = null;
                    return noop.cancel(mi);
                }
                @Override public boolean isCancelled() { return noop.isCancelled(); }
                @Override public boolean isDone() { return noop.isDone(); }
                @Override public Void get() { return null; }
                @Override public Void get(long t, TimeUnit u) { return null; }
            };
        }

        void advanceAndRunPending() {
            if (pending != null) {
                Runnable r = pending;
                pending = null;
                r.run();
            }
        }
    }
}
