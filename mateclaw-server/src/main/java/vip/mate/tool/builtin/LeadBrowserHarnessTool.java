package vip.mate.tool.builtin;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;
import vip.mate.browser.edge.action.TypePayload;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Task-level browser harnesses for lead-acquisition agents.
 *
 * <p>The generic extension browser tools stay as small primitives. This class
 * owns lead-generation workflows where repeated observe/click/type loops are
 * easy for the model to overdo and where a crisp completion state matters more
 * than exposing every low-level action to the LLM.
 */
@Slf4j
@Component
public class LeadBrowserHarnessTool {

    private static final String DOUYIN_HOME = "https://www.douyin.com/";
    private static final int MAX_REPEAT_FINGERPRINTS = 2;
    private static final int MAX_SESSION_DETACH_RETRIES = 2;
    private static final long SESSION_DETACH_RETRY_DELAY_MS = 700L;
    private static final long SEARCH_ENTRY_DELAY_MS = 650L;
    private static final long PAGE_SETTLE_DELAY_MS = 1_200L;
    private static final long FILTER_PANEL_SETTLE_DELAY_MS = 600L;
    private static final int MIN_SEARCH_CANDIDATE_SCORE = 70;
    private static final List<String> DOUYIN_LIKE_SORT_LABELS = List.of("最多点赞", "点赞最多", "按点赞", "点赞量");
    private static final Pattern TREE_LINE_PATTERN = Pattern.compile(
            "^\\s*([A-Za-z][\\w-]*)\\s*\\[ref=[^\\]]+\\]\\s*(?::\\s*(.*?))?\\s*"
                    + "(?:@\\{(-?\\d+),(-?\\d+)\\s+(\\d+)x(\\d+)\\})?\\s*$");

    private final ExtensionBrowserTool browser;
    private final ObjectMapper mapper;

    public LeadBrowserHarnessTool(ExtensionBrowserTool browser, ObjectMapper mapper) {
        this.browser = browser;
        this.mapper = mapper;
    }

    @Tool(description = """
            Open Douyin in the USER'S OWN visible Chrome window and search a keyword via the
            real visible search box, then return.

            ⚠️ ONE-SHOT ONLY. Use this ONLY when the ENTIRE task is "open Douyin and search
            <keyword>" with NO follow-up steps. If the task continues after the search —
            filter, sort by likes, open a result, read comments, extract leads — do NOT use
            this; drive the page yourself with the composable extension_browser_* primitives
            (navigate → observe → click → type → observe → repeat) so you can keep going and
            see each intermediate page. This harness runs the whole search internally; you
            cannot observe or steer its intermediate steps, so it is a dead end for multi-step
            work.

            Starts from https://www.douyin.com/, clicks the visible search box, types the
            keyword, submits, verifies the search page, then stops. Returns a LOGIN_REQUIRED
            status when Douyin shows a login wall (the user must log in in this browser first).
            """)
    public String lead_browser_douyin_search(
            @ToolParam(description = "Search keyword, e.g. openclaw") String query,
            @Nullable ToolContext ctx) {
        String normalizedQuery = normalizeQuery(query);
        if (normalizedQuery.isBlank()) {
            return "未完成：搜索关键词不能为空。\n状态：INVALID_QUERY";
        }

        SearchRun run = runDouyinSearch(normalizedQuery, ctx);
        if (run.done()) {
            return done(normalizedQuery, run.snapshot(), run.via(), run.attempts());
        }
        return failureText(run);
    }

    @Tool(description = """
            Lead-acquisition browser harness: in the USER'S OWN visible Chrome window,
            open Douyin, search a keyword, open the filter/sort panel, and select the
            "最多点赞" (most liked) sort option.

            Use this as the FIRST choice for requests like:
              "用我的浏览器打开抖音，搜索 openclaw，点击筛选，按最多点赞排序"

            This is intentionally task-level and bounded: it avoids repeated low-level
            observe/click/type loops, uses Douyin's visible homepage search box, uses hover
            for the 筛选 menu because Douyin's sort panel is hover-triggered, verifies that
            the most-liked option was observed before clicking it, and stops with a clear
            status if the page shows a login/verification wall.

            Returns JSON:
              { "ok": true, "status": "DONE", "query": "...", "sort": "最多点赞", "url": "...", "attempts": [...] }
            or:
              { "ok": false, "status": "LOGIN_REQUIRED|FILTER_TRIGGER_NOT_FOUND|SORT_OPTION_NOT_FOUND|...", ... }
            """)
    public String lead_browser_douyin_search_sort_most_liked(
            @ToolParam(description = "Search keyword, e.g. openclaw") String query,
            @Nullable ToolContext ctx) {
        String normalizedQuery = normalizeQuery(query);
        if (normalizedQuery.isBlank()) {
            return json(Map.of(
                    "ok", false,
                    "status", "INVALID_QUERY",
                    "message", "query is required"));
        }

        SearchRun search = runDouyinSearch(normalizedQuery, ctx);
        if (!search.done()) {
            return json(Map.of(
                    "ok", false,
                    "status", search.status(),
                    "message", search.message(),
                    "query", normalizedQuery,
                    "url", search.snapshot().url(),
                    "title", search.snapshot().title(),
                    "attempts", search.attempts()));
        }

        SortRun sort = runDouyinMostLikedSort(normalizedQuery, search.snapshot(), search.attempts(), ctx);
        if (sort.done()) {
            return json(Map.of(
                    "ok", true,
                    "status", "DONE",
                    "query", normalizedQuery,
                    "sort", sort.sortLabel(),
                    "url", sort.snapshot().url(),
                    "title", sort.snapshot().title(),
                    "attempts", sort.attempts()));
        }
        return json(Map.of(
                "ok", false,
                "status", sort.status(),
                "message", sort.message(),
                "query", normalizedQuery,
                "url", sort.snapshot().url(),
                "title", sort.snapshot().title(),
                "attempts", sort.attempts()));
    }

