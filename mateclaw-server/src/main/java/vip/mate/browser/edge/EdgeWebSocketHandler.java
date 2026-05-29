package vip.mate.browser.edge;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import vip.mate.browser.edge.action.ActionResult;
import vip.mate.browser.edge.auth.EdgeAuthInterceptor;
import vip.mate.browser.edge.auth.EdgePrincipal;
import vip.mate.browser.edge.protocol.EdgeMessage;
import vip.mate.browser.edge.protocol.EdgeMessageKind;
import vip.mate.browser.edge.session.BrowserSession;
import vip.mate.browser.edge.session.BrowserSessionRegistry;
import vip.mate.browser.orchestrator.ActionExecutionService;
import vip.mate.browser.orchestrator.snapshot.SnapshotEdgeClient;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Phase 1 Edge WebSocket handler.
 *
 * <p>Handles only protocol envelope kinds defined in edge-protocol.md §1:
 * hello, heartbeat, ping (and the corresponding acks/responses).
 *
 * <p>Unknown kinds are logged and silently dropped per the spec
 * (forward-compat). The only conditions that close the connection are
 * protocol-version mismatch (4400) and an unknown session_id on a
 * post-hello message (inline error, no close).
 */
@Slf4j
@Component
public class EdgeWebSocketHandler extends TextWebSocketHandler {

    private static final int SUPPORTED_PROTOCOL_VERSION = 1;
    private static final long HEARTBEAT_INTERVAL_MS = 10_000L;

    private final BrowserSessionRegistry registry;
    private final ObjectMapper mapper;
    private final ActionExecutionService actionExecutionService;
    private final SnapshotEdgeClient snapshotEdgeClient;
    private final String serverVersion;
    private final ConcurrentHashMap<String, String> sessionIdByWsId = new ConcurrentHashMap<>();

    public EdgeWebSocketHandler(BrowserSessionRegistry registry,
                                ObjectMapper mapper,
                                ActionExecutionService actionExecutionService,
                                SnapshotEdgeClient snapshotEdgeClient,
                                @Value("${revision:dev}") String serverVersion) {
        this.registry = registry;
        this.mapper = mapper;
        this.actionExecutionService = actionExecutionService;
        this.snapshotEdgeClient = snapshotEdgeClient;
        this.serverVersion = serverVersion;
    }

    @Override
    protected void handleTextMessage(WebSocketSession ws, TextMessage payload) throws Exception {
        EdgeMessage msg;
        try {
            msg = mapper.readValue(payload.getPayload(), EdgeMessage.class);
        } catch (Exception e) {
            log.warn("[edge] unparseable frame from ws={}: {}", ws.getId(), e.getMessage());
            ws.close(new CloseStatus(4400, "bad-envelope"));
            return;
        }

        if (msg.getV() != SUPPORTED_PROTOCOL_VERSION) {
            log.warn("[edge] unknown protocol v={} from ws={}", msg.getV(), ws.getId());
            ws.close(new CloseStatus(4400, "unknown-protocol-version"));
            return;
        }

        switch (msg.getKind()) {
            case HELLO -> onHello(ws, msg);
            case HEARTBEAT -> onHeartbeat(ws, msg);
            case PING -> onPing(ws, msg);
            case ACTION_RESULT -> onActionResult(ws, msg);
            case INDICATOR_STOP_CLICKED -> onIndicatorStopClicked(ws, msg);
            case A11Y_SNAPSHOT_RESPONSE -> onA11ySnapshotResponse(ws, msg);
            case UNKNOWN -> log.warn("[edge] dropping unknown kind from ws={}", ws.getId());
            default -> log.warn("[edge] kind {} not handled in phase 1", msg.getKind());
        }
    }

    private void onHello(WebSocketSession ws, EdgeMessage hello) throws Exception {
        EdgePrincipal principal = (EdgePrincipal) ws.getAttributes().get(EdgeAuthInterceptor.ATTR_PRINCIPAL);
        if (principal == null) {
            ws.close(new CloseStatus(4401, "no-principal"));
            return;
        }
        String agentVersion = (String) hello.getPayload().getOrDefault("agent_version", "unknown");
        BrowserSession session = registry.register(principal.subject(), ws, agentVersion);
        sessionIdByWsId.put(ws.getId(), session.getId());

        EdgeMessage ack = reply(hello, EdgeMessageKind.HELLO_ACK, Map.of(
                "session_id", session.getId(),
                "server_version", serverVersion,
                "heartbeat_interval_ms", (int) HEARTBEAT_INTERVAL_MS
        ));
        send(ws, ack);
    }

