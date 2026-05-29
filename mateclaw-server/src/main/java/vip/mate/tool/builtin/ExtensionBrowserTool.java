package vip.mate.tool.builtin;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.lang.Nullable;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;
import vip.mate.browser.edge.action.ActionKind;
import vip.mate.browser.edge.action.ActionRequest;
import vip.mate.browser.edge.action.ClickPayload;
import vip.mate.browser.edge.action.MoveMousePayload;
import vip.mate.browser.edge.action.NavigatePayload;
import vip.mate.browser.edge.action.ScrollPayload;
import vip.mate.browser.edge.action.TabRef;
import vip.mate.browser.edge.action.TypePayload;
import vip.mate.browser.edge.action.WaitPayload;
import vip.mate.browser.edge.session.BrowserSession;
import vip.mate.browser.edge.session.BrowserSessionRegistry;
import vip.mate.browser.orchestrator.ActionPlanner;
import vip.mate.browser.orchestrator.GroundingDispatcher;
import vip.mate.browser.orchestrator.PlanExecutionService;
import vip.mate.browser.orchestrator.PlanResult;
import vip.mate.browser.orchestrator.domain.GroundingHint;
import vip.mate.browser.orchestrator.domain.GroundingResult;
import vip.mate.browser.orchestrator.domain.Step;
import vip.mate.browser.orchestrator.snapshot.PageSnapshotService;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Browser-control tool that targets the <strong>user's real Chrome window</strong>
 * via the Phase 1/2/2.1 extension stack. Coexists with {@link BrowserUseTool}
 * (which drives a server-side Playwright headless browser).
 *
 * <p><strong>When to use this vs {@code BrowserUseTool}:</strong>
 * <ul>
 *   <li>Use {@code extension_browser_*} (this tool family) when the user wants
 *       to see the agent operating in their own browser — for example, "open
 *       this tab and click Submit" — or when authenticated state in the user's
 *       browser matters.</li>
 *   <li>Use {@code browser_use} (the Playwright tool) for headless scraping,
 *       background automation, or when no user browser is connected.</li>
 * </ul>
 *
 * <p><strong>Six fine-grained {@code @Tool} methods</strong> (not one fat
 * action-enum tool) so the LLM sees one clear contract per action. The
 * coordinate-free hint-text contract (click "Submit" near "Comments")
 * goes through {@link GroundingDispatcher} → DOM/A11y/Vision engines;
 * the {@link ActionPlanner} then turns the resolved target into the
 * pair of {@code move_mouse}+{@code click} action requests, which
 * {@link PlanExecutionService} runs sequentially via {@code concatMap}.
 *
 * <p>Single-session-per-tenant model for Phase 3: tools look up the
 * {@link BrowserSession} by a configurable subject
 * ({@code mateclaw.browser.tool-subject}, default {@code default}). Phase 4
 * will resolve the subject from {@link ToolContext} (per-conversation auth
 * principal); the {@code @Nullable ToolContext} param is already on every
 * method to keep the signature stable across that future change.
 *
 * <p>browser_screenshot is intentionally NOT in this first-pass tool family
 * — Phase 3 T3.2-protocol adds the {@code screenshot.capture.*} wire
 * kinds; the Vision engine (T3.2 real impl) will consume them. The
 * stand-alone screenshot tool ships as a follow-up commit.
 */
@Slf4j
@Component
public class ExtensionBrowserTool {

    /** Default action deadline. Tunable per-call via the {@code deadline_ms} arg. */
    private static final long DEFAULT_DEADLINE_MS = 15_000L;

    private final BrowserSessionRegistry registry;
    private final ActionPlanner planner;
    private final PlanExecutionService planExec;
    private final GroundingDispatcher dispatcher;
    private final PageSnapshotService snapshotService;
    private final ObjectMapper mapper;

    /**
     * Subject under which the user's extension session is registered.
     * Phase 4 will derive this from {@link ToolContext}'s auth principal.
     */
    private final String defaultSubject;