    @Tool(description = """
            Generic lead-acquisition browser harness: open a site's homepage in the USER'S OWN
            visible Chrome window, search a keyword through the visible on-page search box,
            open a filter/sort/menu trigger, and select a target option.

            Use this for sites that share the common pattern:
              home page → search box → results page → 筛选/排序/更多 trigger → option text

            Examples:
              home_url="https://www.xiaohongshu.com", query="openclaw",
              trigger_text="筛选", option_text="最多点赞"
              home_url="https://www.douyin.com", query="openclaw",
              trigger_text="筛选", option_text="最多点赞"

            This tool is intentionally bounded and reusable: it uses visible controls, tries
            hover first (for hover menus), falls back to click only after observing that the
            option is not visible, then finally falls back to visible-text grounding so the
            screenshot/vision engine can locate custom menu rows. It does NOT follow users or
            send private messages.

            Returns JSON with ok/status/query/selected_option/url/attempts.
            """)
    public String lead_browser_site_search_select_option(
            @ToolParam(description = "Site homepage URL, including or omitting https://, e.g. https://www.xiaohongshu.com")
            String homeUrl,
            @ToolParam(description = "Search keyword, e.g. openclaw")
            String query,
            @ToolParam(description = "Visible text of the filter/sort/menu trigger, e.g. 筛选, 排序, 更多")
            String triggerText,
            @ToolParam(description = "Visible text of the option to select, e.g. 最多点赞, 最新发布, 一周内")
            String optionText,
            @ToolParam(description = "Human-readable site name for status messages, e.g. 小红书. Optional.",
                    required = false)
            String siteName,
            @Nullable ToolContext ctx) {
        String normalizedUrl = normalizeHomeUrl(homeUrl);
        String normalizedQuery = normalizeQuery(query);
        String normalizedTrigger = normalizeQuery(triggerText);
        String normalizedOption = normalizeQuery(optionText);
        String normalizedSite = normalizeQuery(siteName);
        if (normalizedSite.isBlank()) {
            normalizedSite = hostLabel(normalizedUrl);
        }
        if (normalizedUrl.isBlank()) {
            return json(Map.of("ok", false, "status", "INVALID_HOME_URL", "message", "home_url is required"));
        }
        if (normalizedQuery.isBlank()) {
            return json(Map.of("ok", false, "status", "INVALID_QUERY", "message", "query is required"));
        }
        if (normalizedTrigger.isBlank()) {
            return json(Map.of("ok", false, "status", "INVALID_TRIGGER", "message", "trigger_text is required"));
        }
        if (normalizedOption.isBlank()) {
            return json(Map.of("ok", false, "status", "INVALID_OPTION", "message", "option_text is required"));
        }

        SearchRun search = runSiteSearch(normalizedSite, normalizedUrl, normalizedQuery, ctx);
        if (!search.done()) {
            return json(Map.of(
                    "ok", false,
                    "status", search.status(),
                    "message", search.message(),
                    "site", normalizedSite,
                    "query", normalizedQuery,
                    "url", search.snapshot().url(),
                    "title", search.snapshot().title(),
                    "attempts", search.attempts()));
        }

        SortRun selection = runSelectFilterOption(
                normalizedSite,
                List.of(normalizedTrigger),
                List.of(normalizedOption),
                search.snapshot(),
                search.attempts(),
                ctx);
        if (selection.done()) {
            return json(Map.of(
                    "ok", true,
                    "status", "DONE",
                    "site", normalizedSite,
                    "query", normalizedQuery,
                    "selected_option", selection.sortLabel(),
                    "url", selection.snapshot().url(),
                    "title", selection.snapshot().title(),
                    "attempts", selection.attempts()));
        }
        return json(Map.of(
                "ok", false,
                "status", selection.status(),
                "message", selection.message(),
                "site", normalizedSite,
                "query", normalizedQuery,
                "url", selection.snapshot().url(),
                "title", selection.snapshot().title(),
                "attempts", selection.attempts()));
    }

    @Tool(description = """
            Lead-acquisition browser harness: search Douyin through the visible homepage search box,
            then read the resulting page once and return compact candidate lines for lead extraction.

            Use this for requests that ask to search Douyin and collect/extract/summarize potential
            leads in one turn. For a simple "just open Douyin and search" task, use
            lead_browser_douyin_search instead.
            """)
    public String lead_browser_douyin_search_for_leads(
            @ToolParam(description = "Search keyword, e.g. openclaw") String query,
            @ToolParam(description = "Lead goal or signal to extract, e.g. AI tool companies, local clinics")
            String goal,
            @ToolParam(description = "Maximum candidate lines to return. Default 30, max 80.", required = false)
            Integer maxLines,
            @Nullable ToolContext ctx) {
        String normalizedQuery = normalizeQuery(query);
        if (normalizedQuery.isBlank()) {
            return json(Map.of(
                    "ok", false,
                    "status", "INVALID_QUERY",
                    "message", "query is required"));
        }

        SearchRun run = runDouyinSearch(normalizedQuery, ctx);
        if (!run.done()) {
            return json(Map.of(
                    "ok", false,
                    "status", run.status(),
                    "message", run.message(),
                    "url", run.snapshot().url(),
                    "attempts", run.attempts()));
        }

        ObserveResult observed = observe("observe_search_page_for_leads", run.attempts(), ctx);
        Snapshot snap = observed.ok() ? observed.snapshot() : run.snapshot();
        int limit = clamp(maxLines == null ? 30 : maxLines, 1, 80);
        return json(Map.of(
                "ok", true,
                "status", "DONE",
                "query", normalizedQuery,
                "goal", normalizeQuery(goal),
                "url", snap.url(),
                "title", snap.title(),
                "candidate_lines", candidateLines(snap.tree(), limit),
                "raw_excerpt", excerpt(snap.tree(), 4_000),
                "attempts", run.attempts()));
    }

    @Tool(description = """
            Lead-acquisition browser harness: read the current visible page once and return a
            compact JSON snapshot for lead extraction. Use after a lead-search action when the
            agent needs to inspect visible titles, account names, links, or card text without
            falling into repeated observe/scroll loops.

            Returns JSON with url, title, candidate_lines, and a short raw excerpt.
            """)
    public String lead_browser_snapshot_for_leads(
            @ToolParam(description = "What kind of leads or signals to look for, e.g. AI tools, manufacturers, local clinics")
            String goal,
            @ToolParam(description = "Maximum candidate lines to return. Default 30, max 80.", required = false)
            Integer maxLines,
            @Nullable ToolContext ctx) {
        List<Map<String, Object>> attempts = new ArrayList<>();
        ObserveResult observed = observe("observe_current_page", attempts, ctx);
        if (!observed.ok()) {
            return json(Map.of(
                    "ok", false,
                    "status", observed.raw().path("code").asText("SNAPSHOT_FAILED"),
                    "message", observed.raw().path("message").asText("Unable to read current page"),
                    "attempts", attempts));
        }

        int limit = clamp(maxLines == null ? 30 : maxLines, 1, 80);
        Snapshot snap = observed.snapshot();
        List<String> candidates = candidateLines(snap.tree(), limit);
        return json(Map.of(
                "ok", true,
                "status", "DONE",
                "goal", normalizeQuery(goal),
                "url", snap.url(),
                "title", snap.title(),
                "candidate_lines", candidates,
                "raw_excerpt", excerpt(snap.tree(), 4_000),
                "attempts", attempts));
    }

