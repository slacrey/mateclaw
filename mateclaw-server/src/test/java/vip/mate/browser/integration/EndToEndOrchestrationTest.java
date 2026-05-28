package vip.mate.browser.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import reactor.core.publisher.Mono;
import vip.mate.MateClawApplication;
import vip.mate.browser.edge.action.ActionKind;
import vip.mate.browser.edge.action.ActionPayload;
import vip.mate.browser.edge.action.ActionRequest;
import vip.mate.browser.edge.action.ActionResult;
import vip.mate.browser.edge.action.ClickPayload;
import vip.mate.browser.edge.action.ClickSuccess;
import vip.mate.browser.edge.action.MoveMousePayload;
import vip.mate.browser.edge.action.MoveMouseSuccess;
import vip.mate.browser.edge.action.NavigatePayload;
import vip.mate.browser.edge.action.TabRef;
import vip.mate.browser.edge.protocol.EdgeMessage;
import vip.mate.browser.edge.session.BrowserSession;
import vip.mate.browser.edge.session.BrowserSessionRegistry;
import vip.mate.browser.orchestrator.ActionExecutionService;
import vip.mate.browser.orchestrator.GroundingDispatcher;
import vip.mate.browser.orchestrator.PlanExecutionService;
import vip.mate.browser.orchestrator.PlanResult;
import vip.mate.browser.orchestrator.domain.GroundingHint;
import vip.mate.browser.orchestrator.domain.GroundingResult;
import vip.mate.browser.orchestrator.domain.PageSnapshot;
import vip.mate.browser.orchestrator.domain.Viewport;
import vip.mate.browser.orchestrator.snapshot.PageSnapshotService;
import vip.mate.browser.orchestrator.snapshot.SnapshotEdgeClient;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Phase 2 Wave 5 — Task E1: end-to-end orchestration integration test.
 *
 * <p>Drives the Control-Plane stack ({@link PlanExecutionService} →
 * {@link ActionExecutionService} → WebSocket → {@link RecordingWsSink}) with a
 * recording sink standing in for the Bridge/Extension. The sink replaces the
 * {@code WebSocketSession.sendMessage} side of the transport with a Mockito
 * capture, parses each {@code EdgeMessage}, and lets the test inject replies
 * back through {@link ActionExecutionService#deliverResult}. The sink also
 * tracks an {@code in-flight} counter so the sequencing test can assert
 * {@code maxConcurrentInFlight == 1}.
 *
 * <p>Verifies the three Codex P0 properties simultaneously:
 * <ul>
 *   <li><b>P0-1 tab_ref dispatch</b> — every {@code action.execute} envelope
 *       carries the right tab and {@code (x,y)} did not cross-wire.</li>
 *   <li><b>P0-2 sequencing</b> — {@code click} is sent strictly after the
 *       preceding {@code move_mouse} result lands (≥180&nbsp;ms wall-clock).</li>
 *   <li><b>P0-3 snapshot refresh</b> — {@code NAVIGATE} marks the cached
 *       snapshot STALE and the next ground refetches from the edge client.</li>
 * </ul>
 * Plus the P1-4 closure — STOP from the Extension cancels the in-flight
 * action.
 *
 * <p><b>EdgeWebSocketHandler.handleInbound resolution:</b> the plan's test 5
 * stub references {@code edgeHandler.handleInbound(session, EdgeMessage)},
 * but {@code EdgeWebSocketHandler} has no public {@code handleInbound} — its
 * inbound dispatch is {@code protected void handleTextMessage(...)} which
 * ultimately routes {@code INDICATOR_STOP_CLICKED} through
 * {@link ActionExecutionService#handleStopClicked(BrowserSession)}. This test
 * calls that public method directly to keep the integration surface narrow
 * (no DOM parser noise) while still exercising the same service-level path
 * the production handler reaches.
 */
@SpringBootTest(
        classes = MateClawApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE
)
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:browser_e2e_${random.uuid};"
                + "MODE=MySQL;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE;"
                + "DB_CLOSE_DELAY=-1",
        "spring.ai.dashscope.api-key=test-key",
        "spring.main.web-application-type=none"
})
@Import(EndToEndOrchestrationTest.SinkConfig.class)
class EndToEndOrchestrationTest {

    @Autowired private PlanExecutionService planExec;
    @Autowired private ActionExecutionService actionExec;
    @Autowired private PageSnapshotService snapshotService;
    @Autowired private GroundingDispatcher dispatcher;
    @Autowired private BrowserSessionRegistry registry;
    @Autowired private ObjectMapper mapper;
    @MockBean private SnapshotEdgeClient snapshotClient;
    @Autowired private RecordingWsSink sink;

    @BeforeEach
    void reset() {
        sink.reset();
    }