    private void onHeartbeat(WebSocketSession ws, EdgeMessage hb) throws Exception {
        if (!validSession(ws, hb)) return;
        registry.heartbeat(hb.getSessionId());
        send(ws, reply(hb, EdgeMessageKind.HEARTBEAT_ACK, Map.of()));
    }

    private void onPing(WebSocketSession ws, EdgeMessage ping) throws Exception {
        if (!validSession(ws, ping)) return;
        Object echo = ping.getPayload().getOrDefault("echo", "");
        send(ws, reply(ping, EdgeMessageKind.PONG, Map.of(
                "echo", echo,
                "server_ts", Instant.now().toEpochMilli()
        )));
    }

    private void onActionResult(WebSocketSession ws, EdgeMessage msg) throws Exception {
        if (!validSession(ws, msg)) return;
        ActionResult result = mapper.convertValue(msg.getPayload(), ActionResult.class);
        actionExecutionService.deliverResult(msg.getInReplyTo(), result);
    }

    private void onIndicatorStopClicked(WebSocketSession ws, EdgeMessage msg) throws Exception {
        if (!validSession(ws, msg)) return;
        registry.find(msg.getSessionId()).ifPresent(actionExecutionService::handleStopClicked);
    }

    private void onA11ySnapshotResponse(WebSocketSession ws, EdgeMessage msg) throws Exception {
        if (!validSession(ws, msg)) return;
        snapshotEdgeClient.deliverSnapshot(msg.getInReplyTo(), msg.getPayload());
    }

    /**
     * Three-way binding check (Codex P1-4 fix):
     *   (a) session_id exists in registry;
     *   (b) the session's underlying ws is the *current* socket;
     *   (c) the session's subject matches the authenticated principal.
     * Failure of (a) returns app.invalid_session; (b) or (c) returns
     * app.session_binding_mismatch and is logged at WARN for abuse audit.
     */
    private boolean validSession(WebSocketSession ws, EdgeMessage msg) throws Exception {
        BrowserSession session = registry.find(msg.getSessionId()).orElse(null);
        if (session == null) {
            send(ws, reply(msg, EdgeMessageKind.ERROR, Map.of(
                    "code", "app.invalid_session",
                    "message", "session_id not known to server",
                    "retryable", false
            )));
            return false;
        }
        EdgePrincipal principal = (EdgePrincipal) ws.getAttributes()
                .get(EdgeAuthInterceptor.ATTR_PRINCIPAL);
        boolean wsBindingOk = session.getWs().getId().equals(ws.getId());
        boolean principalOk = principal != null
                && session.getSubject().equals(principal.subject());
        if (!wsBindingOk || !principalOk) {
            log.warn("[edge] session binding mismatch: sessionId={} ws-ok={} principal-ok={}",
                    msg.getSessionId(), wsBindingOk, principalOk);
            send(ws, reply(msg, EdgeMessageKind.ERROR, Map.of(
                    "code", "app.session_binding_mismatch",
                    "message", "session_id is not owned by this connection",
                    "retryable", false
            )));
            return false;
        }
        return true;
    }

    private EdgeMessage reply(EdgeMessage from, EdgeMessageKind kind, Map<String, Object> payload) {
        return EdgeMessage.builder()
                .v(1)
                .msgId(UUID.randomUUID().toString())
                .kind(kind)
                .ts(Instant.now().toEpochMilli())
                .traceId(from.getTraceId())
                .sessionId(from.getSessionId())
                .inReplyTo(from.getMsgId())
                .payload(payload)
                .build();
    }

    private void send(WebSocketSession ws, EdgeMessage msg) throws Exception {
        ws.sendMessage(new TextMessage(mapper.writeValueAsString(msg)));
    }

    @Override
    public void afterConnectionClosed(WebSocketSession ws, CloseStatus status) {
        String sessionId = sessionIdByWsId.remove(ws.getId());
        if (sessionId != null) {
            actionExecutionService.sessionClosed(sessionId);
            snapshotEdgeClient.sessionClosed(sessionId);
        }
        registry.removeByWs(ws.getId());
        log.info("[edge] ws {} closed: {}", ws.getId(), status);
    }
}