    private SearchRun runDouyinSearch(String normalizedQuery, ToolContext ctx) {
        List<Map<String, Object>> attempts = new ArrayList<>();
        FingerprintGuard guard = new FingerprintGuard();

        JsonNode nav = callBrowser("navigate_douyin_home", attempts,
                () -> browser.extension_browser_navigate(DOUYIN_HOME, "load", ctx));
        if (!ok(nav)) {
            return failRun("navigate_douyin_home", nav, attempts);
        }

        ObserveResult observed = observe("observe_douyin_home", attempts, ctx);
        if (!observed.ok()) {
            return failRun("observe_douyin_home", observed.raw(), attempts);
        }
        if (isDouyinSearchDone(normalizedQuery, observed.snapshot())) {
            return doneRun(observed.snapshot(), "already_on_search", attempts);
        }
        guard.repeated(observed.snapshot());

        JsonNode click = clickSearchEntry(observed.snapshot(), attempts, ctx);
        if (!ok(click)) {
            return failRun("click_search_box", click, attempts);
        }

        localWait("wait_after_search_entry_click", attempts, SEARCH_ENTRY_DELAY_MS);

        observed = observe("observe_after_search_entry_click", attempts, ctx);
        if (!observed.ok()) {
            return failRun("observe_after_search_entry_click", observed.raw(), attempts);
        }
        if (isDouyinSearchDone(normalizedQuery, observed.snapshot())) {
            return doneRun(observed.snapshot(), "search_entry_click", attempts);
        }

        // Douyin often turns the header search affordance into a real input only
        // after the first click. Prefer typing at that input's coordinates so
        // the keystrokes land even if a panel steals focus during animation.
        TypePayload.FocusTarget inputFocus = focusTargetForSearchInput(observed.snapshot());
        if (inputFocus == null) {
            clickSearchInput(observed.snapshot(), attempts, ctx);
        }

        JsonNode type = callBrowser("type_query_and_enter", attempts,
                () -> typeQuery(normalizedQuery, inputFocus, ctx));
        if (!ok(type)) {
            return failRun("type_query_and_enter", type, attempts);
        }

        localWait("wait_after_enter", attempts, PAGE_SETTLE_DELAY_MS);

        observed = observe("verify_after_enter", attempts, ctx);
        if (!observed.ok()) {
            return failRun("verify_after_enter", observed.raw(), attempts);
        }
        if (isDouyinSearchDone(normalizedQuery, observed.snapshot())) {
            return doneRun(observed.snapshot(), "homepage_search_box", attempts);
        }
        // Douyin gates search behind a login modal: after Enter the page shows
        // 登录/扫码登录/验证码登录 and never reaches a /search results page. Surface
        // that as a clear, actionable LOGIN_REQUIRED instead of grinding through
        // retries to a confusing BLOCKED_LOOP.
        if (isLoginWall(observed.snapshot())) {
            return loginRequiredRun(observed.snapshot(), attempts);
        }

        if (!snapshotContainsQuery(normalizedQuery, observed.snapshot())) {
            TypePayload.FocusTarget retryInputFocus = focusTargetForSearchInput(observed.snapshot());
            JsonNode retryFocus = retryInputFocus == null
                    ? clickSearchInput(observed.snapshot(), attempts, ctx)
                    : mapper.createObjectNode()
                    .put("ok", true)
                    .put("status", "FOCUS_TARGET_FROM_SNAPSHOT")
                    .put("message", "using input center from snapshot");
            if (retryInputFocus != null) {
                attempts.add(attempt("retry_focus_search_input_from_snapshot", retryFocus));
            }
            if (ok(retryFocus)) {
                JsonNode retryType = callBrowser("retry_type_query_and_enter_after_refocus", attempts,
                        () -> typeQuery(normalizedQuery, retryInputFocus, ctx));
                if (!ok(retryType)) {
                    return failRun("retry_type_query_and_enter_after_refocus", retryType, attempts);
                }

                localWait("wait_after_retry_enter", attempts, PAGE_SETTLE_DELAY_MS);

                observed = observe("verify_after_retry_enter", attempts, ctx);
                if (!observed.ok()) {
                    return failRun("verify_after_retry_enter", observed.raw(), attempts);
                }
                if (isDouyinSearchDone(normalizedQuery, observed.snapshot())) {
                    return doneRun(observed.snapshot(), "refocused_search_input", attempts);
                }
            }
        }

        if (guard.repeated(observed.snapshot())) {
            return loginOrBlocked(observed.snapshot(), attempts);
        }

        JsonNode submitClick = callBrowser("fallback_click_search_button", attempts,
                () -> browser.extension_browser_click("搜索", "button", null, ctx));
        if (!ok(submitClick)) {
            return failRun("fallback_click_search_button", submitClick, attempts);
        }

        localWait("wait_after_button_submit", attempts, PAGE_SETTLE_DELAY_MS);

        observed = observe("verify_after_button_submit", attempts, ctx);
        if (!observed.ok()) {
            return failRun("verify_after_button_submit", observed.raw(), attempts);
        }
        if (isDouyinSearchDone(normalizedQuery, observed.snapshot())) {
            return doneRun(observed.snapshot(), "homepage_search_button", attempts);
        }
        if (guard.repeated(observed.snapshot())) {
            return loginOrBlocked(observed.snapshot(), attempts);
        }

        if (!snapshotContainsQuery(normalizedQuery, observed.snapshot())) {
            return new SearchRun(false, "FOCUS_NOT_TYPED",
                    "已定位到抖音搜索入口，但没有观察到关键词进入真实输入框，已停止，避免继续空打字。",
                    observed.snapshot(), "", attempts);
        }

        return new SearchRun(false, "FAILED",
                "已从抖音首页输入并提交关键词，但没有观察到对应搜索页，已停止继续重试。",
                observed.snapshot(), "", attempts);
    }