    public ExtensionBrowserTool(BrowserSessionRegistry registry,
                                ActionPlanner planner,
                                PlanExecutionService planExec,
                                GroundingDispatcher dispatcher,
                                PageSnapshotService snapshotService,
                                ObjectMapper mapper,
                                @Value("${mateclaw.browser.tool-subject:default}") String defaultSubject) {
        this.registry = registry;
        this.planner = planner;
        this.planExec = planExec;
        this.dispatcher = dispatcher;
        this.snapshotService = snapshotService;
        this.mapper = mapper;
        this.defaultSubject = defaultSubject;
    }

    // -----------------------------------------------------------------
    // browser_navigate
    // -----------------------------------------------------------------

    @Tool(description = """
            Navigate the user's active browser tab to a URL. Returns a JSON object:
              { "ok": true, "final_url": "...", "load_state": "load" }
            or on failure:
              { "ok": false, "code": "NO_SESSION|NO_TARGET_TAB|TIMEOUT_PAGE_LOAD|...", "message": "..." }
            Use this tool when the user wants their own Chrome window to open a page.
            For background headless navigation prefer the `browser_use` Playwright tool.
            """)
    public String extension_browser_navigate(
            @ToolParam(description = "URL to navigate to, including scheme (http:// or https://)") String url,
            @ToolParam(description = "Wait strategy: 'load' (default) | 'domcontentloaded' | 'network_idle' | 'none'",
                       required = false) String waitFor,
            @Nullable ToolContext ctx) {
        BrowserSession session = resolveSession();
        if (session == null) return noSession();

        ActionRequest req = new ActionRequest(
                newMsgId(),
                new TabRef.Main(),
                ActionKind.NAVIGATE,
                new NavigatePayload(url, null, defaulted(waitFor, "load")),
                DEFAULT_DEADLINE_MS);

        return executePlan(session, List.of(req));
    }

    // -----------------------------------------------------------------
    // browser_click
    // -----------------------------------------------------------------

    @Tool(description = """
            Click an element on the page identified by its accessible role + visible text.
            The hint_text is matched against the element's accessible name (button label, link
            text, etc.). When two elements share the same hint_text, this tool returns
            GROUNDING_AMBIGUOUS — Phase 3 T3.1 will add a near_label disambiguator field;
            until then, the LLM should refine its prompt.

            Returns a JSON object:
              { "ok": true, "elapsed_ms": 123 }
            or on grounding failure:
              { "ok": false, "code": "GROUNDING_AMBIGUOUS|GROUNDING_MISS|NO_SESSION", "message": "..." }
            """)
    public String extension_browser_click(
            @ToolParam(description = "Visible text / accessible name of the element to click (e.g. 'Submit', 'Cancel', 'Comments')")
            String hintText,
            @ToolParam(description = "Optional accessible role hint: 'button' (default) | 'link' | 'menuitem' | 'tab' | 'checkbox'",
                       required = false) String role,
            @Nullable ToolContext ctx) {
        BrowserSession session = resolveSession();
        if (session == null) return noSession();

        String resolvedRole = defaulted(role, "button");
        // Phase 3 T3.1 (Codex 21, parallel) will add A11yMatch.nearLabel as a 4th
        // ctor arg for nearest-ancestor disambiguation. Until that ships, this
        // tool only exposes the role+name pattern.
        GroundingHint hint = new GroundingHint.A11yMatch(
                resolvedRole,
                Pattern.compile(Pattern.quote(hintText), Pattern.CASE_INSENSITIVE),
                "interactive");

        GroundingResult ground = dispatcher.ground(session, new TabRef.Main(), hint);
        return switch (ground) {
            case GroundingResult.Hit hit -> executePlan(session,
                    planner.plan(new Step.ClickStep(new TabRef.Main(), ground)));
            case GroundingResult.Ambiguous a -> error("GROUNDING_AMBIGUOUS",
                    "found " + a.candidates().size() + " candidates: " + a.evidence());
            case GroundingResult.Miss m -> error("GROUNDING_MISS", m.reason());
        };
    }