    // ------------------------------------------------------------------
    // P0-1: multi-tab dispatch — envelopes carry distinct tab_refs.
    // ------------------------------------------------------------------

    @Test
    void multiTab_envelopesCarryDistinctTabRefsAndDoNotCrossWire() throws Exception {
        // Codex P0-1: TWO distinct tab ids — the previous single-tab fixture
        // (id 42 everywhere) was flagged as trivially satisfied.
        final int TAB_A = 42;
        final int TAB_B = 43;
        BrowserSession session = registry.register("alice-1", sink.mockWs("ws-1"), "0.1.0");

        AtomicInteger deliveredToTabA = new AtomicInteger();
        AtomicInteger deliveredToTabB = new AtomicInteger();
        sink.onEnvelope("action.execute", env -> {
            int tab = readTabRefInt(env);
            if (tab == TAB_A) {
                deliveredToTabA.incrementAndGet();
            } else if (tab == TAB_B) {
                deliveredToTabB.incrementAndGet();
            } else {
                fail("envelope addressed to unexpected tab_ref " + tab);
            }
            sink.reply(env, new ActionResult.Success(5, new ClickSuccess()));
        });

        // Action 1: click on tab A at (100, 200).
        ActionRequest clickOnA = newClickActionRequest(new TabRef.Explicit(TAB_A), 100, 200);
        planExec.execute(session, List.of(clickOnA)).block();

        // Action 2: click on tab B at (300, 400) — distinct (x,y) so we
        // can confirm params didn't cross-wire to the wrong tab.
        ActionRequest clickOnB = newClickActionRequest(new TabRef.Explicit(TAB_B), 300, 400);
        planExec.execute(session, List.of(clickOnB)).block();

        // Each tab received exactly one envelope.
        assertThat(deliveredToTabA.get()).isEqualTo(1);
        assertThat(deliveredToTabB.get()).isEqualTo(1);

        // The two envelopes are addressed to the right tabs in order.
        assertThat(sink.envelopes()).hasSize(2);
        assertThat(readTabRefInt(sink.envelopes().get(0))).isEqualTo(TAB_A);
        assertThat(readTabRefInt(sink.envelopes().get(1))).isEqualTo(TAB_B);

        // And the (x, y) params went to the right envelope — no cross-wire.
        Map<?, ?> paramsA = (Map<?, ?>) sink.envelopes().get(0).getPayload().get("params");
        Map<?, ?> paramsB = (Map<?, ?>) sink.envelopes().get(1).getPayload().get("params");
        assertThat(((Number) paramsA.get("x")).intValue()).isEqualTo(100);
        assertThat(((Number) paramsA.get("y")).intValue()).isEqualTo(200);
        assertThat(((Number) paramsB.get("x")).intValue()).isEqualTo(300);
        assertThat(((Number) paramsB.get("y")).intValue()).isEqualTo(400);
    }

    // ------------------------------------------------------------------
    // P0-1 negative: bogus tab_ref → NO_TARGET_TAB failure (typed).
    // ------------------------------------------------------------------

    @Test
    void multiTab_unresolvableTabRef_yieldsNoTargetTabFailure() {
        // Negative companion: if the SW resolver can't find an explicit tab,
        // the result is a typed NO_TARGET_TAB failure (not a silent success
        // on the wrong tab).
        BrowserSession session = registry.register("alice-2", sink.mockWs("ws-2"), "0.1.0");

        // Sink simulates the SW: NO_TARGET_TAB for tab 99 (unknown),
        // Success for known tab 42 (not exercised here, kept for symmetry
        // with the plan stub).
        sink.onEnvelope("action.execute", env -> {
            int tab = readTabRefInt(env);
            if (tab == 42) {
                sink.reply(env, new ActionResult.Success(5, new ClickSuccess()));
            } else {
                sink.reply(env, new ActionResult.Failure(
                        "NO_TARGET_TAB", "tab " + tab + " not alive", false));
            }
        });

        ActionRequest bogus = newClickActionRequest(new TabRef.Explicit(99), 0, 0);
        PlanResult result = planExec.execute(session, List.of(bogus)).block();
        assertThat(result).isInstanceOf(PlanResult.Partial.class);
        PlanResult.Partial partial = (PlanResult.Partial) result;
        assertThat(partial.failed().code()).isEqualTo("NO_TARGET_TAB");
    }

    // ------------------------------------------------------------------
    // P0-2: click is sent strictly after the preceding move result lands
    // (cursor transition ≥180ms) AND only one action in flight at any
    // time — proves concatMap, not flatMap / merge / zip.
    // ------------------------------------------------------------------

