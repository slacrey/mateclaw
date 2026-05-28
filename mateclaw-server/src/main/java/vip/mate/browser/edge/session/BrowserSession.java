package vip.mate.browser.edge.session;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import org.springframework.web.socket.WebSocketSession;

import java.time.Instant;

/**
 * Live, in-memory representation of one Browser Agent edge session.
 * One subject -> at most one active session in Phase 1.
 *
 * <p>"Subject" carries either the JWT subject (username) or the PAT's
 * {@code userId.toString()}, depending on which auth path established the
 * session. Long-typed user identity is introduced in Phase 4 when sessions
 * become DB-persistent.
 */
@Data
@Builder
@AllArgsConstructor
public class BrowserSession {

    /** Server-issued opaque id, communicated to Native Host via hello.ack. */
    private final String id;

    /**
     * Authenticated principal — either JWT subject (username) or
     * PAT user id as string. Source of truth for the binding check
     * in {@link vip.mate.browser.edge.EdgeWebSocketHandler}.
     */
    private final String subject;

    /** Native Host agent version, from hello payload. */
    private final String agentVersion;

    /** Underlying WebSocket; do not leak outside the registry. */
    private final WebSocketSession ws;

    /** Last time we received any message (heartbeat or otherwise). */
    private volatile Instant lastHeartbeatAt;
}