    // -----------------------------------------------------------------
    // browser_type
    // -----------------------------------------------------------------

    @Tool(description = """
            Type text into the currently focused element. To type into a specific field,
            click_first that field via browser_click, then call this tool. The implementation
            does NOT auto-focus — that's deliberate, to avoid clobbering an existing focus
            the user established.

            Returns a JSON object on success:
              { "ok": true, "chars_typed": 5 }
            """)
    public String extension_browser_type(
            @ToolParam(description = "Text to type. Unicode supported; per-char keyDown + char + keyUp.")
            String text,
            @Nullable ToolContext ctx) {
        BrowserSession session = resolveSession();
        if (session == null) return noSession();

        ActionRequest req = new ActionRequest(
                newMsgId(),
                new TabRef.Main(),
                ActionKind.TYPE,
                new TypePayload(text, null),
                DEFAULT_DEADLINE_MS);

        return executePlan(session, List.of(req));
    }

    // -----------------------------------------------------------------
    // browser_scroll
    // -----------------------------------------------------------------

    @Tool(description = """
            Scroll the page. Direction is one of up/down/left/right. distance_px is the
            total scroll amount; the underlying CDP implementation segments it into N
            wheel events with log-normal inter-segment delays (mimics human wheel push).

            Returns: { "ok": true }
            """)
    public String extension_browser_scroll(
            @ToolParam(description = "Scroll direction: 'up' | 'down' | 'left' | 'right'") String direction,
            @ToolParam(description = "Total scroll distance in CSS pixels. Default 600.", required = false)
            Integer distancePx,
            @Nullable ToolContext ctx) {
        BrowserSession session = resolveSession();
        if (session == null) return noSession();

        ActionRequest req = new ActionRequest(
                newMsgId(),
                new TabRef.Main(),
                ActionKind.SCROLL,
                new ScrollPayload(direction, distancePx == null ? 600 : distancePx, 5),
                DEFAULT_DEADLINE_MS);

        return executePlan(session, List.of(req));
    }

    // -----------------------------------------------------------------
    // browser_wait
    // -----------------------------------------------------------------

    @Tool(description = """
            Pause the agent until a wait condition is satisfied. Use this before an action
            that depends on a page reaching a stable state — for example, wait_for=network_idle
            after a click that triggers a long XHR.

            Strategies:
              - time: sleep duration_ms (deterministic; default if duration_ms is set)
              - load_state: wait for chrome.webNavigation onCompleted matching load_state
                            ('load' | 'domcontentloaded' | 'network_idle')
              - network_idle: Phase-2 fallback — sleeps idle_threshold_ms (default 500ms)

            Returns: { "ok": true, "waited_ms": 500 }
            """)
    public String extension_browser_wait(
            @ToolParam(description = "Wait strategy: 'time' | 'load_state' | 'network_idle'") String strategy,
            @ToolParam(description = "Duration in milliseconds (for strategy=time)", required = false)
            Long durationMs,
            @ToolParam(description = "load_state target: 'load' | 'domcontentloaded' | 'network_idle' (for strategy=load_state)",
                       required = false) String loadState,
            @ToolParam(description = "Idle threshold in milliseconds (for strategy=network_idle, default 500)",
                       required = false) Long idleThresholdMs,
            @Nullable ToolContext ctx) {
        BrowserSession session = resolveSession();
        if (session == null) return noSession();

        ActionRequest req = new ActionRequest(
                newMsgId(),
                new TabRef.Main(),
                ActionKind.WAIT,
                new WaitPayload(strategy, durationMs, idleThresholdMs, loadState),
                DEFAULT_DEADLINE_MS);

        return executePlan(session, List.of(req));
    }

    // -----------------------------------------------------------------
    // browser_observe
    // -----------------------------------------------------------------