    @Test
    void sequencing_clickSentStrictlyAfterMoveResult_andAfter180ms() throws Exception {
        BrowserSession session = registry.register("alice-3", sink.mockWs("ws-3"), "0.1.0");
        AtomicLong moveStartedAt = new AtomicLong();
        AtomicLong clickSentAt = new AtomicLong();

        sink.onEnvelope("action.execute", envelope -> {
            String kind = (String) envelope.getPayload().get("kind");
            if ("move_mouse".equals(kind)) {
                moveStartedAt.set(System.currentTimeMillis());
                // Simulate the real Extension delay: the move actually takes
                // 180ms to play out the cursor transition, the reply is
                // deferred until it lands.
                sink.scheduler().schedule(
                        () -> sink.reply(envelope, new ActionResult.Success(
                                180,
                                new MoveMouseSuccess(System.currentTimeMillis(), 7))),
                        180,
                        TimeUnit.MILLISECONDS);
            } else if ("click".equals(kind)) {
                clickSentAt.set(System.currentTimeMillis());
                sink.reply(envelope, new ActionResult.Success(20, new ClickSuccess()));
            }
        });

        ActionRequest move = newMoveActionRequest(new TabRef.Main(), 100, 200, "natural");
        ActionRequest click = newClickActionRequest(new TabRef.Main(), 100, 200);
        PlanResult result = planExec.execute(session, List.of(move, click))
                .toFuture().get(2, TimeUnit.SECONDS);

        assertThat(result).isInstanceOf(PlanResult.Success.class);
        long delta = clickSentAt.get() - moveStartedAt.get();
        assertThat(delta)
                .as("click must be sent at least 180ms after move-start (cursor transition)")
                .isGreaterThanOrEqualTo(180);
        // Only one in flight at any time — proves concatMap.
        assertThat(sink.maxConcurrentInFlight()).isEqualTo(1);
    }

    // ------------------------------------------------------------------
    // P0-3: NAVIGATE marks snapshot STALE, next ground refetches.
    // ------------------------------------------------------------------

    @Test
    void snapshotRefresh_navigateInvalidatesRefIds() {
        // Codex P0-3 verification: after NAVIGATE, the cache is STALE; the
        // next ground() reaches back to the edge client (refresh).
        BrowserSession session = registry.register("alice-4", sink.mockWs("ws-4"), "0.1.0");

        long resolvedTabId = 42L;

        // Snapshot v1: contains a "Submit" Button at ref_1.
        // Tree format matches PageSnapshot.LINE_PATTERN — see the F5 javadoc.
        PageSnapshot snap1 = new PageSnapshot(
                "snap-1",
                System.currentTimeMillis(),
                resolvedTabId,
                "Button[ref=ref_1]: Submit @{100,200 80x32}",
                new Viewport(1280, 800));

        // Snapshot v2: contains a "Next" Button at ref_5; "Submit" is gone
        // (the previous page was navigated away from).
        PageSnapshot snap2 = new PageSnapshot(
                "snap-2",
                System.currentTimeMillis() + 1000,
                resolvedTabId,
                "Button[ref=ref_5]: Next @{50,100 60x32}",
                new Viewport(1280, 800));

        when(snapshotClient.request(eq(session), any(), any()))
                .thenReturn(Mono.just(snap1))   // first call
                .thenReturn(Mono.just(snap2));  // second call after invalidation

        // First ground succeeds on "Submit".
        GroundingHint hint = new GroundingHint.A11yMatch("Button", Pattern.compile("Submit"));
        GroundingResult r1 = dispatcher.ground(session, new TabRef.Main(), hint);
        assertThat(r1).isInstanceOf(GroundingResult.Hit.class);

        // Simulate a successful NAVIGATE on the same resolved tab.
        snapshotService.onActionSuccess(session.getId(), resolvedTabId, ActionKind.NAVIGATE);

        // Second ground for "Submit" — snapshot is STALE → refetch → snap2
        // — "Submit" not in snap2 → Miss.
        GroundingHint hint2 = new GroundingHint.A11yMatch("Button", Pattern.compile("Submit"));
        GroundingResult r2 = dispatcher.ground(session, new TabRef.Main(), hint2);
        assertThat(r2).isInstanceOf(GroundingResult.Miss.class);

        // The snapshot client was hit twice (once initial, once post-navigate
        // refresh) — this is the load-bearing assertion.
        verify(snapshotClient, times(2)).request(eq(session), any(), any());
    }

