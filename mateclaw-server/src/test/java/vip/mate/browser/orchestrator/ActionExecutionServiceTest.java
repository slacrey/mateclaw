package vip.mate.browser.orchestrator;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import vip.mate.browser.edge.action.ActionKind;
import vip.mate.browser.edge.action.ActionRequest;
import vip.mate.browser.edge.action.ActionResult;
import vip.mate.browser.edge.action.ClickPayload;
import vip.mate.browser.edge.action.ClickSuccess;
import vip.mate.browser.edge.action.TabRef;
import vip.mate.browser.edge.protocol.EdgeMessage;
import vip.mate.browser.edge.protocol.EdgeMessageKind;
import vip.mate.browser.edge.session.BrowserSession;
import vip.mate.browser.edge.session.BrowserSessionRegistry;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ActionExecutionServiceTest {

    private BrowserSessionRegistry registry;
    private ObjectMapper mapper;
    private Clock clock;
    private ScheduledExecutorService deadlines;
    private List<Runnable> scheduledTasks;
    private RecordingWebSocket ws;
    private BrowserSession session;
    private ActionExecutionService service;

    @BeforeEach
    void setUp() {
        registry = mock(BrowserSessionRegistry.class);
        mapper = new ObjectMapper();
        clock = Clock.fixed(Instant.parse("2026-05-28T00:00:00Z"), ZoneOffset.UTC);
        scheduledTasks = new CopyOnWriteArrayList<>();
        deadlines = mock(ScheduledExecutorService.class);
        when(deadlines.schedule(
                org.mockito.ArgumentMatchers.any(Runnable.class),
                org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.any(TimeUnit.class)))
                .thenAnswer(invocation -> {
                    scheduledTasks.add(invocation.getArgument(0));
                    return mock(ScheduledFuture.class);
                });
        ws = new RecordingWebSocket("ws-1");
        session = BrowserSession.builder()
                .id("sess-1")
                .subject("alice")
                .agentVersion("0.2.0")
                .ws(ws)
                .lastHeartbeatAt(clock.instant())
                .build();
        when(registry.find("sess-1")).thenReturn(java.util.Optional.of(session));
        service = new ActionExecutionService(registry, mapper, clock, deadlines);
    }

    @Test
    void execute_roundTrip_returnsSuccess() throws Exception {
        ActionRequest req = clickRequest("m1", 5_000);
        var mono = service.execute(session, req);

        ActionResult.Success success = new ActionResult.Success(12, new ClickSuccess());
        service.deliverResult("m1", success);

        ActionResult result = mono.toFuture().get(1, TimeUnit.SECONDS);
        assertThat(result).isSameAs(success);
        assertThat(sentKinds()).containsExactly(EdgeMessageKind.ACTION_EXECUTE);
    }

    @Test
    void execute_resultBeforeReturn_isNotLost() throws Exception {
        ws.onSend(() -> service.deliverResult(
                "m-race",
                new ActionResult.Success(8, new ClickSuccess())));

        var mono = service.execute(session, clickRequest("m-race", 5_000));

        ActionResult result = mono.toFuture().get(1, TimeUnit.SECONDS);
        assertThat(result).isInstanceOf(ActionResult.Success.class);
    }

    @Test
    void cancel_winsRaceAgainstResult_deliversCancelledFailure() throws Exception {
        var mono = service.execute(session, clickRequest("m-cancel-win", 5_000));

        service.cancel(session, "m-cancel-win", "user_stop").block();
        service.deliverResult("m-cancel-win", new ActionResult.Success(9, new ClickSuccess()));

        ActionResult result = mono.toFuture().get(1, TimeUnit.SECONDS);
        assertCancelled(result, "user_stop");
        assertThat(sentKinds()).containsExactly(
                EdgeMessageKind.ACTION_EXECUTE,
                EdgeMessageKind.ACTION_CANCEL);
    }

    @Test
    void cancel_losesRaceAgainstResult_deliversRealResult() throws Exception {
        var mono = service.execute(session, clickRequest("m-result-win", 5_000));
        ActionResult.Success success = new ActionResult.Success(10, new ClickSuccess());

        service.deliverResult("m-result-win", success);
        service.cancel(session, "m-result-win", "user_stop").block();

        ActionResult result = mono.toFuture().get(1, TimeUnit.SECONDS);
        assertThat(result).isSameAs(success);
        assertThat(sentKinds()).containsExactly(EdgeMessageKind.ACTION_EXECUTE);
    }

    @Test
    void deadlineExpiry_returnsFailure() throws Exception {
        var mono = service.execute(session, clickRequest("m-deadline", 100));

        scheduledTasks.getFirst().run();
        ActionResult result = mono.toFuture().get(1, TimeUnit.SECONDS);

        assertThat(result).isEqualTo(new ActionResult.Failure(
                "DEADLINE_EXCEEDED",
                "action deadline exceeded after 100ms",
                true));
    }

    @Test
    void sessionDetachedMidFlight_returnsFailure() throws Exception {
        var mono = service.execute(session, clickRequest("m-detached", 5_000));

        service.sessionClosed("sess-1");

        ActionResult result = mono.toFuture().get(1, TimeUnit.SECONDS);
        assertThat(result).isEqualTo(new ActionResult.Failure(
                "SESSION_DETACHED",
                "browser session detached",
                false));
    }

    @Test
    void execute_idempotency_sameMsgIdReturnsSameMono() throws Exception {
        ActionRequest req = clickRequest("m-idempotent", 5_000);

        var first = service.execute(session, req);
        var second = service.execute(session, req);

        assertThat(second).isSameAs(first);
        assertThat(sentKinds()).containsExactly(EdgeMessageKind.ACTION_EXECUTE);
        assertThat(ws.sent()).hasSize(1);
    }

    @Test
    void handleStopClicked_triggersCancelOnInflight() throws Exception {
        var mono = service.execute(session, clickRequest("m-stop", 5_000));

        service.handleStopClicked(session);

        ActionResult result = mono.toFuture().get(1, TimeUnit.SECONDS);
        assertCancelled(result, "user_stop");
        // P1-4 closure: stop-click emits the cancel AND the indicator.hide
        // bookend so the on-page overlays come down. indicator.hide is the
        // final outbound envelope on the wire.
        assertThat(sentKinds()).containsExactly(
                EdgeMessageKind.ACTION_EXECUTE,
                EdgeMessageKind.ACTION_CANCEL,
                EdgeMessageKind.INDICATOR_HIDE);
    }

    @Test
    void handleStopClicked_noInflight_ignored() {
        service.handleStopClicked(session);

        assertThat(ws.sent()).isEmpty();
    }

    private ActionRequest clickRequest(String msgId, long deadlineMs) {
        return new ActionRequest(
                msgId,
                new TabRef.Main(),
                ActionKind.CLICK,
                new ClickPayload(10, 20, "left", 1),
                deadlineMs);
    }

    private List<EdgeMessageKind> sentKinds() {
        return ws.sent().stream()
                .map(message -> {
                    try {
                        return mapper.readValue(message.getPayload(), EdgeMessage.class).getKind();
                    } catch (Exception e) {
                        throw new AssertionError(e);
                    }
                })
                .toList();
    }

    private void assertCancelled(ActionResult result, String reason) {
        assertThat(result).isEqualTo(new ActionResult.Failure(
                "CANCELLED",
                "action cancelled: " + reason,
                false));
    }

    private static final class RecordingWebSocket implements WebSocketSession {
        private final String id;
        private final List<TextMessage> sent = new CopyOnWriteArrayList<>();
        private volatile Runnable onSend;

        private RecordingWebSocket(String id) {
            this.id = id;
        }

        void onSend(Runnable onSend) {
            this.onSend = onSend;
        }

        List<TextMessage> sent() {
            return sent;
        }

        @Override
        public String getId() {
            return id;
        }

        @Override
        public void sendMessage(org.springframework.web.socket.WebSocketMessage<?> message) {
            sent.add((TextMessage) message);
            Runnable callback = onSend;
            if (callback != null) {
                callback.run();
            }
        }

        @Override
        public boolean isOpen() {
            return true;
        }

        @Override
        public java.net.URI getUri() { return null; }

        @Override
        public org.springframework.http.HttpHeaders getHandshakeHeaders() {
            return org.springframework.http.HttpHeaders.EMPTY;
        }

        @Override
        public java.util.Map<String, Object> getAttributes() {
            return java.util.Map.of();
        }

        @Override
        public java.security.Principal getPrincipal() { return null; }

        @Override
        public java.net.InetSocketAddress getLocalAddress() { return null; }

        @Override
        public java.net.InetSocketAddress getRemoteAddress() { return null; }

        @Override
        public String getAcceptedProtocol() { return null; }

        @Override
        public void setTextMessageSizeLimit(int messageSizeLimit) {}

        @Override
        public int getTextMessageSizeLimit() { return 0; }

        @Override
        public void setBinaryMessageSizeLimit(int messageSizeLimit) {}

        @Override
        public int getBinaryMessageSizeLimit() { return 0; }

        @Override
        public java.util.List<org.springframework.web.socket.WebSocketExtension> getExtensions() {
            return java.util.List.of();
        }

        @Override
        public void close() {}

        @Override
        public void close(org.springframework.web.socket.CloseStatus status) {}
    }
}