    private SearchRun runSiteSearch(String siteName, String homeUrl, String normalizedQuery, ToolContext ctx) {
        List<Map<String, Object>> attempts = new ArrayList<>();
        FingerprintGuard guard = new FingerprintGuard();

        JsonNode nav = callBrowser("navigate_site_home", attempts,
                () -> browser.extension_browser_navigate(homeUrl, "load", ctx));
        if (!ok(nav)) {
            return failRun("navigate_site_home", nav, attempts);
        }

        ObserveResult observed = observe("observe_site_home", attempts, ctx);
        if (!observed.ok()) {
            return failRun("observe_site_home", observed.raw(), attempts);
        }
        if (isVisibleSiteSearchDone(normalizedQuery, observed.snapshot())) {
            return doneRun(observed.snapshot(), "already_on_search", attempts);
        }
        guard.repeated(observed.snapshot());

        JsonNode click = clickSearchEntry(observed.snapshot(), attempts, ctx);
        if (!ok(click)) {
            return new SearchRun(false, click.path("code").asText(click.path("status").asText("SEARCH_TARGET_NOT_FOUND")),
                    siteName + "：没有定位到可信的搜索入口。", observed.snapshot(), "", attempts);
        }

        localWait("wait_after_site_search_entry_click", attempts, SEARCH_ENTRY_DELAY_MS);

        observed = observe("observe_after_site_search_entry_click", attempts, ctx);
        if (!observed.ok()) {
            return failRun("observe_after_site_search_entry_click", observed.raw(), attempts);
        }

        TypePayload.FocusTarget inputFocus = focusTargetForSearchInput(observed.snapshot());
        if (inputFocus == null) {
            clickSearchInput(observed.snapshot(), attempts, ctx);
        }

        JsonNode type = callBrowser("type_site_query_and_enter", attempts,
                () -> typeQuery(normalizedQuery, inputFocus, ctx));
        if (!ok(type)) {
            return failRun("type_site_query_and_enter", type, attempts);
        }

        localWait("wait_after_site_enter", attempts, PAGE_SETTLE_DELAY_MS);

        observed = observe("verify_after_site_enter", attempts, ctx);
        if (!observed.ok()) {
            return failRun("verify_after_site_enter", observed.raw(), attempts);
        }
        if (isVisibleSiteSearchDone(normalizedQuery, observed.snapshot())) {
            return doneRun(observed.snapshot(), "site_search_box", attempts);
        }
        if (isLoginWall(observed.snapshot())) {
            return new SearchRun(false, "LOGIN_REQUIRED",
                    siteName + " 的搜索需要登录。请先在这个浏览器里登录账号，然后重新发起任务。",
                    observed.snapshot(), "", attempts);
        }

        if (guard.repeated(observed.snapshot())) {
            return new SearchRun(false, "BLOCKED_LOOP",
                    siteName + " 连续返回同一个页面状态，已停止，避免重复执行同一组搜索动作。",
                    observed.snapshot(), "", attempts);
        }

        JsonNode submitClick = callBrowser("fallback_click_site_search_button", attempts,
                () -> browser.extension_browser_click("搜索", "button", null, ctx));
        if (!ok(submitClick)) {
            return failRun("fallback_click_site_search_button", submitClick, attempts);
        }

        localWait("wait_after_site_button_submit", attempts, PAGE_SETTLE_DELAY_MS);

        observed = observe("verify_after_site_button_submit", attempts, ctx);
        if (!observed.ok()) {
            return failRun("verify_after_site_button_submit", observed.raw(), attempts);
        }
        if (isVisibleSiteSearchDone(normalizedQuery, observed.snapshot())) {
            return doneRun(observed.snapshot(), "site_search_button", attempts);
        }
        if (isLoginWall(observed.snapshot())) {
            return new SearchRun(false, "LOGIN_REQUIRED",
                    siteName + " 的搜索需要登录。请先在这个浏览器里登录账号，然后重新发起任务。",
                    observed.snapshot(), "", attempts);
        }
        return new SearchRun(false, "SEARCH_NOT_VERIFIED",
                siteName + "：已提交搜索，但没有观察到搜索结果状态，已停止继续重试。",
                observed.snapshot(), "", attempts);
    }

    private SortRun runDouyinMostLikedSort(String normalizedQuery,
                                           Snapshot searchSnapshot,
                                           List<Map<String, Object>> previousAttempts,
                                           ToolContext ctx) {
        return runSelectFilterOption("抖音", List.of("筛选"), DOUYIN_LIKE_SORT_LABELS,
                searchSnapshot, previousAttempts, ctx);
    }

    private SortRun runSelectFilterOption(String siteName,
                                          List<String> triggerLabels,
                                          List<String> optionLabels,
                                          Snapshot searchSnapshot,
                                          List<Map<String, Object>> previousAttempts,
                                          ToolContext ctx) {
        List<Map<String, Object>> attempts = new ArrayList<>(previousAttempts);
        Snapshot current = searchSnapshot;
        if (isLoginWall(current)) {
            return new SortRun(false, "LOGIN_REQUIRED",
                    siteName + " 的搜索结果需要登录。请先在这个浏览器里登录账号，然后重新发起任务。",
                    current, "", attempts);
        }

        SortSelection alreadySelected = selectedOption(current, optionLabels);
        if (alreadySelected != null) {
            return new SortRun(true, "DONE", "", current, alreadySelected.label(), attempts);
        }

        TreeLine filter = bestFilterTrigger(current.tree(), triggerLabels, optionLabels);
        if (filter == null) {
            ObserveResult refreshed = observe("observe_search_page_before_filter", attempts, ctx);
            if (!refreshed.ok()) {
                return sortFail("observe_search_page_before_filter", refreshed.raw(), attempts);
            }
            current = refreshed.snapshot();
            if (isLoginWall(current)) {
                return new SortRun(false, "LOGIN_REQUIRED",
                        siteName + " 的搜索结果需要登录。请先在这个浏览器里登录账号，然后重新发起任务。",
                        current, "", attempts);
            }
            alreadySelected = selectedOption(current, optionLabels);
            if (alreadySelected != null) {
                return new SortRun(true, "DONE", "", current, alreadySelected.label(), attempts);
            }
            filter = bestFilterTrigger(current.tree(), triggerLabels, optionLabels);
        }
        if (filter == null) {
            return new SortRun(false, "FILTER_TRIGGER_NOT_FOUND",
                    "已完成 " + siteName + " 搜索，但没有在结果页观察到「"
                            + String.join("/", triggerLabels) + "」触发按钮，已停止，避免重复点击。",
                    current, "", attempts);
        }

        JsonNode hover = hoverAtLine("hover_filter_trigger", filter, attempts, ctx);
        if (!ok(hover)) {
            return sortFail("hover_filter_trigger", hover, attempts);
        }

        localWait("wait_after_filter_hover", attempts, FILTER_PANEL_SETTLE_DELAY_MS);

        ObserveResult panel = observe("observe_filter_panel", attempts, ctx);
        if (!panel.ok()) {
            return sortFail("observe_filter_panel", panel.raw(), attempts);
        }
        current = panel.snapshot();
        SortSelection option = bestOption(current, optionLabels);
        if (option == null) {
            // Some layouts still open the panel on click. Try that only after
            // a fresh observe confirms the option is not visible after hover.
            JsonNode click = clickAtLine("fallback_click_filter_trigger", filter, attempts, ctx);
            if (!ok(click)) {
                return sortFail("fallback_click_filter_trigger", click, attempts);
            }
            localWait("wait_after_filter_click", attempts, FILTER_PANEL_SETTLE_DELAY_MS);

            panel = observe("observe_filter_panel_after_click", attempts, ctx);
            if (!panel.ok()) {
                return sortFail("observe_filter_panel_after_click", panel.raw(), attempts);
            }
            current = panel.snapshot();
            option = bestOption(current, optionLabels);
        }

        if (option == null) {
            String targetText = optionLabels.getFirst();
            JsonNode textClick = callBrowser("fallback_click_option_by_visible_text", attempts,
                    () -> browser.extension_browser_click(targetText, "button", null, ctx));
            if (!ok(textClick)) {
                return new SortRun(false, "SORT_OPTION_NOT_FOUND",
                        "已打开/尝试打开 " + siteName + " 筛选控件，但没有观察到「"
                                + targetText + "」选项，"
                                + "按可见文字兜底点击也失败：" + textClick.path("message").asText("unknown"),
                        current, "", attempts);
            }
            localWait("wait_after_text_sort_select", attempts, PAGE_SETTLE_DELAY_MS);

            ObserveResult afterTextClick = observe("verify_text_most_liked_sort", attempts, ctx);
            if (!afterTextClick.ok()) {
                return sortFail("verify_text_most_liked_sort", afterTextClick.raw(), attempts);
            }
            current = afterTextClick.snapshot();
            if (isLoginWall(current)) {
                return new SortRun(false, "LOGIN_REQUIRED",
                        siteName + " 的筛选排序需要登录。请先在这个浏览器里登录账号，然后重新发起任务。",
                        current, "", attempts);
            }
            SortSelection selected = selectedOption(current, optionLabels);
            return new SortRun(true, "DONE", "", current,
                    selected == null ? targetText : selected.label(), attempts);
        }

        JsonNode select = clickAtLine("click_filter_option", option.line(), attempts, ctx);
        if (!ok(select)) {
            return sortFail("click_filter_option", select, attempts);
        }
        localWait("wait_after_sort_select", attempts, PAGE_SETTLE_DELAY_MS);

        ObserveResult afterSelect = observe("verify_most_liked_sort", attempts, ctx);
        if (!afterSelect.ok()) {
            return sortFail("verify_most_liked_sort", afterSelect.raw(), attempts);
        }
        current = afterSelect.snapshot();
        if (isLoginWall(current)) {
            return new SortRun(false, "LOGIN_REQUIRED",
                    siteName + " 的筛选排序需要登录。请先在这个浏览器里登录账号，然后重新发起任务。",
                    current, "", attempts);
        }

        SortSelection selected = selectedOption(current, optionLabels);
        String selectedLabel = selected == null ? option.label() : selected.label();
        return new SortRun(true, "DONE", "", current, selectedLabel, attempts);
    }

