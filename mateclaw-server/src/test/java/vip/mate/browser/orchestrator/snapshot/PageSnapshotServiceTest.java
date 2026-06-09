package vip.mate.browser.orchestrator.snapshot;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import vip.mate.browser.edge.action.ActionKind;
import vip.mate.browser.edge.action.TabRef;
import vip.mate.browser.edge.session.BrowserSession;
import vip.mate.browser.orchestrator.domain.PageEvent;
import vip.mate.browser.orchestrator.domain.PageSnapshot;
import vip.mate.browser.orchestrator.domain.Viewport;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Tests for the F5 PageSnapshotService freshness state machine.
 *
 * <p>The spec is canonical at:
 * <ul>
 *   <li>{@code docs/specs/edge-protocol.md} §"A11y snapshot lifecycle"</li>
 *   <li>{@code docs/plans/2026-05-28-browser-agent-phase-2.md} §"Task F5"</li>
 * </ul>
 *
 * <p>Each test exercises a single transition. Network freshness is mocked via
 * a Mockito stub of {@link SnapshotEdgeClient}; time freshness is mocked via
 * a hand-rolled {@link TestClock} that lets us {@code advance(Duration)}.
 */
class PageSnapshotServiceTest {

    /** Resolved tab id echoed by the (mocked) Extension. Single-tab-fixture for most tests. */
    private static final long TAB_ID = 42L;
    private static final long OTHER_TAB_ID = 43L;
    private static final String SESSION_ID = "sess-1";

    private SnapshotEdgeClient client;
    private TestClock clock;
    private DefaultPageSnapshotService service;
    private BrowserSession session;

    /** Two distinct stubbed snapshots so we can tell a refresh from a cache hit. */
    private PageSnapshot firstSnap;
    private PageSnapshot secondSnap;

    @BeforeEach
    void setup() {
        client = mock(SnapshotEdgeClient.class);
        clock = new TestClock(Instant.parse("2026-05-29T00:00:00Z"));
        service = new DefaultPageSnapshotService(client, clock);

        session = BrowserSession.builder()
                .id(SESSION_ID)
                .subject("alice")
                .agentVersion("0.2.0")
                .ws(null)
                .lastHeartbeatAt(clock.instant())
                .build();

        firstSnap = new PageSnapshot(
                "snap-1",
                clock.instant().toEpochMilli(),
                TAB_ID,
                "Button[ref=ref_1]: Submit @{100,200 80x32}",
                new Viewport(1280, 800),
                "https://example.com/first",
                "First");
        secondSnap = new PageSnapshot(
                "snap-2",
                clock.instant().plusMillis(500).toEpochMilli(),
                TAB_ID,
                "Link[ref=ref_2]: Read more @{200,400 120x18}",
                new Viewport(1280, 800),
                "https://example.com/second",
                "Second");

        // Default stub: every request returns firstSnap. Individual tests reset()
        // and re-stub when they care about distinguishing first vs second fetch.
        when(client.request(any(BrowserSession.class), any(TabRef.class), anyString()))
                .thenReturn(Mono.just(firstSnap));
    }

    @Test
    void firstRequest_fetchesAndCachesAsFresh() {
        PageSnapshot snap = service.request(session, new TabRef.Main(), "default").block();

        assertThat(snap).isNotNull();
        assertThat(snap.snapshotId()).isEqualTo("snap-1");
        verify(client, times(1)).request(eq(session), any(TabRef.class), eq("default"));
    }

    @Test
    void secondRequestSameKey_returnsCacheNoFetch() {
        service.request(session, new TabRef.Main(), "default").block();
        reset(client);

        PageSnapshot snap = service.request(session, new TabRef.Main(), "default").block();

        assertThat(snap).isNotNull();
        assertThat(snap.snapshotId()).isEqualTo("snap-1");
        verifyNoInteractions(client);
    }

