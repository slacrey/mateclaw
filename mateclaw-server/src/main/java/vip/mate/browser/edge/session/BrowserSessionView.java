package vip.mate.browser.edge.session;

import java.time.Instant;

/** Read-only projection of a session — no WebSocket reference. Used by D3. */
public record BrowserSessionView(
        String sessionId,
        String subject,
        String agentVersion,
        Instant lastHeartbeatAt
) {}
