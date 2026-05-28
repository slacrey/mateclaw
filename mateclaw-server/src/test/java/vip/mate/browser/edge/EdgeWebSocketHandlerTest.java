package vip.mate.browser.edge;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import vip.mate.browser.edge.auth.EdgeAuthInterceptor;
import vip.mate.browser.edge.auth.EdgePrincipal;
import vip.mate.browser.edge.protocol.EdgeMessage;
import vip.mate.browser.edge.protocol.EdgeMessageKind;
import vip.mate.browser.edge.session.BrowserSessionRegistry;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.*;

class EdgeWebSocketHandlerTest {

    private BrowserSessionRegistry registry;
    private EdgeWebSocketHandler handler;
    private ObjectMapper mapper;

    @BeforeEach
    void setUp() {
        registry = new BrowserSessionRegistry();
        mapper = new ObjectMapper();
        handler = new EdgeWebSocketHandler(registry, mapper, "1.4.0");
    }

    @Test
    void hello_returnsHelloAckAndRegistersSession() throws Exception {
        WebSocketSession ws = mockWs("user-1");

        EdgeMessage hello = EdgeMessage.builder()
                .v(1).msgId("m1").kind(EdgeMessageKind.HELLO).ts(0).traceId("t1").sessionId("")
                .payload(Map.of(
                        "agent_version", "0.1.0",
                        "os", "windows",
                        "arch", "amd64",
                        "auth", Map.of("scheme", "jwt", "token", "ignored-by-handler")
                )).build();

        handler.handleTextMessage(ws, new TextMessage(mapper.writeValueAsString(hello)));

        ArgumentCaptor<TextMessage> sent = ArgumentCaptor.forClass(TextMessage.class);
        verify(ws).sendMessage(sent.capture());

        EdgeMessage ack = mapper.readValue(sent.getValue().getPayload(), EdgeMessage.class);
        assertThat(ack.getKind()).isEqualTo(EdgeMessageKind.HELLO_ACK);
        assertThat(ack.getInReplyTo()).isEqualTo("m1");
        assertThat((String) ack.getPayload().get("session_id")).startsWith("sess-");
        assertThat(ack.getPayload().get("server_version")).isEqualTo("1.4.0");
        assertThat(ack.getPayload().get("heartbeat_interval_ms")).isEqualTo(10000);

        assertThat(registry.size()).isEqualTo(1);
    }

    @Test
    void heartbeat_updatesRegistryAndAcks() throws Exception {
        WebSocketSession ws = mockWs("user-1");
        var session = registry.register("user-1", ws, "0.1.0");

        EdgeMessage hb = EdgeMessage.builder()
                .v(1).msgId("m2").kind(EdgeMessageKind.HEARTBEAT)
                .ts(0).traceId("t2").sessionId(session.getId())
                .payload(Map.of()).build();

        handler.handleTextMessage(ws, new TextMessage(mapper.writeValueAsString(hb)));

        ArgumentCaptor<TextMessage> sent = ArgumentCaptor.forClass(TextMessage.class);
        verify(ws).sendMessage(sent.capture());
        EdgeMessage ack = mapper.readValue(sent.getValue().getPayload(), EdgeMessage.class);
        assertThat(ack.getKind()).isEqualTo(EdgeMessageKind.HEARTBEAT_ACK);
        assertThat(ack.getInReplyTo()).isEqualTo("m2");
    }

    @Test
    void ping_echoesBack() throws Exception {
        WebSocketSession ws = mockWs("user-1");
        var session = registry.register("user-1", ws, "0.1.0");

        EdgeMessage ping = EdgeMessage.builder()
                .v(1).msgId("m3").kind(EdgeMessageKind.PING)
                .ts(0).traceId("t3").sessionId(session.getId())
                .payload(Map.of("echo", "hello-world")).build();

        handler.handleTextMessage(ws, new TextMessage(mapper.writeValueAsString(ping)));

        ArgumentCaptor<TextMessage> sent = ArgumentCaptor.forClass(TextMessage.class);
        verify(ws).sendMessage(sent.capture());
        EdgeMessage pong = mapper.readValue(sent.getValue().getPayload(), EdgeMessage.class);
        assertThat(pong.getKind()).isEqualTo(EdgeMessageKind.PONG);
        assertThat(pong.getPayload().get("echo")).isEqualTo("hello-world");
        assertThat(pong.getPayload()).containsKey("server_ts");
    }

