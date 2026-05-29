package vip.mate.tool.builtin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import vip.mate.browser.edge.action.ActionKind;
import vip.mate.browser.edge.action.ActionRequest;
import vip.mate.browser.edge.action.ActionResult;
import vip.mate.browser.edge.action.ClickSuccess;
import vip.mate.browser.edge.action.NavigateSuccess;
import vip.mate.browser.edge.action.TabRef;
import vip.mate.browser.edge.session.BrowserSession;
import vip.mate.browser.edge.session.BrowserSessionRegistry;
import vip.mate.browser.orchestrator.ActionPlanner;
import vip.mate.browser.orchestrator.GroundingDispatcher;
import vip.mate.browser.orchestrator.PlanExecutionService;
import vip.mate.browser.orchestrator.PlanResult;
import vip.mate.browser.orchestrator.domain.BBox;
import vip.mate.browser.orchestrator.domain.GroundedTarget;
import vip.mate.browser.orchestrator.domain.GroundingHint;
import vip.mate.browser.orchestrator.domain.GroundingResult;
import vip.mate.browser.orchestrator.domain.PageSnapshot;
import vip.mate.browser.orchestrator.domain.Viewport;
import vip.mate.browser.orchestrator.snapshot.PageSnapshotService;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit coverage for {@link ExtensionBrowserTool}. All Phase 2 services
 * are mocked; we verify that each {@code @Tool} method shapes the right
 * {@link ActionRequest} / {@link GroundingHint} and surfaces the
 * downstream result as the documented JSON.
 *
 * <p>Six tools × happy + edge cases ≈ 9 cases. The grounding-failure
 * fallthrough is the highest-value path because that's how the LLM
 * recovers without crashing the run.
 */
class ExtensionBrowserToolTest {

    private BrowserSessionRegistry registry;
    private ActionPlanner planner;
    private PlanExecutionService planExec;
    private GroundingDispatcher dispatcher;
    private PageSnapshotService snapshotService;
    private ObjectMapper mapper;
    private ExtensionBrowserTool tool;
    private BrowserSession session;

    @BeforeEach
    void setUp() {
        registry = mock(BrowserSessionRegistry.class);
        planner = mock(ActionPlanner.class);
        planExec = mock(PlanExecutionService.class);
        dispatcher = mock(GroundingDispatcher.class);
        snapshotService = mock(PageSnapshotService.class);
        mapper = new ObjectMapper();
        tool = new ExtensionBrowserTool(registry, planner, planExec,
                dispatcher, snapshotService, mapper, "default");
        session = BrowserSession.builder()
                .id("sess-1").subject("default").agentVersion("0.1.0")
                .ws(null).lastHeartbeatAt(java.time.Instant.now()).build();

        when(registry.findBySubject("default")).thenReturn(Optional.of(session));
    }

    // -----------------------------------------------------------------
    // Session resolution
    // -----------------------------------------------------------------

    @Test
    void allTools_returnNO_SESSION_whenNoSubjectMatch() throws Exception {
        when(registry.findBySubject("default")).thenReturn(Optional.empty());

        String out = tool.extension_browser_navigate("https://example.com", null, null);

        JsonNode j = mapper.readTree(out);
        assertThat(j.get("ok").asBoolean()).isFalse();
        assertThat(j.get("code").asText()).isEqualTo("NO_SESSION");
        verify(planExec, never()).execute(any(), any());
    }

    // -----------------------------------------------------------------
    // browser_navigate
    // -----------------------------------------------------------------

    @Test
    void browserNavigate_dispatchesNavigateActionAndReturnsSuccessJson() throws Exception {
        when(planExec.execute(any(), any())).thenReturn(Mono.just((PlanResult)
                new PlanResult.Success(List.of(
                        new ActionResult.Success(123L,
                                new NavigateSuccess("https://example.com", 200, "load"))))));

        String out = tool.extension_browser_navigate("https://example.com", "load", null);

        JsonNode j = mapper.readTree(out);
        assertThat(j.get("ok").asBoolean()).isTrue();
        assertThat(j.get("elapsed_ms").asLong()).isEqualTo(123);

        var captor = org.mockito.ArgumentCaptor.forClass(List.class);
        verify(planExec).execute(any(), captor.capture());
        @SuppressWarnings("unchecked")
        List<ActionRequest> sent = (List<ActionRequest>) captor.getValue();
        assertThat(sent).hasSize(1);
        assertThat(sent.get(0).kind()).isEqualTo(ActionKind.NAVIGATE);
        assertThat(sent.get(0).tabRef()).isEqualTo(new TabRef.Main());
    }