    @Test
    void emptyTreeSnapshot_notCachedFresh_nextRequestRefetches() {
        // A blank-tree snapshot (blank / closed / not-yet-laid-out tab) must NOT
        // be replayed from cache: it is cached STALE so the very next observe
        // refetches. Otherwise one transient empty tree sticks for MAX_AGE — the
        // "时好时坏" empty-observe the user hit (a single empty read amplified to 30s).
        PageSnapshot emptySnap = new PageSnapshot(
                "snap-empty",
                clock.instant().toEpochMilli(),
                TAB_ID,
                "",
                new Viewport(1280, 800),
                "about:blank",
                "");
        reset(client);
        when(client.request(any(BrowserSession.class), any(TabRef.class), anyString()))
                .thenReturn(Mono.just(emptySnap));

        PageSnapshot first = service.request(session, new TabRef.Main(), "default").block();
        assertThat(first).isNotNull();
        assertThat(first.tree()).isEmpty();

        // Next request must refetch (cache miss on STALE), NOT serve the empty.
        reset(client);
        when(client.request(any(BrowserSession.class), any(TabRef.class), anyString()))
                .thenReturn(Mono.just(secondSnap));
        PageSnapshot second = service.request(session, new TabRef.Main(), "default").block();

        assertThat(second.snapshotId()).isEqualTo("snap-2");
        verify(client, times(1)).request(eq(session), any(TabRef.class), eq("default"));
    }

    @Test
    void requestFresh_bypassesUsableCache_andRefetches() {
        // observe() uses requestFresh: it must NOT serve a cached snapshot even
        // when the entry is FRESH and within TTL — observe means "read the page
        // NOW". This is what stops a post-submit observe from replaying the
        // pre-navigation (homepage) snapshot and falsely concluding the search
        // failed (the re-search loop the user hit).
        service.request(session, new TabRef.Main(), "default").block(); // caches firstSnap FRESH
        reset(client);
        when(client.request(any(BrowserSession.class), any(TabRef.class), anyString()))
                .thenReturn(Mono.just(secondSnap));

        PageSnapshot snap = service.requestFresh(session, new TabRef.Main(), "default").block();

        assertThat(snap.snapshotId()).isEqualTo("snap-2"); // live snapshot, NOT the FRESH cache entry
        verify(client, times(1)).request(eq(session), any(TabRef.class), eq("default"));
    }

    @Test
    void requestFresh_repopulatesCacheFresh_soFollowingRequestReusesIt() {
        // After observe(requestFresh) lands a live snapshot, the immediately
        // following click grounding (which uses the cached request()) must reuse
        // it — no second wire fetch — so the click acts on the SAME tree the
        // agent just observed.
        when(client.request(any(BrowserSession.class), any(TabRef.class), anyString()))
                .thenReturn(Mono.just(secondSnap));
        service.requestFresh(session, new TabRef.Main(), "default").block();
        reset(client);

        PageSnapshot reused = service.request(session, new TabRef.Main(), "default").block();

        assertThat(reused.snapshotId()).isEqualTo("snap-2");
        verifyNoInteractions(client);
    }

    @Test
    void navigateAction_marksStale_nextRequestRefetches() {
        service.request(session, new TabRef.Main(), "default").block();
        service.onActionSuccess(SESSION_ID, TAB_ID, ActionKind.NAVIGATE);

        reset(client);
        when(client.request(any(BrowserSession.class), any(TabRef.class), anyString()))
                .thenReturn(Mono.just(secondSnap));

        PageSnapshot snap = service.request(session, new TabRef.Main(), "default").block();

        assertThat(snap).isNotNull();
        assertThat(snap.snapshotId()).isEqualTo("snap-2");
        verify(client, times(1)).request(eq(session), any(TabRef.class), eq("default"));
    }

    @Test
    void clickAction_marksSuspect_butUsableOnce() {
        service.request(session, new TabRef.Main(), "default").block();
        service.onActionSuccess(SESSION_ID, TAB_ID, ActionKind.CLICK);

        reset(client);
        // SUSPECT must serve cache; the edge client must NOT be called.
        PageSnapshot snap = service.request(session, new TabRef.Main(), "default").block();
        assertThat(snap).isNotNull();
        assertThat(snap.snapshotId()).isEqualTo("snap-1");
        verifyNoInteractions(client);

        // After the orchestrator's ground attempt misses it calls invalidate;
        // the very next request must refetch.
        when(client.request(any(BrowserSession.class), any(TabRef.class), anyString()))
                .thenReturn(Mono.just(secondSnap));
        service.invalidate(SESSION_ID, TAB_ID);
        PageSnapshot fresh = service.request(session, new TabRef.Main(), "default").block();
        assertThat(fresh).isNotNull();
        assertThat(fresh.snapshotId()).isEqualTo("snap-2");
        verify(client, times(1)).request(eq(session), any(TabRef.class), eq("default"));
    }

