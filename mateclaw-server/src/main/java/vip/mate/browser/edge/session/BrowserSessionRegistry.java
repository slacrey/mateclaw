package vip.mate.browser.edge.session;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Thread-safe in-memory registry of live Browser Agent edge sessions.
 *
 * <p>Phase 1 is in-memory only — sessions die with the JVM. Phase 4 introduces
 * Postgres-backed session checkpointing and resumption.
 *
 * <p>Single-active-session-per-user policy: re-registering for the same user
 * replaces the previous session and closes the old WebSocket with code 4409.
 * This is intentional — a Native Host crash + reconnect must not leave a stale
 * handler holding the registry slot.
 */
@Slf4j
@Component
public class BrowserSessionRegistry {

    /** Heartbeat grace window: if no message seen for this long, session is stale. */
    public static final Duration STALE_GRACE = Duration.ofSeconds(30);

    /**
     * Tomcat/JSR-356 WebSocket sessions forbid concurrent sends — two threads
     * calling sendMessage at once throws "TEXT_PARTIAL_WRITING" / corrupts the
     * stream. The edge protocol sends from several threads (heartbeat-ack,
     * action.execute, a11y.snapshot.request, …), so every session is wrapped in
     * a {@link ConcurrentWebSocketSessionDecorator} that serialises sends behind
     * a per-session lock + bounded buffer.
     */
    private static final int SEND_TIME_LIMIT_MS = 15_000;
    private static final int SEND_BUFFER_BYTES = 1024 * 1024;

    private final ConcurrentHashMap<String, BrowserSession> byId = new ConcurrentHashMap<>();
    /** subject -> live sessionId. compute() on this map is the per-subject serialisation lock. */
    private final ConcurrentHashMap<String, String> subjectToSession = new ConcurrentHashMap<>();

    private volatile Clock clock;

    public BrowserSessionRegistry() { this(Clock.systemUTC()); }

    public BrowserSessionRegistry(Clock clock) { this.clock = clock; }

    /** Test seam — do not call from production code. */
    void setClockForTest(Clock clock) { this.clock = clock; }

    /**
     * Register a new session for {@code subject}. Atomically replaces any
     * existing session for the same subject; the old socket is closed with
     * 4409 inside the per-subject compute() lock so two concurrent
     * registrations cannot leave two live entries in {@link #byId}.
     */
    public BrowserSession register(String subject, WebSocketSession ws, String agentVersion) {
        String newId = "sess-" + UUID.randomUUID();
        // Wrap so ALL sends through session.getWs() are serialised — every
        // component (handler, ActionExecutionService, snapshot/screenshot
        // clients) sends through this one decorated instance.
        WebSocketSession concurrentWs =
                new ConcurrentWebSocketSessionDecorator(ws, SEND_TIME_LIMIT_MS, SEND_BUFFER_BYTES);
        BrowserSession session = BrowserSession.builder()
                .id(newId)
                .subject(subject)
                .agentVersion(agentVersion)
                .ws(concurrentWs)
                .lastHeartbeatAt(clock.instant())
                .build();

        // (1) Publish in byId first so concurrent readers see a coherent state.
        byId.put(newId, session);

        // (2) Atomically remap subject -> newId. Inside the closure we are the
        //     only writer for this subject; removing the previous id is race-free.
        subjectToSession.compute(subject, (k, existingId) -> {
            if (existingId != null && !existingId.equals(newId)) {
                BrowserSession prev = byId.remove(existingId);
                if (prev != null) {
                    closeQuietly(prev.getWs(), new CloseStatus(4409, "session-conflict"));
                    log.info("[edge] subject {} reconnected; dropped previous session {}",
                            subject, existingId);
                }
            }
            return newId;
        });
        return session;
    }

    public Optional<BrowserSession> find(String sessionId) {
        return Optional.ofNullable(byId.get(sessionId));
    }

    public Optional<BrowserSession> findBySubject(String subject) {
        String id = subjectToSession.get(subject);
        return id == null ? Optional.empty() : Optional.ofNullable(byId.get(id));
    }

    public void heartbeat(String sessionId) {
        BrowserSession s = byId.get(sessionId);
        if (s != null) {
            s.setLastHeartbeatAt(clock.instant());
        }
    }

    public int sizeForSubject(String subject) {
        return subjectToSession.containsKey(subject) ? 1 : 0;
    }

    public int size() { return byId.size(); }

    /**
     * Read-only snapshot of live sessions for debugging / observability.
     * Returned views intentionally omit the underlying WebSocket reference
     * (no leaking to controllers).
     */
    public List<BrowserSessionView> snapshot() {
        var out = new ArrayList<BrowserSessionView>(byId.size());
        for (var s : byId.values()) {
            out.add(new BrowserSessionView(
                    s.getId(), s.getSubject(), s.getAgentVersion(), s.getLastHeartbeatAt()));
        }
        return out;
    }

    /**
     * Remove sessions whose lastHeartbeatAt is older than STALE_GRACE.
     * Returns the number of sessions reaped.
     */
    public int reapStale() {
        var threshold = clock.instant().minus(STALE_GRACE);
        int reaped = 0;
        for (var entry : byId.entrySet()) {
            BrowserSession s = entry.getValue();
            if (s.getLastHeartbeatAt().isBefore(threshold)) {
                byId.remove(entry.getKey());
                subjectToSession.remove(s.getSubject(), entry.getKey());
                closeQuietly(s.getWs(), new CloseStatus(4408, "heartbeat-timeout"));
                reaped++;
                log.info("[edge] reaped stale session {} (subject={})", s.getId(), s.getSubject());
            }
        }
        return reaped;
    }

    /** Called by the WebSocket handler on close. */
    public void removeByWs(String wsId) {
        for (var entry : byId.entrySet()) {
            if (entry.getValue().getWs().getId().equals(wsId)) {
                byId.remove(entry.getKey());
                subjectToSession.remove(entry.getValue().getSubject(), entry.getKey());
                return;
            }
        }
    }

    private void closeQuietly(WebSocketSession ws, CloseStatus status) {
        try {
            if (ws.isOpen()) ws.close(status);
        } catch (IOException e) {
            log.debug("[edge] suppressed close error: {}", e.getMessage());
        }
    }
}