    // -----------------------------------------------------------------
    // browser_click
    // -----------------------------------------------------------------

    @Test
    void browserClick_onHit_callsPlannerAndPlanExec() throws Exception {
        var grounded = new GroundedTarget(new BBox(100, 200, 80, 32));
        when(dispatcher.ground(any(), any(), any()))
                .thenReturn(new GroundingResult.Hit(grounded, "data-test=submit"));
        when(planner.plan(any())).thenReturn(List.of(
                navRequest(ActionKind.MOVE_MOUSE),
                navRequest(ActionKind.CLICK)));
        when(planExec.execute(any(), any())).thenReturn(Mono.just((PlanResult)
                new PlanResult.Success(List.of(
                        new ActionResult.Success(50L, new ClickSuccess())))));

        String out = tool.extension_browser_click("Submit", "button", null);

        JsonNode j = mapper.readTree(out);
        assertThat(j.get("ok").asBoolean()).isTrue();
        verify(planner).plan(any());
    }

    @Test
    void browserClick_onAmbiguous_returnsGROUNDING_AMBIGUOUS_withoutPlanExec() throws Exception {
        var t1 = new GroundedTarget(new BBox(0, 0, 10, 10));
        var t2 = new GroundedTarget(new BBox(20, 20, 10, 10));
        when(dispatcher.ground(any(), any(), any()))
                .thenReturn(new GroundingResult.Ambiguous(List.of(t1, t2), "two Submits"));

        String out = tool.extension_browser_click("Submit", null, null);

        JsonNode j = mapper.readTree(out);
        assertThat(j.get("ok").asBoolean()).isFalse();
        assertThat(j.get("code").asText()).isEqualTo("GROUNDING_AMBIGUOUS");
        assertThat(j.get("message").asText()).contains("two Submits");
        verify(planner, never()).plan(any());
        verify(planExec, never()).execute(any(), any());
    }

    @Test
    void browserClick_onMiss_returnsGROUNDING_MISS() throws Exception {
        when(dispatcher.ground(any(), any(), any()))
                .thenReturn(new GroundingResult.Miss("no element matches"));

        String out = tool.extension_browser_click("Nonexistent", null, null);

        JsonNode j = mapper.readTree(out);
        assertThat(j.get("ok").asBoolean()).isFalse();
        assertThat(j.get("code").asText()).isEqualTo("GROUNDING_MISS");
    }

    @Test
    void browserClick_usesA11yMatchHintWithDefaultRoleButton() throws Exception {
        when(dispatcher.ground(any(), any(), any()))
                .thenReturn(new GroundingResult.Miss("not found"));

        tool.extension_browser_click("Submit", null, null);

        var captor = org.mockito.ArgumentCaptor.forClass(GroundingHint.class);
        verify(dispatcher).ground(any(), any(), captor.capture());
        assertThat(captor.getValue()).isInstanceOf(GroundingHint.A11yMatch.class);
        assertThat(((GroundingHint.A11yMatch) captor.getValue()).role()).isEqualTo("button");
    }

    // -----------------------------------------------------------------
    // browser_type / scroll / wait — minimal coverage
    // -----------------------------------------------------------------

    @Test
    void browserType_dispatchesTypeRequest() throws Exception {
        when(planExec.execute(any(), any())).thenReturn(Mono.just((PlanResult)
                new PlanResult.Success(List.of())));

        tool.extension_browser_type("hello world", null);

        var captor = org.mockito.ArgumentCaptor.forClass(List.class);
        verify(planExec).execute(any(), captor.capture());
        @SuppressWarnings("unchecked")
        List<ActionRequest> sent = (List<ActionRequest>) captor.getValue();
        assertThat(sent.get(0).kind()).isEqualTo(ActionKind.TYPE);
    }