    @Test
    void typeAction_marksSuspect() {
        service.request(session, new TabRef.Main(), "default").block();
        service.onActionSuccess(SESSION_ID, TAB_ID, ActionKind.TYPE);

        reset(client);
        PageSnapshot snap = service.request(session, new TabRef.Main(), "default").block();

        assertThat(snap.snapshotId()).isEqualTo("snap-1");
        verifyNoInteractions(client);
    }

    @Test
    void scrollAction_marksSuspect() {
        service.request(session, new TabRef.Main(), "default").block();
        service.onActionSuccess(SESSION_ID, TAB_ID, ActionKind.SCROLL);

        reset(client);
        PageSnapshot snap = service.request(session, new TabRef.Main(), "default").block();

        assertThat(snap.snapshotId()).isEqualTo("snap-1");
        verifyNoInteractions(client);
    }

    @Test
    void moveMouseAction_doesNotChangeState() {
        // After a fresh request, the cache is FRESH. MOVE_MOUSE leaves it FRESH —
        // not SUSPECT, not STALE. Subsequent request still served from cache.
        service.request(session, new TabRef.Main(), "default").block();
        service.onActionSuccess(SESSION_ID, TAB_ID, ActionKind.MOVE_MOUSE);

        reset(client);
        PageSnapshot snap = service.request(session, new TabRef.Main(), "default").block();

        assertThat(snap.snapshotId()).isEqualTo("snap-1");
        verifyNoInteractions(client);
    }

    @Test
    void waitAction_doesNotChangeState() {
        service.request(session, new TabRef.Main(), "default").block();
        service.onActionSuccess(SESSION_ID, TAB_ID, ActionKind.WAIT);

        reset(client);
        PageSnapshot snap = service.request(session, new TabRef.Main(), "default").block();

        assertThat(snap.snapshotId()).isEqualTo("snap-1");
        verifyNoInteractions(client);
    }

    @Test
    void age30sExceeded_isStaleEvenWithoutAction() {
        service.request(session, new TabRef.Main(), "default").block();
        clock.advance(Duration.ofSeconds(31));

        reset(client);
        when(client.request(any(BrowserSession.class), any(TabRef.class), anyString()))
                .thenReturn(Mono.just(secondSnap));
        PageSnapshot snap = service.request(session, new TabRef.Main(), "default").block();

        assertThat(snap.snapshotId()).isEqualTo("snap-2");
        verify(client, times(1)).request(eq(session), any(TabRef.class), eq("default"));
    }

    @Test
    void age29s_stillUsesCache() {
        // Boundary check on the TTL — anything STRICTLY under 30 s stays cacheable.
        service.request(session, new TabRef.Main(), "default").block();
        clock.advance(Duration.ofSeconds(29));

        reset(client);
        PageSnapshot snap = service.request(session, new TabRef.Main(), "default").block();

        assertThat(snap.snapshotId()).isEqualTo("snap-1");
        verifyNoInteractions(client);
    }

    @Test
    void pageEvent_navigated_marksStale() {
        service.request(session, new TabRef.Main(), "default").block();
        service.onPageEvent(SESSION_ID, TAB_ID, PageEvent.NAVIGATED);

        reset(client);
        when(client.request(any(BrowserSession.class), any(TabRef.class), anyString()))
                .thenReturn(Mono.just(secondSnap));
        PageSnapshot snap = service.request(session, new TabRef.Main(), "default").block();

        assertThat(snap.snapshotId()).isEqualTo("snap-2");
        verify(client, times(1)).request(eq(session), any(TabRef.class), eq("default"));
    }

    @Test
    void pageEvent_tabClosed_removesEntry_nextRequestRefetches() {
        service.request(session, new TabRef.Main(), "default").block();
        service.onPageEvent(SESSION_ID, TAB_ID, PageEvent.TAB_CLOSED);

        reset(client);
        when(client.request(any(BrowserSession.class), any(TabRef.class), anyString()))
                .thenReturn(Mono.just(secondSnap));
        PageSnapshot snap = service.request(session, new TabRef.Main(), "default").block();

        // Entry was removed, so this is treated as a fresh first request → fetch.
        assertThat(snap.snapshotId()).isEqualTo("snap-2");
        verify(client, times(1)).request(eq(session), any(TabRef.class), eq("default"));
    }