    @Tool(description = """
            Read the page's accessibility tree as plain text. Use this between actions
            to let the LLM see what's on the page before deciding the next click/type
            target. The returned tree is the same input the A11y engine uses for
            grounding, so referring to "the Submit button near the Comments heading"
            in your next browser_click matches what the page actually exposes.

            The tree format per line is:
              Role[ref=ref_N]: accessible name @{x,y wxh}

            Returns a JSON object:
              { "ok": true, "snapshot_id": "...", "viewport": {"w": 1280, "h": 800},
                "tree": "Button[ref=ref_1]: Submit @{100,200 80x32}\\n..." }
            """)
    public String extension_browser_observe(
            @ToolParam(description = "Snapshot filter: 'interactive' (default — buttons/links/inputs) | 'all' | 'default'",
                       required = false) String filter,
            @Nullable ToolContext ctx) {
        BrowserSession session = resolveSession();
        if (session == null) return noSession();

        String resolvedFilter = defaulted(filter, "interactive");
        try {
            var snapshot = snapshotService
                    .request(session, new TabRef.Main(), resolvedFilter)
                    .block(java.time.Duration.ofSeconds(15));
            if (snapshot == null) {
                return error("SNAPSHOT_FAILED", "PageSnapshotService returned null");
            }
            return json(Map.of(
                    "ok", true,
                    "snapshot_id", snapshot.snapshotId(),
                    "viewport", Map.of("w", snapshot.viewport().w(), "h", snapshot.viewport().h()),
                    "tree", snapshot.tree()));
        } catch (Exception e) {
            return error("SNAPSHOT_FAILED", e.getMessage());
        }
    }

    // -----------------------------------------------------------------
    // Internals
    // -----------------------------------------------------------------

    /** Resolve the active extension session for this tenant. Phase 4 will accept the
     *  subject from ToolContext; Phase 3 uses a single configured subject. */
    private BrowserSession resolveSession() {
        Optional<BrowserSession> s = registry.findBySubject(defaultSubject);
        return s.orElse(null);
    }

    /** Execute the given list of action requests sequentially via PlanExecutionService.
     *  Blocks on the resulting Mono for tool semantics (LLM expects a return value). */
    private String executePlan(BrowserSession session, List<ActionRequest> plan) {
        try {
            PlanResult result = planExec.execute(session, plan).block(java.time.Duration.ofSeconds(60));
            if (result == null) {
                return error("EXECUTION_FAILED", "PlanExecutionService returned null");
            }
            return switch (result) {
                case PlanResult.Success ok -> json(Map.of(
                        "ok", true,
                        "elapsed_ms", ok.completed().stream()
                                .mapToLong(s -> s.elapsedMs()).sum(),
                        "steps", ok.completed().size()));
                case PlanResult.Partial p -> json(Map.of(
                        "ok", false,
                        "code", p.failed().code(),
                        "message", p.failed().message(),
                        "completed_before_failure", p.completed().size()));
            };
        } catch (Exception e) {
            return error("EXECUTION_FAILED", e.getMessage());
        }
    }

    private String noSession() {
        return error("NO_SESSION",
                "no extension session registered for subject '" + defaultSubject
                        + "'. Has the user opened the MateClaw sidepanel and clicked Ping?");
    }

    private String error(String code, String message) {
        return json(Map.of("ok", false, "code", code, "message", message == null ? "" : message));
    }

    private String json(Map<String, Object> obj) {
        try {
            return mapper.writeValueAsString(obj);
        } catch (JsonProcessingException e) {
            // Should be impossible for Map<String, Object> with primitive values
            log.warn("[ExtensionBrowserTool] serialise failed: {}", e.getMessage());
            return "{\"ok\":false,\"code\":\"SERIALISE_FAILED\",\"message\":\"" + e.getMessage() + "\"}";
        }
    }

    private static String newMsgId() {
        return UUID.randomUUID().toString();
    }

    private static String defaulted(String value, String fallback) {
        return (value == null || value.isBlank()) ? fallback : value;
    }
}
