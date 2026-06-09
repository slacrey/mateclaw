package vip.mate.browser.edge.session;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.*;

class BrowserSessionRegistryTest {

    private BrowserSessionRegistry registry;
    private Clock fixedClock;

    @BeforeEach
    void setUp() {
        fixedClock = Clock.fixed(Instant.ofEpochMilli(1_730_000_000_000L), ZoneOffset.UTC);
        registry = new BrowserSessionRegistry(fixedClock);
    }

    @Test
    void register_returnsSessionWithGeneratedId() {
        WebSocketSession ws = mockWs("ws-1");
        BrowserSession s = registry.register("alice", ws, "1.0.0");

        assertThat(s.getId()).isNotBlank();
        assertThat(s.getSubject()).isEqualTo("alice");
        assertThat(s.getAgentVersion()).isEqualTo("1.0.0");
        assertThat(s.getLastHeartbeatAt()).isEqualTo(Instant.ofEpochMilli(1_730_000_000_000L));
    }

    @Test
    void register_sameSubject_replacesPreviousSession() {
        WebSocketSession ws1 = mockWs("ws-1");
        WebSocketSession ws2 = mockWs("ws-2");
        BrowserSession s1 = registry.register("alice", ws1, "1.0.0");
        BrowserSession s2 = registry.register("alice", ws2, "1.0.0");

        assertThat(s2.getId()).isNotEqualTo(s1.getId());
        assertThat(registry.find(s1.getId())).isEmpty();          // old removed
        assertThat(registry.find(s2.getId())).isPresent();        // new active
        assertThat(registry.sizeForSubject("alice")).isEqualTo(1);
        // Old ws was closed with 4409
        try {
            verify(ws1).close(argThat(s -> s.getCode() == 4409));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void heartbeat_updatesLastHeartbeatAt() {
        BrowserSession s = registry.register("alice", mockWs("ws"), "1.0.0");

        Clock later = Clock.fixed(Instant.ofEpochMilli(1_730_000_005_000L), ZoneOffset.UTC);
        registry.setClockForTest(later);
        registry.heartbeat(s.getId());

        assertThat(registry.find(s.getId()).orElseThrow().getLastHeartbeatAt())
                .isEqualTo(Instant.ofEpochMilli(1_730_000_005_000L));
    }

    @Test
    void reapStale_removesSessionsBeyondGrace() {
        BrowserSession s = registry.register("alice", mockWs("ws"), "1.0.0");

        // 31 seconds later — past the 30s grace
        Clock later = Clock.fixed(Instant.ofEpochMilli(1_730_000_031_000L), ZoneOffset.UTC);
        registry.setClockForTest(later);

        int reaped = registry.reapStale();
        assertThat(reaped).isEqualTo(1);
        assertThat(registry.find(s.getId())).isEmpty();
    }

    @Test
    void reapStale_keepsLiveSessions() {
        BrowserSession s = registry.register("alice", mockWs("ws"), "1.0.0");
        Clock later = Clock.fixed(Instant.ofEpochMilli(1_730_000_005_000L), ZoneOffset.UTC);
        registry.setClockForTest(later);

        assertThat(registry.reapStale()).isZero();
        assertThat(registry.find(s.getId())).isPresent();
    }

    // --- Concurrency invariant (P1-3 fix) ---
    @Test
    void register_concurrentSameSubject_leavesExactlyOneLiveSession() throws Exception {
        int parallelism = 64;
        var pool = java.util.concurrent.Executors.newFixedThreadPool(parallelism);
        var ready = new java.util.concurrent.CountDownLatch(parallelism);
        var go = new java.util.concurrent.CountDownLatch(1);
        var done = new java.util.concurrent.CountDownLatch(parallelism);
        var sessionIds = java.util.concurrent.ConcurrentHashMap.<String>newKeySet();

        for (int i = 0; i < parallelism; i++) {
            final int idx = i;
            pool.submit(() -> {
                try {
                    ready.countDown();
                    go.await();
                    BrowserSession s = registry.register("alice", mockWs("ws-" + idx), "1.0.0");
                    sessionIds.add(s.getId());
                } catch (Exception ignored) {
                } finally {
                    done.countDown();
                }
            });
        }
        ready.await();
        go.countDown();             // unleash all threads at once
        done.await();
        pool.shutdown();

        // Invariant: exactly one live session for the subject.
        assertThat(registry.sizeForSubject("alice")).isEqualTo(1);
        // Invariant: byId.size() also == 1 (the bug Codex caught let this be > 1).
        assertThat(registry.size()).isEqualTo(1);
        // The surviving id is one of the N generated.
        var survivingId = registry.findBySubject("alice").orElseThrow().getId();
        assertThat(sessionIds).contains(survivingId);
    }

    private WebSocketSession mockWs(String id) {
        WebSocketSession ws = mock(WebSocketSession.class);
        when(ws.getId()).thenReturn(id);
        when(ws.isOpen()).thenReturn(true);
        return ws;
    }
}