    @Test
    void invalidate_forcesStale() {
        service.request(session, new TabRef.Main(), "default").block();
        service.invalidate(SESSION_ID, TAB_ID);

        reset(client);
        when(client.request(any(BrowserSession.class), any(TabRef.class), anyString()))
                .thenReturn(Mono.just(secondSnap));
        PageSnapshot snap = service.request(session, new TabRef.Main(), "default").block();

        assertThat(snap.snapshotId()).isEqualTo("snap-2");
        verify(client, times(1)).request(eq(session), any(TabRef.class), eq("default"));
    }

    @Test
    void onActionSuccess_unrelatedKey_doesNotAffectOtherTabs() {
        // First tab is cached.
        service.request(session, new TabRef.Main(), "default").block();

        // Action.result for a DIFFERENT (sessionId, tabId) must not mutate the cached entry.
        service.onActionSuccess(SESSION_ID, OTHER_TAB_ID, ActionKind.NAVIGATE);

        reset(client);
        PageSnapshot snap = service.request(session, new TabRef.Main(), "default").block();
        assertThat(snap.snapshotId()).isEqualTo("snap-1");
        verifyNoInteractions(client);
    }

    @Test
    void invalidate_absentEntry_isNoOp() {
        // No prior request; invalidate() must not throw, must not call the client.
        service.invalidate(SESSION_ID, TAB_ID);
        verifyNoInteractions(client);

        // Subsequent first request still works.
        PageSnapshot snap = service.request(session, new TabRef.Main(), "default").block();
        assertThat(snap).isNotNull();
        verify(client, times(1)).request(any(), any(), anyString());
    }

    @Test
    void onPageEvent_tabClosed_absentEntry_isNoOp() {
        // No prior request; tab_closed must not throw.
        service.onPageEvent(SESSION_ID, TAB_ID, PageEvent.TAB_CLOSED);
        verifyNoInteractions(client);
    }

    @Test
    void concurrentRequests_sameKey_singleFetch() {
        // Race: two concurrent first-time requests for the same (session, tabRef).
        // The contract is that the edge client is hit AT MOST ONCE and both Monos
        // resolve to the same snapshot.
        //
        // We provoke the race with Mono.zip → block, which subscribes both Monos
        // in parallel on Reactor's parallel scheduler. The edge-client stub returns
        // a Mono that increments a counter exactly once at subscription time, so
        // we can directly assert the number of subscriptions.
        AtomicInteger subscriptions = new AtomicInteger(0);
        reset(client);
        when(client.request(any(BrowserSession.class), any(TabRef.class), anyString()))
                .thenAnswer(inv -> Mono.fromCallable(() -> {
                    subscriptions.incrementAndGet();
                    // Tiny sleep to widen the race window so both subscribers
                    // observe the cache miss before the first response lands.
                    Thread.sleep(20);
                    return firstSnap;
                }));

        Mono<PageSnapshot> req1 = service.request(session, new TabRef.Main(), "default");
        Mono<PageSnapshot> req2 = service.request(session, new TabRef.Main(), "default");

        var both = Mono.zip(
                req1.subscribeOn(reactor.core.scheduler.Schedulers.parallel()),
                req2.subscribeOn(reactor.core.scheduler.Schedulers.parallel())
        ).block(Duration.ofSeconds(5));

        assertThat(both).isNotNull();
        assertThat(both.getT1().snapshotId()).isEqualTo("snap-1");
        assertThat(both.getT2().snapshotId()).isEqualTo("snap-1");
        assertThat(subscriptions.get())
                .as("edge client must be subscribed exactly once for concurrent cache misses on the same key")
                .isEqualTo(1);
    }

    /**
     * Hand-rolled mutable Clock for tests. JUnit fixtures can {@code advance(d)}
     * the wall-clock without sleeping the test thread. Thread-safe enough for
     * Reactor schedulers we drive in {@link #concurrentRequests_sameKey_singleFetch()}.
     */
    private static final class TestClock extends Clock {
        private volatile Instant now;

        TestClock(Instant start) {
            this.now = start;
        }

        void advance(Duration d) {
            this.now = this.now.plus(d);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this; // tests are zone-agnostic
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