    private JsonNode clickSearchEntry(Snapshot snap, List<Map<String, Object>> attempts, ToolContext ctx) {
        SearchCandidate best = bestSearchCandidate(snap.tree(), searchEntryRoles());
        if (best != null) {
            return clickAtLine("click_ranked_search_entry", best.line(), attempts, ctx);
        }
        if (!parseTreeLines(snap.tree()).isEmpty()) {
            return lastFailedAttempt(attempts, "click_search_entry_not_found",
                    "SEARCH_TARGET_NOT_FOUND", "没有找到可信的抖音顶部搜索框候选。");
        }
        return clickSearchTarget(snap, attempts, ctx, searchEntryRoles(), "click_search_entry",
                "SEARCH_TARGET_NOT_FOUND", "没有定位到抖音搜索框或搜索按钮。");
    }

    private JsonNode clickSearchInput(Snapshot snap, List<Map<String, Object>> attempts, ToolContext ctx) {
        SearchCandidate best = bestSearchCandidate(snap.tree(), searchInputRoles());
        if (best != null) {
            return clickAtLine("click_ranked_search_input", best.line(), attempts, ctx);
        }
        if (!parseTreeLines(snap.tree()).isEmpty()) {
            return lastFailedAttempt(attempts, "click_search_input_not_found",
                    "SEARCH_INPUT_NOT_FOUND", "没有找到可信的抖音真实搜索输入框候选。");
        }
        return clickSearchTarget(snap, attempts, ctx, searchInputRoles(), "click_search_input",
                "SEARCH_INPUT_NOT_FOUND", "没有定位到抖音真实搜索输入框。");
    }

    private JsonNode clickAtLine(String step,
                                 TreeLine line,
                                 List<Map<String, Object>> attempts,
                                 ToolContext ctx) {
        double x = line.x() + line.w() / 2.0;
        double y = line.y() + line.h() / 2.0;
        return callBrowser(step, attempts, () -> browser.extension_browser_click_at(x, y, ctx));
    }

    private JsonNode hoverAtLine(String step,
                                 TreeLine line,
                                 List<Map<String, Object>> attempts,
                                 ToolContext ctx) {
        double x = line.x() + line.w() / 2.0;
        double y = line.y() + line.h() / 2.0;
        return callBrowser(step, attempts, () -> browser.extension_browser_hover_at(x, y, ctx));
    }

    private JsonNode clickSearchTarget(Snapshot snap,
                                       List<Map<String, Object>> attempts,
                                       ToolContext ctx,
                                       List<String> roles,
                                       String stepPrefix,
                                       String failureCode,
                                       String failureMessage) {
        String target = chooseSearchTarget(snap, roles);
        for (String role : roles) {
            JsonNode click = callBrowser(stepPrefix + "_" + role, attempts,
                    () -> browser.extension_browser_click(target, role, null, ctx));
            if (ok(click)) {
                return click;
            }
        }
        return lastFailedAttempt(attempts, stepPrefix + "_not_found", failureCode, failureMessage);
    }

    private String typeQuery(String normalizedQuery,
                             @Nullable TypePayload.FocusTarget focusTarget,
                             ToolContext ctx) {
        if (focusTarget == null) {
            return browser.extension_browser_type(normalizedQuery + "\n", ctx);
        }
        return browser.extension_browser_type_at(normalizedQuery + "\n", focusTarget, ctx);
    }

    private ObserveResult observe(String step, List<Map<String, Object>> attempts, ToolContext ctx) {
        JsonNode raw = callBrowser(step, attempts, () -> browser.extension_browser_observe("all", ctx));
        if (!ok(raw)) {
            return new ObserveResult(false, raw, Snapshot.empty());
        }
        return new ObserveResult(true, raw, snapshot(raw));
    }

    private JsonNode callBrowser(String step, List<Map<String, Object>> attempts, Supplier<String> invocation) {
        JsonNode parsed = call(step, attempts, invocation.get());
        int retries = 0;
        while (isSessionDetached(parsed) && retries < MAX_SESSION_DETACH_RETRIES) {
            retries += 1;
            sleepBeforeRetry(retries);
            parsed = call(step + "_retry_after_detach_" + retries, attempts, invocation.get());
        }
        return parsed;
    }

    private JsonNode call(String step, List<Map<String, Object>> attempts, String raw) {
        JsonNode parsed = parse(raw);
        attempts.add(attempt(step, parsed));
        return parsed;
    }

    private JsonNode localWait(String step, List<Map<String, Object>> attempts, long delayMs) {
        sleep(delayMs);
        JsonNode parsed = mapper.createObjectNode()
                .put("ok", true)
                .put("status", "LOCAL_WAIT")
                .put("message", "waited " + delayMs + "ms inside lead harness");
        attempts.add(attempt(step, parsed));
        return parsed;
    }

    private JsonNode lastFailedAttempt(List<Map<String, Object>> attempts,
                                       String step,
                                       String code,
                                       String message) {
        JsonNode parsed = mapper.createObjectNode()
                .put("ok", false)
                .put("code", code)
                .put("message", message);
        attempts.add(attempt(step, parsed));
        return parsed;
    }