    @Test
    void unknownSessionId_sendsAppInvalidSession() throws Exception {
        WebSocketSession ws = mockWs("user-1");

        EdgeMessage ping = EdgeMessage.builder()
                .v(1).msgId("m4").kind(EdgeMessageKind.PING)
                .ts(0).traceId("t4").sessionId("sess-unknown")
                .payload(Map.of("echo", "x")).build();

        handler.handleTextMessage(ws, new TextMessage(mapper.writeValueAsString(ping)));

        ArgumentCaptor<TextMessage> sent = ArgumentCaptor.forClass(TextMessage.class);
        verify(ws).sendMessage(sent.capture());
        EdgeMessage err = mapper.readValue(sent.getValue().getPayload(), EdgeMessage.class);
        assertThat(err.getKind()).isEqualTo(EdgeMessageKind.ERROR);
        assertThat(err.getPayload().get("code")).isEqualTo("app.invalid_session");
    }

    @Test
    void otherWsSendsKnownSessionId_returnsBindingMismatch() throws Exception {
        // alice's session, registered on ws-alice
        WebSocketSession alice = mockWs("alice");
        var aliceSession = registry.register("alice", alice, "0.1.0");

        // bob authenticated, on ws-bob, but sends alice's session_id
        WebSocketSession bob = mockWs("bob");

        EdgeMessage spoofed = EdgeMessage.builder()
                .v(1).msgId("m-spoof").kind(EdgeMessageKind.PING)
                .ts(0).traceId("t-spoof").sessionId(aliceSession.getId())
                .payload(Map.of("echo", "evil")).build();

        handler.handleTextMessage(bob, new TextMessage(mapper.writeValueAsString(spoofed)));

        ArgumentCaptor<TextMessage> sent = ArgumentCaptor.forClass(TextMessage.class);
        verify(bob).sendMessage(sent.capture());
        EdgeMessage err = mapper.readValue(sent.getValue().getPayload(), EdgeMessage.class);
        assertThat(err.getKind()).isEqualTo(EdgeMessageKind.ERROR);
        assertThat(err.getPayload().get("code")).isEqualTo("app.session_binding_mismatch");

        // alice's heartbeat must not have been updated by bob's spoof — no message sent to alice
        try {
            verify(alice, never()).sendMessage(any());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void principalMismatch_returnsBindingMismatch() throws Exception {
        // ws registered as alice, but attributes were tampered to look like bob
        WebSocketSession ws = mockWs("alice");
        var session = registry.register("alice", ws, "0.1.0");

        Map<String, Object> attrs = new ConcurrentHashMap<>();
        attrs.put(EdgeAuthInterceptor.ATTR_PRINCIPAL, new EdgePrincipal("bob", "jwt"));
        when(ws.getAttributes()).thenReturn(attrs);

        EdgeMessage ping = EdgeMessage.builder()
                .v(1).msgId("m-pp").kind(EdgeMessageKind.PING)
                .ts(0).traceId("t-pp").sessionId(session.getId())
                .payload(Map.of("echo", "x")).build();

        handler.handleTextMessage(ws, new TextMessage(mapper.writeValueAsString(ping)));

        ArgumentCaptor<TextMessage> sent = ArgumentCaptor.forClass(TextMessage.class);
        verify(ws).sendMessage(sent.capture());
        EdgeMessage err = mapper.readValue(sent.getValue().getPayload(), EdgeMessage.class);
        assertThat(err.getPayload().get("code")).isEqualTo("app.session_binding_mismatch");
    }

    @Test
    void unknownProtocolVersion_closes4400() throws Exception {
        WebSocketSession ws = mockWs("user-1");

        EdgeMessage msg = EdgeMessage.builder()
                .v(999).msgId("m5").kind(EdgeMessageKind.PING).ts(0).traceId("t5").sessionId("")
                .payload(Map.of()).build();

        handler.handleTextMessage(ws, new TextMessage(mapper.writeValueAsString(msg)));

        try {
            verify(ws).close(argThat(s -> s.getCode() == 4400));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private WebSocketSession mockWs(String subject) {
        WebSocketSession ws = mock(WebSocketSession.class);
        when(ws.getId()).thenReturn("ws-" + subject);
        when(ws.isOpen()).thenReturn(true);
        Map<String, Object> attrs = new ConcurrentHashMap<>();
        attrs.put(EdgeAuthInterceptor.ATTR_PRINCIPAL, new EdgePrincipal(subject, "jwt"));
        when(ws.getAttributes()).thenReturn(attrs);
        return ws;
    }
}