    @Test
    void browserScroll_dispatchesScrollRequest() throws Exception {
        when(planExec.execute(any(), any())).thenReturn(Mono.just((PlanResult)
                new PlanResult.Success(List.of())));

        tool.extension_browser_scroll("down", 800, null);

        var captor = org.mockito.ArgumentCaptor.forClass(List.class);
        verify(planExec).execute(any(), captor.capture());
        @SuppressWarnings("unchecked")
        List<ActionRequest> sent = (List<ActionRequest>) captor.getValue();
        assertThat(sent.get(0).kind()).isEqualTo(ActionKind.SCROLL);
    }

    @Test
    void browserWait_dispatchesWaitRequest_timeStrategy() throws Exception {
        when(planExec.execute(any(), any())).thenReturn(Mono.just((PlanResult)
                new PlanResult.Success(List.of())));

        tool.extension_browser_wait("time", 500L, null, null, null);

        var captor = org.mockito.ArgumentCaptor.forClass(List.class);
        verify(planExec).execute(any(), captor.capture());
        @SuppressWarnings("unchecked")
        List<ActionRequest> sent = (List<ActionRequest>) captor.getValue();
        assertThat(sent.get(0).kind()).isEqualTo(ActionKind.WAIT);
    }

    // -----------------------------------------------------------------
    // browser_observe
    // -----------------------------------------------------------------

    @Test
    void browserObserve_returnsSnapshotTreeAsJson() throws Exception {
        var snap = new PageSnapshot("snap-1", 1L, 42L,
                "Button[ref=ref_1]: Submit @{100,200 80x32}",
                new Viewport(1280, 800));
        when(snapshotService.request(any(), any(), any())).thenReturn(Mono.just(snap));

        String out = tool.extension_browser_observe("interactive", null);

        JsonNode j = mapper.readTree(out);
        assertThat(j.get("ok").asBoolean()).isTrue();
        assertThat(j.get("snapshot_id").asText()).isEqualTo("snap-1");
        assertThat(j.get("tree").asText()).contains("Submit");
        assertThat(j.get("viewport").get("w").asInt()).isEqualTo(1280);
    }

    // -----------------------------------------------------------------
    // Partial plan failure
    // -----------------------------------------------------------------

    @Test
    void planFailureMidStep_returnsPartialWithFailureCode() throws Exception {
        when(planExec.execute(any(), any())).thenReturn(Mono.just((PlanResult)
                new PlanResult.Partial(
                        List.of(new ActionResult.Success(50L, new NavigateSuccess("https://x", 200, "load"))),
                        new ActionResult.Failure("TIMEOUT_PAGE_LOAD", "slow page", true))));

        String out = tool.extension_browser_navigate("https://slow.example", null, null);

        JsonNode j = mapper.readTree(out);
        assertThat(j.get("ok").asBoolean()).isFalse();
        assertThat(j.get("code").asText()).isEqualTo("TIMEOUT_PAGE_LOAD");
        assertThat(j.get("completed_before_failure").asInt()).isEqualTo(1);
    }

    // -----------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------

    private static ActionRequest navRequest(ActionKind kind) {
        return switch (kind) {
            case NAVIGATE -> new ActionRequest(UUID.randomUUID().toString(), new TabRef.Main(),
                    ActionKind.NAVIGATE,
                    new vip.mate.browser.edge.action.NavigatePayload("https://x", null, "load"),
                    15_000L);
            case MOVE_MOUSE -> new ActionRequest(UUID.randomUUID().toString(), new TabRef.Main(),
                    ActionKind.MOVE_MOUSE,
                    new vip.mate.browser.edge.action.MoveMousePayload(140, 216, "natural"),
                    15_000L);
            case CLICK -> new ActionRequest(UUID.randomUUID().toString(), new TabRef.Main(),
                    ActionKind.CLICK,
                    new vip.mate.browser.edge.action.ClickPayload(140, 216, "left", 1),
                    15_000L);
            default -> throw new IllegalArgumentException(kind.toString());
        };
    }
}