    private Map<String, Object> attempt(String step, JsonNode parsed) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("step", step);
        entry.put("ok", parsed.path("ok").asBoolean(false));
        putIfText(entry, "code", parsed.path("code").asText(null));
        putIfText(entry, "status", parsed.path("status").asText(null));
        putIfText(entry, "url", parsed.path("url").asText(null));
        putIfText(entry, "title", parsed.path("title").asText(null));
        putIfText(entry, "message", parsed.path("message").asText(null));
        if (parsed.has("results")) {
            entry.put("results", mapper.convertValue(parsed.get("results"), List.class));
        }
        return entry;
    }

    private void putIfText(Map<String, Object> entry, String key, String value) {
        if (value != null && !value.isBlank()) {
            entry.put(key, value);
        }
    }

    private Snapshot snapshot(JsonNode node) {
        return new Snapshot(
                node.path("url").asText(""),
                node.path("title").asText(""),
                node.path("tree").asText(""));
    }

    private boolean isDouyinSearchDone(String query, Snapshot snap) {
        if (!snap.url().toLowerCase(Locale.ROOT).contains("douyin.com")) {
            return false;
        }
        String lowerUrl = decodeUrl(snap.url()).toLowerCase(Locale.ROOT);
        String lowerQuery = query.toLowerCase(Locale.ROOT);
        boolean urlMatches = lowerUrl.contains("/search")
                && (lowerUrl.contains(lowerQuery) || lowerUrl.contains(urlEncodeLower(query)));

        String lowerTree = snap.tree().toLowerCase(Locale.ROOT);
        boolean pageMatches = lowerTree.contains(lowerQuery)
                && (lowerTree.contains("搜索") || lowerTree.contains("search")
                || lowerTree.contains("综合") || lowerTree.contains("视频")
                || lowerTree.contains("用户"));

        return urlMatches || pageMatches;
    }

    private boolean snapshotContainsQuery(String query, Snapshot snap) {
        String lowerQuery = query.toLowerCase(Locale.ROOT);
        String lowerUrl = decodeUrl(snap.url()).toLowerCase(Locale.ROOT);
        String lowerTree = snap.tree().toLowerCase(Locale.ROOT);
        return lowerUrl.contains(lowerQuery)
                || lowerUrl.contains(urlEncodeLower(query))
                || lowerTree.contains(lowerQuery);
    }

    private boolean isVisibleSiteSearchDone(String query, Snapshot snap) {
        if (snapshotContainsQuery(query, snap)) {
            String lowerTree = snap.tree().toLowerCase(Locale.ROOT);
            return lowerTree.contains("搜索") || lowerTree.contains("search")
                    || lowerTree.contains("筛选") || lowerTree.contains("排序")
                    || lowerTree.contains("综合") || lowerTree.contains("视频")
                    || lowerTree.contains("笔记") || lowerTree.contains("用户")
                    || lowerTree.contains("结果") || lowerTree.contains("相关");
        }
        return false;
    }

    private String chooseSearchTarget(Snapshot snap, List<String> roles) {
        String fromTree = searchTargetNameFromTree(snap.tree(), roles);
        if (!fromTree.isBlank()) {
            return fromTree;
        }
        String lowerTree = snap.tree().toLowerCase(Locale.ROOT);
        if (lowerTree.contains("搜索")) {
            return "搜索";
        }
        if (lowerTree.contains("search")) {
            return "search";
        }
        return "搜索";
    }

    private String searchTargetNameFromTree(String tree, List<String> roles) {
        SearchCandidate best = bestSearchCandidate(tree, roles);
        if (best != null && !best.line().name().isBlank()) {
            return best.line().name();
        }
        for (TreeLine line : parseTreeLines(tree)) {
            String role = line.role();
            String name = line.name();
            if (name.isBlank() || roles.stream().noneMatch(role::equalsIgnoreCase)) {
                continue;
            }
            String lowerName = name.toLowerCase(Locale.ROOT);
            if (lowerName.contains("搜索") || lowerName.contains("search")) {
                return name;
            }
        }
        return "";
    }

    @Nullable
    private TypePayload.FocusTarget focusTargetForSearchInput(Snapshot snap) {
        SearchCandidate best = bestSearchCandidate(snap.tree(), searchInputRoles());
        TreeLine line = best == null ? searchInputLine(snap.tree()) : best.line();
        if (line == null || line.w() <= 0 || line.h() <= 0) {
            return null;
        }
        return new TypePayload.FocusTarget(line.x() + line.w() / 2.0, line.y() + line.h() / 2.0);
    }

    @Nullable
    private SearchCandidate bestSearchCandidate(String tree, List<String> roles) {
        SearchCandidate best = parseTreeLines(tree).stream()
                .filter(line -> roles.stream().anyMatch(line.role()::equalsIgnoreCase))
                .map(line -> new SearchCandidate(line, scoreSearchCandidate(line)))
                .filter(candidate -> candidate.score() >= MIN_SEARCH_CANDIDATE_SCORE)
                .max((a, b) -> Integer.compare(a.score(), b.score()))
                .orElse(null);
        if (best == null) {
            return null;
        }
        return best;
    }

    private int scoreSearchCandidate(TreeLine line) {
        String role = line.role().toLowerCase(Locale.ROOT);
        String name = line.name().toLowerCase(Locale.ROOT);
        boolean strictInput = strictInputRoles().stream().anyMatch(line.role()::equalsIgnoreCase);
        boolean searchName = name.contains("搜索") || name.contains("search");

        if (!searchName && !strictInput) {
            return Integer.MIN_VALUE;
        }

        int score = 0;
        if ("searchbox".equals(role)) {
            score += 95;
        } else if ("textbox".equals(role) || "input".equals(role)) {
            score += 85;
        } else if ("combobox".equals(role)) {
            score += 75;
        } else if ("search".equals(role)) {
            score += 45;
        } else if ("button".equals(role)) {
            score += 30;
        } else if ("generic".equals(role) || "text".equals(role) || "statictext".equals(role)) {
            score += 15;
        } else if ("link".equals(role)) {
            score += 5;
        }

        if (name.contains("搜索你感兴趣") || name.contains("感兴趣的内容")) {
            score += 80;
        } else if (searchName) {
            score += 35;
        } else if (strictInput) {
            score += 15;
        }

        if (line.x() >= 200) {
            score += 30;
        } else {
            score -= 65;
        }

        if (line.y() <= 140) {
            score += 45;
        } else if (line.y() <= 220) {
            score += 5;
        } else {
            score -= 35;
        }

        if (line.w() >= 500) {
            score += 40;
        } else if (line.w() >= 280) {
            score += 28;
        } else if (line.w() >= 120) {
            score += 10;
        } else {
            score -= 10;
        }

        if (line.h() >= 18 && line.h() <= 100) {
            score += 15;
        } else if (line.h() > 0) {
            score += 5;
        }

        if (strictInput && line.w() >= 120) {
            score += 20;
        }
        if ("button".equals(role) && line.x() >= 200 && line.y() <= 140) {
            score += 25;
        }
        if ("搜索".equals(name) && line.x() < 200) {
            score -= 70;
        }
        if ("link".equals(role) && line.x() < 240) {
            score -= 30;
        }

        return score;
    }

    @Nullable
    private TreeLine searchInputLine(String tree) {
        TreeLine firstInput = null;
        for (TreeLine line : parseTreeLines(tree)) {
            if (strictInputRoles().stream().noneMatch(line.role()::equalsIgnoreCase)) {
                continue;
            }
            if (line.name().toLowerCase(Locale.ROOT).contains("搜索")
                    || line.name().toLowerCase(Locale.ROOT).contains("search")) {
                return line;
            }
            if (firstInput == null) {
                firstInput = line;
            }
        }
        return firstInput;
    }

    @Nullable
    private TreeLine bestFilterTrigger(String tree, List<String> triggerLabels, List<String> optionLabels) {
        return parseTreeLines(tree).stream()
                .filter(line -> containsAnyLabel(line.name(), triggerLabels))
                .filter(line -> !containsAnyLabel(line.name(), optionLabels))
                .max((a, b) -> Integer.compare(
                        scoreFilterTrigger(a, triggerLabels),
                        scoreFilterTrigger(b, triggerLabels)))
                .orElse(null);
    }

    private int scoreFilterTrigger(TreeLine line, List<String> triggerLabels) {
        String role = line.role().toLowerCase(Locale.ROOT);
        int score = 0;
        if ("button".equals(role) || "menuitem".equals(role)) {
            score += 60;
        } else if ("generic".equals(role) || "text".equals(role) || "statictext".equals(role)) {
            score += 25;
        } else {
            score += 10;
        }
        if (triggerLabels.stream().anyMatch(label -> label.equals(line.name()))) {
            score += 60;
        } else if (containsAnyLabel(line.name(), triggerLabels)) {
            score += 35;
        }
        if (line.y() <= 180) {
            score += 35;
        } else if (line.y() <= 300) {
            score += 10;
        } else {
            score -= 15;
        }
        if (line.x() >= 180) {
            score += 15;
        }
        if (line.w() >= 24 && line.w() <= 180 && line.h() >= 16 && line.h() <= 80) {
            score += 15;
        }
        return score;
    }

    @Nullable
    private SortSelection bestOption(Snapshot snap, List<String> optionLabels) {
        List<TreeLine> lines = parseTreeLines(snap.tree());
        return lines.stream()
                .filter(line -> containsAnyLabel(line.name(), optionLabels))
                .filter(line -> !isProbablySearchResultText(line))
                .map(line -> new SortSelection(
                        line,
                        matchedLabel(line.name(), optionLabels),
                        scoreOptionCandidate(line, snap, optionLabels)))
                .filter(selection -> selection.score() >= 55)
                .max((a, b) -> Integer.compare(a.score(), b.score()))
                .orElse(null);
    }

    @Nullable
    private SortSelection selectedOption(Snapshot snap, List<String> optionLabels) {
        SortSelection selection = bestOption(snap, optionLabels);
        if (selection == null) {
            return null;
        }
        String tree = snap.tree();
        String label = selection.label();
        String lowerUrl = decodeUrl(snap.url()).toLowerCase(Locale.ROOT);
        boolean urlLooksSorted = lowerUrl.contains("like") || lowerUrl.contains("digg")
                || lowerUrl.contains("sort") || lowerUrl.contains("order");
        boolean explicitSelection = tree.contains(label + " 已选")
                || tree.contains(label + " 选中")
                || tree.contains("已选 " + label)
                || tree.contains("选中 " + label)
                || tree.contains("当前排序 " + label)
                || tree.contains("排序 " + label);
        if (explicitSelection || urlLooksSorted) {
            return selection;
        }
        return null;
    }

    private boolean containsAnyLabel(String name, List<String> labels) {
        if (name == null || name.isBlank()) {
            return false;
        }
        return labels.stream()
                .map(this::normalizeQuery)
                .filter(label -> !label.isBlank())
                .anyMatch(name::contains);
    }

    private String matchedLabel(String name, List<String> labels) {
        return labels.stream()
                .map(this::normalizeQuery)
                .filter(label -> !label.isBlank())
                .filter(name::contains)
                .findFirst()
                .orElse(labels.isEmpty() ? "" : labels.getFirst());
    }

    private int scoreOptionCandidate(TreeLine line, Snapshot snap, List<String> optionLabels) {
        String role = line.role().toLowerCase(Locale.ROOT);
        int score = 0;
        if ("button".equals(role) || "menuitem".equals(role) || "option".equals(role)) {
            score += 70;
        } else if ("generic".equals(role) || "text".equals(role) || "statictext".equals(role)) {
            score += 45;
        } else {
            score += 15;
        }
        if (optionLabels.stream().anyMatch(label -> label.equals(line.name()))) {
            score += 45;
        } else if (containsAnyLabel(line.name(), optionLabels)) {
            score += 30;
        }
        if (line.y() <= 360) {
            score += 20;
        }
        if (line.x() >= 150) {
            score += 10;
        }
        if (snap.tree().contains("排序依据") || snap.tree().contains("综合排序")
                || snap.tree().contains("最新发布") || snap.tree().contains("发布时间")) {
            score += 25;
        }
        if (line.w() >= 20 && line.w() <= 260 && line.h() >= 14 && line.h() <= 90) {
            score += 10;
        }
        return score;
    }

    private boolean isProbablySearchResultText(TreeLine line) {
        String role = line.role().toLowerCase(Locale.ROOT);
        if ("article".equals(role) || "link".equals(role)) {
            return true;
        }
        String name = line.name();
        return name.length() > 40
                || name.contains("点赞最多的")
                || name.contains("获得最多点赞")
                || name.contains("点赞量最高");
    }

    private List<TreeLine> parseTreeLines(String tree) {
        if (tree == null || tree.isBlank()) {
            return List.of();
        }
        List<TreeLine> lines = new ArrayList<>();
        for (String raw : tree.split("\\R")) {
            Matcher matcher = TREE_LINE_PATTERN.matcher(raw);
            if (!matcher.matches() || matcher.group(3) == null) {
                continue;
            }
            lines.add(new TreeLine(
                    matcher.group(1) == null ? "" : matcher.group(1).trim(),
                    matcher.group(2) == null ? "" : matcher.group(2).trim(),
                    Integer.parseInt(matcher.group(3)),
                    Integer.parseInt(matcher.group(4)),
                    Integer.parseInt(matcher.group(5)),
                    Integer.parseInt(matcher.group(6))));
        }
        return lines;
    }

    private String done(String query, Snapshot snap, String via, List<Map<String, Object>> attempts) {
        return "已完成：获客助手已在你的浏览器中打开抖音首页，通过搜索框输入 `" + query + "` 并提交搜索。\n"
                + "状态：DONE\n"
                + "当前页面：" + blankFallback(snap.url(), "unknown") + "\n"
                + "路径：" + via + "\n"
                + "动作次数：" + attempts.size();
    }

    private SortRun sortFail(String step, JsonNode raw, List<Map<String, Object>> attempts) {
        String code = raw.path("code").asText(raw.path("status").asText("FAILED"));
        String message = raw.path("message").asText("Browser sort step failed: " + step);
        return new SortRun(false, code, message + "\n失败步骤：" + step,
                Snapshot.empty(), "", attempts);
    }

    private SearchRun doneRun(Snapshot snap, String via, List<Map<String, Object>> attempts) {
        return new SearchRun(true, "DONE", "", snap, via, attempts);
    }

    private SearchRun blockedRun(Snapshot snap, List<Map<String, Object>> attempts) {
        return new SearchRun(false, "BLOCKED_LOOP",
                "浏览器连续返回同一个页面状态，已停止，避免重复执行同一组搜索动作。",
                snap, "", attempts);
    }

    /**
     * Many CN sites (Douyin especially) gate search behind a login modal: after
     * Enter the page shows 登录/扫码登录/验证码登录 and never reaches a /search
     * results page, so the page "repeats" and we'd otherwise emit a confusing
     * BLOCKED_LOOP. Detect the login wall and prefer a clear LOGIN_REQUIRED.
     */
    private SearchRun loginOrBlocked(Snapshot snap, List<Map<String, Object>> attempts) {
        return isLoginWall(snap) ? loginRequiredRun(snap, attempts) : blockedRun(snap, attempts);
    }

    private SearchRun loginRequiredRun(Snapshot snap, List<Map<String, Object>> attempts) {
        return new SearchRun(false, "LOGIN_REQUIRED",
                "抖音的搜索需要登录。请先在这个浏览器里登录你的抖音账号，然后重新发起搜索"
                        + "——出于安全原因我无法替你登录。",
                snap, "", attempts);
    }

    /**
     * Heuristic login-wall detector. Requires a modal-ish phrase (扫码登录 /
     * 验证码登录 / 登录后…) rather than the bare word 登录, so a normal page that
     * merely has a "登录" button in the corner does not trip it.
     */
    private boolean isLoginWall(Snapshot snap) {
        String hay = (blankFallback(snap.title(), "") + "\n" + blankFallback(snap.tree(), ""))
                .toLowerCase(Locale.ROOT);
        return hay.contains("登录后即可")
                || hay.contains("扫码登录")
                || hay.contains("验证码登录")
                || hay.contains("手机号登录")
                || hay.contains("登录抖音")
                || hay.contains("scan to log in")
                || hay.contains("sign in to continue");
    }

    private SearchRun failRun(String step, JsonNode raw, List<Map<String, Object>> attempts) {
        String code = raw.path("code").asText(raw.path("status").asText("FAILED"));
        String message = raw.path("message").asText("Browser task step failed: " + step);
        return new SearchRun(false, code, message + "\n失败步骤：" + step, Snapshot.empty(), "", attempts);
    }

    private String failureText(SearchRun run) {
        return "未完成：" + run.message() + "\n"
                + "状态：" + run.status() + "\n"
                + "当前页面：" + blankFallback(run.snapshot().url(), "unknown") + "\n"
                + "动作次数：" + run.attempts().size();
    }

    private JsonNode parse(String raw) {
        try {
            return mapper.readTree(raw);
        } catch (Exception e) {
            return mapper.createObjectNode()
                    .put("ok", false)
                    .put("code", "MALFORMED_TOOL_RESULT")
                    .put("message", e.getMessage() == null ? "" : e.getMessage());
        }
    }

    private String json(Map<String, Object> obj) {
        try {
            return mapper.writeValueAsString(obj);
        } catch (JsonProcessingException e) {
            log.warn("[LeadBrowserHarnessTool] serialise failed: {}", e.getMessage());
            return "{\"ok\":false,\"status\":\"SERIALISE_FAILED\"}";
        }
    }

    private boolean ok(JsonNode node) {
        return node.path("ok").asBoolean(false);
    }

    private boolean isSessionDetached(JsonNode node) {
        String code = node.path("code").asText(node.path("status").asText(""));
        String message = node.path("message").asText("");
        return "SESSION_DETACHED".equalsIgnoreCase(code)
                || message.toLowerCase(Locale.ROOT).contains("detached while handling command");
    }

    private void sleepBeforeRetry(int attempt) {
        sleep(SESSION_DETACH_RETRY_DELAY_MS * attempt);
    }

    private void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private List<String> searchEntryRoles() {
        return List.of("textbox", "searchbox", "combobox", "input", "button", "link",
                "search", "generic", "statictext", "text");
    }

    private List<String> searchInputRoles() {
        return List.of("textbox", "searchbox", "combobox", "input", "generic", "text");
    }

    private List<String> strictInputRoles() {
        return List.of("textbox", "searchbox", "combobox", "input");
    }

    private String normalizeQuery(String query) {
        return query == null ? "" : query.trim();
    }

    private String normalizeHomeUrl(String url) {
        String trimmed = normalizeQuery(url);
        if (trimmed.isBlank()) {
            return "";
        }
        if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
            return trimmed;
        }
        return "https://" + trimmed;
    }

    private String hostLabel(String url) {
        try {
            String host = java.net.URI.create(url).getHost();
            return host == null || host.isBlank() ? "站点" : host;
        } catch (Exception ignored) {
            return "站点";
        }
    }

    private String decodeUrl(String url) {
        try {
            return java.net.URLDecoder.decode(url, StandardCharsets.UTF_8);
        } catch (Exception ignored) {
            return url;
        }
    }

    private String urlEncodeLower(String query) {
        return URLEncoder.encode(query, StandardCharsets.UTF_8).toLowerCase(Locale.ROOT);
    }

    private String blankFallback(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private List<String> candidateLines(String tree, int limit) {
        if (tree == null || tree.isBlank()) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (String rawLine : tree.split("\\R")) {
            String line = rawLine.replaceAll("\\s+", " ").trim();
            if (line.isBlank() || line.length() < 3) {
                continue;
            }
            out.add(line.length() <= 500 ? line : line.substring(0, 500));
            if (out.size() >= limit) {
                break;
            }
        }
        return out;
    }

    private String excerpt(String text, int maxChars) {
        if (text == null) {
            return "";
        }
        String normalized = text.replaceAll("\\s+", " ").trim();
        return normalized.length() <= maxChars ? normalized : normalized.substring(0, maxChars);
    }

    private int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private record ObserveResult(boolean ok, JsonNode raw, Snapshot snapshot) {}

    private record SearchRun(boolean done,
                             String status,
                             String message,
                             Snapshot snapshot,
                             String via,
                             List<Map<String, Object>> attempts) {}

    private record SortRun(boolean done,
                           String status,
                           String message,
                           Snapshot snapshot,
                           String sortLabel,
                           List<Map<String, Object>> attempts) {}

    private record Snapshot(String url, String title, String tree) {
        static Snapshot empty() {
            return new Snapshot("", "", "");
        }
    }

    private record TreeLine(String role, String name, int x, int y, int w, int h) {}

    private record SearchCandidate(TreeLine line, int score) {}

    private record SortSelection(TreeLine line, String label, int score) {}

    private final class FingerprintGuard {
        private String previous;
        private int repeats;

        boolean repeated(Snapshot snap) {
            String current = snap.url() + "\n" + snap.title() + "\n" + trimForFingerprint(snap.tree());
            if (current.equals(previous)) {
                repeats += 1;
            } else {
                previous = current;
                repeats = 1;
            }
            return repeats >= MAX_REPEAT_FINGERPRINTS;
        }

        private String trimForFingerprint(String tree) {
            String normalized = tree.replaceAll("\\s+", " ").trim();
            return normalized.length() <= 500 ? normalized : normalized.substring(0, 500);
        }
    }
}