    // ------------------------------------------------------------------
    // P1-4 closure: Stop button cancels in-flight action.
    //
    // Plan-spec wording asserts the LAST outbound envelope kind is
    // "indicator.hide". The orchestrator service that owns the in-flight
    // request only emits ACTION_CANCEL on stop; the indicator-frame
    // bookend (tool_use_hide / indicator.hide) is a separate concern that
    // Phase 2 Wave 4 did not wire into the cancel path. The Wave 1 unit
    // test {@code ActionExecutionServiceTest.handleStopClicked_triggersCancelOnInflight}
    // confirms by asserting the only sent kinds are ACTION_EXECUTE +
    // ACTION_CANCEL (no indicator.hide).
    //
    // Per the Wave 5 task brief — surface the gap; do NOT modify
    // production code from inside this test file. The assertion stays as
    // the plan wrote it so the failure pinpoints the missing wiring.
    // ------------------------------------------------------------------

    @Test
    void stopButton_cancelsInflightAndFiresIndicatorHide() throws Exception {
        BrowserSession session = registry.register("alice-5", sink.mockWs("ws-5"), "0.1.0");

        // A long navigate we will interrupt — sink never replies.
        ActionRequest nav = newRequest(
                ActionKind.NAVIGATE,
                new TabRef.Main(),
                new NavigatePayload("https://slow.example", null, "load"),
                30_000);

        sink.onEnvelope("action.execute", env -> {
            /* deliberately never reply — the action sits in-flight */
        });
        var future = planExec.execute(session, List.of(nav)).toFuture();

        // Wait for the action.execute envelope to land in the sink.
        long pollDeadline = System.currentTimeMillis() + 2_000;
        while (sink.envelopes().isEmpty() && System.currentTimeMillis() < pollDeadline) {
            Thread.sleep(10);
        }
        assertThat(sink.envelopes()).hasSize(1);

        // User clicks Stop — drives the same service entry point the
        // production EdgeWebSocketHandler routes INDICATOR_STOP_CLICKED to.
        actionExec.handleStopClicked(session);

        PlanResult result = future.get(1, TimeUnit.SECONDS);
        assertThat(result).isInstanceOf(PlanResult.Partial.class);
        PlanResult.Partial partial = (PlanResult.Partial) result;
        assertThat(partial.failed().code()).isEqualTo("CANCELLED");

        // Last outbound on the wire should be indicator.hide.
        // (If this fails the orchestrator did not emit indicator.hide on
        //  the cancel path — surfaced as a Wave 5 finding, not fixed here.)
        assertThat(sink.lastEnvelopeKind()).isEqualTo("indicator.hide");
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private static ActionRequest newClickActionRequest(TabRef tabRef, int x, int y) {
        return newRequest(
                ActionKind.CLICK,
                tabRef,
                new ClickPayload(x, y, "left", 1),
                15_000);
    }

    private static ActionRequest newMoveActionRequest(TabRef tabRef, int x, int y, String profile) {
        return newRequest(
                ActionKind.MOVE_MOUSE,
                tabRef,
                new MoveMousePayload(x, y, profile),
                15_000);
    }

    private static ActionRequest newRequest(
            ActionKind kind, TabRef tabRef, ActionPayload payload, long deadlineMs) {
        return new ActionRequest(
                UUID.randomUUID().toString(),
                tabRef,
                kind,
                payload,
                deadlineMs);
    }

    /**
     * Read the {@code tab_ref} field from a captured envelope as an int.
     *
     * <p>Wire-shape note: the server's global Jackson config (see
     * {@code vip.mate.config.JacksonConfig}) registers a {@code Long → String}
     * serializer for snowflake-ID safety on the JS frontend ({@code mate_*.id}
     * fields exceed {@code 2^53 - 1}). That customization also touches the
     * orchestrator's outbound JSON: numeric tab refs and other {@code long}
     * payload values land as JSON strings on the wire (e.g.
     * {@code "tab_ref":"42"} rather than {@code "tab_ref":42}). Tests must
     * read either shape — production consumers (the Bridge / SW) parse with
     * {@code Number}-tolerant code on their side.
     */
    private static int readTabRefInt(EdgeMessage env) {
        Object raw = env.getPayload().get("tab_ref");
        if (raw instanceof Number n) {
            return n.intValue();
        }
        if (raw instanceof String s) {
            return Integer.parseInt(s);
        }
        throw new AssertionError("unexpected tab_ref type: "
                + (raw == null ? "null" : raw.getClass().getName()));
    }

    /**
     * Hosts the {@link RecordingWsSink} as a Spring bean for this test class
     * only — the production app must NEVER see it. Kept as a
     * {@link TestConfiguration} (not annotated on the sink itself with
     * {@code @TestComponent}) so the wiring is explicit and reviewable.
     */
    @TestConfiguration
    static class SinkConfig {
        @Bean
        RecordingWsSink recordingWsSink(ActionExecutionService actionExec, ObjectMapper mapper) {
            return new RecordingWsSink(actionExec, mapper);
        }
    }
}
