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
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
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
    private static final int PROFILE_OPEN_OBSERVE_ATTEMPTS = 4;
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

            IMPORTANT: if the user's task continues after sorting — e.g. "open the first
            video", "打开评论区", "评论区获客" — do NOT use this partial tool. Use
            lead_browser_douyin_search_sort_open_first_video_comments instead so the whole
            flow stays inside one verified harness and does not fall back to brittle manual
            icon/JS clicking.

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
            FIRST-CHOICE end-to-end Douyin harness for:
              "用我的浏览器打开抖音，搜索 <query>，点击筛选，按最多点赞排序，
               点开第一个视频，打开评论区，停在评论区"

            This single tool performs the whole chain in the user's visible Chrome:
              1. opens Douyin and searches through the real visible search box;
              2. opens 筛选 and selects 最多点赞;
              3. chooses the highest-liked visible video result and clicks the video cover/card
                 rather than author/profile links;
              4. focuses the video surface and presses Douyin's X shortcut to open comments;
              5. verifies readable comment content before returning DONE_COMMENTS_OPENED.

            Do not decompose this task into extension_browser_click / JS / manual icon clicks.
            If this returns false, stop and report its status/message/attempts instead of
            retrying random coordinates.
            """)
    public String lead_browser_douyin_search_sort_open_first_video_comments(
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
        if (!sort.done()) {
            return json(Map.of(
                    "ok", false,
                    "status", sort.status(),
                    "message", sort.message(),
                    "query", normalizedQuery,
                    "url", sort.snapshot().url(),
                    "title", sort.snapshot().title(),
                    "attempts", sort.attempts()));
        }

        VideoCommentsRun comments = runDouyinOpenFirstVideoCommentsFrom(sort.snapshot(), sort.attempts(), ctx);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", comments.done());
        out.put("status", comments.status());
        if (!comments.message().isBlank()) {
            out.put("message", comments.message());
        }
        out.put("query", normalizedQuery);
        out.put("sort", sort.sortLabel());
        out.put("url", comments.snapshot().url());
        out.put("title", comments.snapshot().title());
        out.put("attempts", comments.attempts());
        return json(out);
    }

    @Tool(description = """
            FULL debug lead-acquisition harness for Douyin from scratch:
              open Douyin → search <query> → 筛选/最多点赞 → open the highest-liked first
              video → open comments → read visible comments → click the first visible
              comment author's profile → click 关注 → click 私信, but DO NOT type or send.

            Use this when testing the complete post-comment lead path end-to-end. It is a
            one-shot harness so the agent does not stitch together multiple brittle low-level
            browser clicks. It returns visible candidate_comments and the selected first
            comment/author for inspection.
            """)
    public String lead_browser_douyin_debug_full_first_comment_follow_open_dm(
            @ToolParam(description = "Search keyword, e.g. openclaw") String query,
            @Nullable ToolContext ctx) {
        String normalizedQuery = normalizeQuery(query);
        if (normalizedQuery.isBlank()) {
            return json(Map.of(
                    "ok", false,
                    "status", "INVALID_QUERY",
                    "message", "query is required"));
        }

        FirstCommentEngagementRun run = runDouyinDebugFullFirstCommentFollowOpenDm(normalizedQuery, ctx);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", run.done());
        out.put("status", run.status());
        if (!run.message().isBlank()) {
            out.put("message", run.message());
        }
        out.put("query", normalizedQuery);
        out.put("url", run.snapshot().url());
        out.put("title", run.snapshot().title());
        out.put("first_comment", run.firstComment() == null ? "" : run.firstComment().comment().name());
        out.put("first_author", run.author() == null ? "" : run.author().name());
        out.put("profile_url", run.profileUrl());
        out.put("follow_clicked", run.followClicked());
        out.put("dm_opened", run.dmOpened());
        out.put("candidate_comments", run.candidateComments());
        out.put("attempts", run.attempts());
        return json(out);
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
            Lead-acquisition browser harness for Douyin comment-section prospecting.

            Use after the browser is already on a Douyin search results page, ideally after
            sorting/filtering. It opens the first visible video/result, opens or reveals the
            comment area, searches visible comments for text similar to target_comment, clicks
            the matched commenter's profile/user link, and then STOPS.

            Safety boundary: this tool never clicks 关注, 私信, or sends any message. If it
            opens a matching user's profile, it returns next_action telling the agent to call
            extension_browser_click("关注", "button", ...) so the existing Tool Guard approval
            flow can handle the outbound contact action.

            Returns JSON with:
              status DONE_PROFILE_OPENED | NO_VIDEO_RESULT | COMMENTS_NOT_FOUND |
                     COMMENT_MATCH_NOT_FOUND | PROFILE_OPEN_FAILED | LOGIN_REQUIRED
              matched_comment, matched_author, profile_url, candidate_comments, next_action.
            """)
    public String lead_browser_douyin_open_first_video_match_comment_user(
            @ToolParam(description = "Target comment/query to match, e.g. 有一种和“老年人玩不懂智能手机”一样的无力感")
            String targetComment,
            @ToolParam(description = "Maximum comment scroll/read attempts after opening the video. Default 4, max 8.",
                    required = false)
            Integer maxScrolls,
            @Nullable ToolContext ctx) {
        String normalizedTarget = normalizeQuery(targetComment);
        if (normalizedTarget.isBlank()) {
            return json(Map.of(
                    "ok", false,
                    "status", "INVALID_TARGET_COMMENT",
                    "message", "target_comment is required"));
        }

        int scrollLimit = clamp(maxScrolls == null ? 4 : maxScrolls, 0, 8);
        CommentLeadRun run = runDouyinOpenFirstVideoMatchCommentUser(normalizedTarget, scrollLimit, ctx);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", run.done());
        out.put("status", run.status());
        if (!run.message().isBlank()) {
            out.put("message", run.message());
        }
        out.put("target_comment", normalizedTarget);
        out.put("url", run.snapshot().url());
        out.put("title", run.snapshot().title());
        out.put("matched_comment", run.match() == null ? "" : run.match().comment().name());
        out.put("matched_author", run.author() == null ? "" : run.author().name());
        out.put("profile_url", run.profileUrl());
        out.put("candidate_comments", run.candidateComments());
        out.put("attempts", run.attempts());
        if (run.done()) {
            out.put("next_action",
                    "Call extension_browser_click(hintText=\"关注\", role=\"button\") after the user approves this specific candidate. Do not coordinate-click 关注.");
        }
        return json(out);
    }

    @Tool(description = """
            DEBUG lead-acquisition harness for the current Douyin video/comments page.

            Use when the browser is already on an opened Douyin video and the comments panel
            is visible. It reads the currently visible comment rows, clicks the first visible
            comment author's profile, clicks 关注 if present, then clicks 私信 / 发私信 /
            message entry if present. It intentionally does NOT type or send any message.

            This is for debugging the post-comment lead path only. It returns candidate_comments
            so the caller can inspect what the harness read from the comments panel.
            """)
    public String lead_browser_douyin_debug_first_comment_follow_open_dm(@Nullable ToolContext ctx) {
        FirstCommentEngagementRun run = runDouyinDebugFirstCommentFollowOpenDm(ctx);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", run.done());
        out.put("status", run.status());
        if (!run.message().isBlank()) {
            out.put("message", run.message());
        }
        out.put("url", run.snapshot().url());
        out.put("title", run.snapshot().title());
        out.put("first_comment", run.firstComment() == null ? "" : run.firstComment().comment().name());
        out.put("first_author", run.author() == null ? "" : run.author().name());
        out.put("profile_url", run.profileUrl());
        out.put("follow_clicked", run.followClicked());
        out.put("dm_opened", run.dmOpened());
        out.put("candidate_comments", run.candidateComments());
        out.put("attempts", run.attempts());
        return json(out);
    }

    @Tool(description = """
            Lead-acquisition browser harness for the NEXT small Douyin step only:
            from the current Douyin search results page, truly open the first visible
            video/result and open/reveal its comment area.

            Use this when the task is only "点击第一个视频 / 打开评论区". It does not
            match comments, open user profiles, follow anyone, or send messages.

            The tool verifies effects after each click:
              1. after clicking the first result, it must observe a video page/modal;
              2. after clicking 评论, it must observe a readable comment area.

            Returns JSON with status:
              DONE_COMMENTS_OPENED | NO_VIDEO_RESULT | VIDEO_OPEN_FAILED |
              COMMENTS_NOT_OPENED | LOGIN_REQUIRED.
            """)
    public String lead_browser_douyin_open_first_video_comments(@Nullable ToolContext ctx) {
        VideoCommentsRun run = runDouyinOpenFirstVideoComments(ctx);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", run.done());
        out.put("status", run.status());
        if (!run.message().isBlank()) {
            out.put("message", run.message());
        }
        out.put("url", run.snapshot().url());
        out.put("title", run.snapshot().title());
        out.put("attempts", run.attempts());
        return json(out);
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
            return selectOptionViaVisibleTriggerText(siteName, triggerLabels, optionLabels, current, attempts, ctx);
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
            if (filterPanelLooksOpen(current, optionLabels) || hoverClickToggleSensitiveSite(siteName)) {
                return clickOptionByVisibleText(siteName, optionLabels, current, attempts, ctx,
                        "fallback_click_option_by_visible_text_after_hover",
                        "wait_after_hover_text_sort_select",
                        "verify_hover_text_sort_select");
            }
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
            return clickOptionByVisibleText(siteName, optionLabels, current, attempts, ctx,
                    "fallback_click_option_by_visible_text",
                    "wait_after_text_sort_select",
                    "verify_text_most_liked_sort");
        }

        return clickObservedOption(siteName, optionLabels, option, attempts, ctx,
                "click_filter_option", "wait_after_sort_select", "verify_most_liked_sort");
    }

    private CommentLeadRun runDouyinOpenFirstVideoMatchCommentUser(String targetComment,
                                                                   int maxScrolls,
                                                                   ToolContext ctx) {
        VideoCommentsRun setup = runDouyinOpenFirstVideoComments(ctx);
        List<Map<String, Object>> attempts = new ArrayList<>(setup.attempts());
        Snapshot current = setup.snapshot();
        if (!setup.done()) {
            return commentLeadFail(setup.status(), setup.message(), current,
                    null, null, "", List.of(), attempts);
        }

        List<String> candidateComments = new ArrayList<>();
        CommentMatch bestSeen = null;
        for (int read = 0; read <= maxScrolls; read++) {
            List<CommentMatch> matches = commentMatches(current, targetComment);
            appendCandidateComments(candidateComments, matches, 12);
            CommentMatch best = matches.stream()
                    .filter(match -> match.score() >= 55)
                    .max(Comparator.comparingInt(CommentMatch::score))
                    .orElse(null);
            if (best != null) {
                TreeLine author = commentAuthorNearMatch(current.tree(), best.comment());
                if (author == null) {
                    return commentLeadFail("PROFILE_OPEN_FAILED",
                            "已找到相似评论，但没有定位到这条评论附近可点击的用户主页/作者名称。",
                            current, best, null, "", candidateComments, attempts);
                }

                JsonNode openProfile = clickAtLine("click_matched_comment_author", author, attempts, ctx);
                if (!ok(openProfile)) {
                    return commentLeadFail("PROFILE_OPEN_FAILED",
                            "已找到相似评论和作者，但点击作者主页失败："
                                    + openProfile.path("message").asText("unknown"),
                            current, best, author, "", candidateComments, attempts);
                }
                localWait("wait_after_author_click", attempts, PAGE_SETTLE_DELAY_MS);

                ProfileOpenResult profile = observeProfileAfterAuthorClick(current,
                        "observe_matched_author_profile", attempts, ctx);
                Snapshot profileSnap = profile.snapshot();
                if (profile.ok()) {
                    return new CommentLeadRun(true, "DONE_PROFILE_OPENED", "", profileSnap,
                            best, author, profileSnap.url(), List.copyOf(candidateComments), attempts);
                }
                return commentLeadFail("PROFILE_OPEN_FAILED",
                        "已点击匹配评论附近的作者，但没有确认进入用户主页。",
                        profileSnap, best, author, profileSnap.url(), candidateComments, attempts);
            }

            bestSeen = matches.stream()
                    .max(Comparator.comparingInt(CommentMatch::score))
                    .orElse(bestSeen);
            if (read == maxScrolls) {
                break;
            }

            JsonNode scroll = callBrowser("scroll_comments_" + (read + 1), attempts,
                    () -> browser.extension_browser_scroll("down", 650, ctx));
            if (!ok(scroll)) {
                break;
            }
            localWait("wait_after_comment_scroll_" + (read + 1), attempts, FILTER_PANEL_SETTLE_DELAY_MS);
            ObserveResult afterScroll = observe("observe_comments_after_scroll_" + (read + 1), attempts, ctx);
            if (!afterScroll.ok()) {
                break;
            }
            current = afterScroll.snapshot();
            if (isLoginWall(current)) {
                return commentLeadFail("LOGIN_REQUIRED",
                        "抖音评论区需要登录。请先在这个浏览器里登录账号，然后重新发起任务。",
                        current, bestSeen, null, "", candidateComments, attempts);
            }
        }

        if (candidateComments.isEmpty()) {
            return commentLeadFail("COMMENTS_NOT_FOUND",
                    "已打开第一条视频，但没有在可见页面中读到评论内容。",
                    current, bestSeen, null, "", candidateComments, attempts);
        }
        return commentLeadFail("COMMENT_MATCH_NOT_FOUND",
                "已读取可见评论，但没有找到与目标评论足够相似的评论。",
                current, bestSeen, null, "", candidateComments, attempts);
    }

    private FirstCommentEngagementRun runDouyinDebugFirstCommentFollowOpenDm(ToolContext ctx) {
        List<Map<String, Object>> attempts = new ArrayList<>();
        ObserveResult initial = observe("observe_current_douyin_comments_for_first_author", attempts, ctx);
        if (!initial.ok()) {
            return firstCommentEngagementFail(
                    initial.raw().path("code").asText(initial.raw().path("status").asText("OBSERVE_FAILED")),
                    initial.raw().path("message").asText("Unable to observe current Douyin comments page"),
                    Snapshot.empty(), null, null, "", false, false, List.of(), attempts);
        }

        return engageFirstVisibleCommentAuthor(initial.snapshot(), attempts, ctx);
    }

    private FirstCommentEngagementRun runDouyinDebugFullFirstCommentFollowOpenDm(String query, ToolContext ctx) {
        SearchRun search = runDouyinSearch(query, ctx);
        if (!search.done()) {
            return firstCommentEngagementFail(search.status(), search.message(), search.snapshot(),
                    null, null, "", false, false, List.of(), search.attempts());
        }

        SortRun sort = runDouyinMostLikedSort(query, search.snapshot(), search.attempts(), ctx);
        if (!sort.done()) {
            return firstCommentEngagementFail(sort.status(), sort.message(), sort.snapshot(),
                    null, null, "", false, false, List.of(), sort.attempts());
        }

        VideoCommentsRun comments = runDouyinOpenFirstVideoCommentsFrom(sort.snapshot(), sort.attempts(), ctx);
        if (!comments.done()) {
            return firstCommentEngagementFail(comments.status(), comments.message(), comments.snapshot(),
                    null, null, "", false, false, List.of(), comments.attempts());
        }

        return engageFirstVisibleCommentAuthor(comments.snapshot(), new ArrayList<>(comments.attempts()), ctx);
    }

    private FirstCommentEngagementRun engageFirstVisibleCommentAuthor(Snapshot current,
                                                                      List<Map<String, Object>> attempts,
                                                                      ToolContext ctx) {
        if (isLoginWall(current)) {
            return firstCommentEngagementFail("LOGIN_REQUIRED",
                    "抖音页面需要登录。请先在这个浏览器里登录账号，然后重新发起任务。",
                    current, null, null, "", false, false, List.of(), attempts);
        }
        if (!hasVisibleCommentPanel(current)) {
            return firstCommentEngagementFail("COMMENTS_NOT_OPENED",
                    "当前页没有确认看到已展开的评论区，请先打开评论区后再执行这个调试工具。",
                    current, null, null, "", false, false, List.of(), attempts);
        }

        List<CommentMatch> matches = visiblePanelCommentMatches(current);
        List<String> candidateComments = new ArrayList<>();
        appendCandidateComments(candidateComments, matches, 20);
        CommentMatch first = matches.stream()
                .filter(match -> commentAuthorNearMatch(current.tree(), match.comment()) != null)
                .min(Comparator.comparingInt(match -> match.comment().y()))
                .orElse(null);
        if (first == null) {
            return firstCommentEngagementFail("COMMENTS_NOT_FOUND",
                    "已看到评论区，但没有定位到可点击作者的可见评论。",
                    current, null, null, "", false, false, candidateComments, attempts);
        }

        TreeLine author = commentAuthorNearMatch(current.tree(), first.comment());
        if (author == null) {
            return firstCommentEngagementFail("PROFILE_OPEN_FAILED",
                    "已找到第一条评论，但没有定位到这条评论附近的用户主页入口。",
                    current, first, null, "", false, false, candidateComments, attempts);
        }

        JsonNode openProfile = clickAtLine("click_first_comment_author", author, attempts, ctx);
        if (!ok(openProfile)) {
            return firstCommentEngagementFail("PROFILE_OPEN_FAILED",
                    "点击第一条评论作者失败：" + openProfile.path("message").asText("unknown"),
                    current, first, author, "", false, false, candidateComments, attempts);
        }
        localWait("wait_after_first_comment_author_click", attempts, PAGE_SETTLE_DELAY_MS);

        ProfileOpenResult profile = observeProfileAfterAuthorClick(current,
                "observe_first_comment_author_profile", attempts, ctx);
        Snapshot profileSnap = profile.snapshot();
        if (!profile.ok()) {
            return firstCommentEngagementFail("PROFILE_OPEN_FAILED",
                    "已点击第一条评论作者，但没有确认进入用户主页。",
                    profileSnap, first, author, profileSnap.url(), false, false, candidateComments, attempts);
        }

        FollowDmResult engagement = clickFollowAndOpenDm(profileSnap, profile.target(), attempts, ctx);
        return new FirstCommentEngagementRun(true,
                engagement.dmOpened() ? "DONE_DM_OPENED" : "DONE_PROFILE_FOLLOW_ATTEMPTED",
                engagement.dmOpened() ? "" : "已进入主页并尝试关注，但没有确认打开私信入口。",
                engagement.snapshot(), first, author, engagement.snapshot().url(),
                engagement.followClicked(), engagement.dmOpened(), List.copyOf(candidateComments), attempts);
    }

    private VideoCommentsRun runDouyinOpenFirstVideoComments(ToolContext ctx) {
        List<Map<String, Object>> attempts = new ArrayList<>();
        ObserveResult initial = observe("observe_current_douyin_results", attempts, ctx);
        if (!initial.ok()) {
            return videoCommentsFail(
                    initial.raw().path("code").asText(initial.raw().path("status").asText("OBSERVE_FAILED")),
                    initial.raw().path("message").asText("Unable to observe current page"),
                    Snapshot.empty(), attempts);
        }

        return runDouyinOpenFirstVideoCommentsFrom(initial.snapshot(), attempts, ctx);
    }

    private VideoCommentsRun runDouyinOpenFirstVideoCommentsFrom(Snapshot start,
                                                                 List<Map<String, Object>> previousAttempts,
                                                                 ToolContext ctx) {
        List<Map<String, Object>> attempts = new ArrayList<>(previousAttempts);
        Snapshot current = start;
        if (isLoginWall(current)) {
            return videoCommentsFail("LOGIN_REQUIRED",
                    "抖音页面需要登录。请先在这个浏览器里登录账号，然后重新发起任务。",
                    current, attempts);
        }

        if (!isDouyinVideoOpen(current)) {
            VideoResultTarget firstVideo = firstDouyinVideoTarget(current);
            if (firstVideo == null) {
                JsonNode scroll = callBrowser("scroll_results_to_find_first_video", attempts,
                        () -> browser.extension_browser_scroll("down", 500, ctx));
                if (ok(scroll)) {
                    localWait("wait_after_results_scroll", attempts, FILTER_PANEL_SETTLE_DELAY_MS);
                    ObserveResult refreshed = observe("observe_results_after_scroll", attempts, ctx);
                    if (!refreshed.ok()) {
                        return videoCommentsFail(
                                refreshed.raw().path("code").asText(refreshed.raw().path("status").asText("OBSERVE_FAILED")),
                                refreshed.raw().path("message").asText("Unable to observe search results after scrolling"),
                                current, attempts);
                    }
                    current = refreshed.snapshot();
                    firstVideo = firstDouyinVideoTarget(current);
                }
            }
            if (firstVideo == null) {
                return videoCommentsFail("NO_VIDEO_RESULT",
                        "当前页没有找到可信的第一条视频/搜索结果卡片。",
                        current, attempts);
            }

            // Hover is useful on Douyin because cards sometimes lazy-hydrate
            // their clickable surface on pointer enter. Treat it as a best-effort
            // priming step; opening is verified by the click+observe loop below.
            hoverAtLine("hover_first_video_result", firstVideo.clickLine(), attempts, ctx);

            VideoOpenAttempt opened = openDouyinVideoTarget(firstVideo, current, attempts, ctx);
            current = opened.snapshot();
            if (!opened.clicked()) {
                return videoCommentsFail("VIDEO_OPEN_FAILED",
                        "找到第一条视频/搜索结果，但点击候选视频区域失败。",
                        current, attempts);
            }
            if (isLoginWall(current)) {
                return videoCommentsFail("LOGIN_REQUIRED",
                        "抖音视频或评论区需要登录。请先在这个浏览器里登录账号，然后重新发起任务。",
                        current, attempts);
            }
            if (!isDouyinVideoOpen(current)) {
                return videoCommentsFail("VIDEO_OPEN_FAILED",
                        "已点击第一条搜索结果，但没有观察到视频页或视频弹层，已停止，避免在搜索页误操作评论。",
                        current, attempts);
            }
        }

        CommentReveal commentsReveal = revealDouyinComments(current, attempts, ctx);
        Snapshot comments = commentsReveal.snapshot();
        if (isLoginWall(comments)) {
            return videoCommentsFail("LOGIN_REQUIRED",
                    "抖音评论区需要登录。请先在这个浏览器里登录账号，然后重新发起任务。",
                    comments, attempts);
        }
        if (!commentsReveal.opened()) {
            return videoCommentsFail("COMMENTS_NOT_OPENED",
                    "已打开视频，但没有成功点击评论入口；读取到的评论数据不等于评论区已展开。",
                    comments, attempts);
        }
        if (!hasCommentContent(comments)) {
            return videoCommentsFail("COMMENTS_NOT_OPENED",
                    "已打开视频，但没有观察到评论区内容；可能评论按钮未展开、评论区被登录/风控遮挡，或当前视频无可见评论。",
                    comments, attempts);
        }
        return new VideoCommentsRun(true, "DONE_COMMENTS_OPENED", "", comments, attempts);
    }

    private CommentReveal revealDouyinComments(Snapshot current,
                                               List<Map<String, Object>> attempts,
                                               ToolContext ctx) {
        boolean clicked = false;

        // 1) Douyin video pages have native keyboard shortcuts for comments.
        //    Prefer them before generic text/icon clicks: they avoid the
        //    unstable path that can miss the speech-bubble icon or accidentally
        //    click an author/card nearby. If the shortcuts fail to produce a
        //    visible comments panel, fall back to grounded clicking below.
        if (!hasVisibleCommentPanel(current) && isDouyinVideoOpen(current)) {
            CommentReveal shortcut = pressDouyinCommentsShortcuts(current, attempts, ctx);
            current = shortcut.snapshot();
            if (shortcut.opened()) {
                return shortcut;
            }
        }

        // 2) Tree trigger — a comment entry that surfaced in the a11y tree with a
        //    usable bbox (a labeled "评论 N" button/icon). Cheap + deterministic;
        //    handles the common case where the comment control IS named.
        TreeLine trigger = bestCommentTrigger(current.tree());
        if (trigger != null) {
            CommentReveal triggerClick = clickCommentTrigger(trigger, current, attempts, ctx);
            clicked = triggerClick.opened();
            current = triggerClick.snapshot();
            if (clicked && hasVisibleCommentPanel(current)) {
                return triggerClick;
            }
        }

        // 3) Grounded vision click — the comment entry is an UNLABELED icon
        //    (speech-bubble + count, no accessible text) that never appears as a
        //    clickable "评论" node in the tree. extension_browser_click runs the
        //    full DOM → A11y → Vision cascade, so the vision model locates it. This
        //    remains as a fallback for layouts/regions where the keyboard shortcut
        //    is unavailable.
        if (!hasVisibleCommentPanel(current)) {
            CommentReveal grounded = clickCommentsByVisibleText(current, attempts, ctx);
            current = grounded.snapshot();
            if (grounded.opened()) {
                return grounded;
            }
        }

        return new CommentReveal(current, clicked && hasVisibleCommentPanel(current));
    }

    private VideoOpenAttempt openDouyinVideoTarget(VideoResultTarget target,
                                                   Snapshot current,
                                                   List<Map<String, Object>> attempts,
                                                   ToolContext ctx) {
        List<ClickPoint> points = videoOpenClickPoints(target, current);
        boolean clicked = false;
        for (int i = 0; i < points.size(); i++) {
            ClickPoint point = points.get(i);
            String suffix = i == 0 ? "" : "_variant_" + (i + 1);
            JsonNode click = clickAtPoint("click_first_video_result" + suffix,
                    point.x(), point.y(), attempts, ctx);
            if (!ok(click)) {
                continue;
            }
            clicked = true;
            localWait("wait_after_first_video_click" + suffix, attempts,
                    i == 0 ? PAGE_SETTLE_DELAY_MS : FILTER_PANEL_SETTLE_DELAY_MS);
            ObserveResult afterOpen = observe("observe_video_page" + suffix, attempts, ctx);
            if (!afterOpen.ok()) {
                return new VideoOpenAttempt(current, clicked);
            }
            current = afterOpen.snapshot();
            if (isDouyinVideoOpen(current) || isLoginWall(current)) {
                return new VideoOpenAttempt(current, true);
            }
        }
        return new VideoOpenAttempt(current, clicked);
    }

    private List<ClickPoint> videoOpenClickPoints(VideoResultTarget target, Snapshot snap) {
        List<ClickPoint> points = new ArrayList<>();
        List<TreeLine> surfaces = videoClickSurfacesForTarget(target, snap);
        for (TreeLine surface : surfaces) {
            addClickPoint(points, centerOf(surface), snap);
        }
        addClickPoint(points, videoCardCenterPoint(target.clickLine(), target.evidenceLine()), snap);
        for (TreeLine surface : surfaces) {
            if (surface.w() >= 120 && surface.h() >= 90) {
                addClickPoint(points, videoSurfacePoint(surface, 0.50, 0.30), snap);
                addClickPoint(points, videoSurfacePoint(surface, 0.50, 0.62), snap);
            }
            addClickPoint(points, videoCoverLikePoint(surface), snap);
        }
        return points;
    }

    private List<TreeLine> videoClickSurfacesForTarget(VideoResultTarget target, Snapshot snap) {
        List<TreeLine> surfaces = parseTreeLines(snap.tree()).stream()
                .filter(line -> sameVideoResultCard(target.evidenceLine(), line)
                        || sameVideoResultCard(target.clickLine(), line))
                .filter(line -> isVideoCardClickableSurface(line, target.evidenceLine()))
                .sorted(Comparator.comparingInt((TreeLine line) -> scoreVideoClickSurface(line, target.evidenceLine()))
                        .reversed())
                .toList();
        if (!surfaces.isEmpty()) {
            return surfaces;
        }
        return List.of(target.clickLine(), target.evidenceLine());
    }

    private ClickPoint centerOf(TreeLine line) {
        return new ClickPoint(line.x() + line.w() / 2.0, line.y() + line.h() / 2.0);
    }

    private ClickPoint videoCardCenterPoint(TreeLine clickLine, TreeLine evidence) {
        int left = Math.min(clickLine.x(), evidence.x());
        int top = Math.min(clickLine.y(), evidence.y());
        int right = Math.max(clickLine.x() + clickLine.w(), evidence.x() + evidence.w());
        int bottom = Math.max(clickLine.y() + clickLine.h(), evidence.y() + evidence.h());
        double width = Math.max(160.0, right - left);
        double height = Math.max(160.0, bottom - top);
        return new ClickPoint(left + width / 2.0, top + height / 2.0);
    }

    private ClickPoint videoCoverLikePoint(TreeLine line) {
        double x = line.x() + Math.max(24.0, Math.min(line.w() * 0.5, line.w() - 24.0));
        double y = line.y() + Math.max(36.0, Math.min(line.h() * 0.45, line.h() - 36.0));
        return new ClickPoint(x, y);
    }

    private ClickPoint videoSurfacePoint(TreeLine line, double xRatio, double yRatio) {
        double x = line.x() + Math.max(8.0, Math.min(line.w() * xRatio, line.w() - 8.0));
        double y = line.y() + Math.max(8.0, Math.min(line.h() * yRatio, line.h() - 8.0));
        return new ClickPoint(x, y);
    }

    private void addClickPoint(List<ClickPoint> points, ClickPoint point, Snapshot snap) {
        if (point.x() < 0 || point.y() < 0 || point.x() >= viewportW(snap) || point.y() >= viewportH(snap)) {
            return;
        }
        boolean duplicate = points.stream()
                .anyMatch(existing -> Math.abs(existing.x() - point.x()) < 3
                        && Math.abs(existing.y() - point.y()) < 3);
        if (!duplicate) {
            points.add(point);
        }
    }

    private CommentReveal clickCommentTrigger(TreeLine trigger,
                                              Snapshot current,
                                              List<Map<String, Object>> attempts,
                                              ToolContext ctx) {
        List<ClickPoint> points = commentClickPoints(trigger);
        boolean clicked = false;
        for (int i = 0; i < points.size(); i++) {
            ClickPoint point = points.get(i);
            String suffix = i == 0 ? "" : "_variant_" + (i + 1);
            JsonNode click = clickAtPoint("click_comments_trigger" + suffix, point.x(), point.y(), attempts, ctx);
            if (!ok(click)) {
                continue;
            }
            clicked = true;
            localWait("wait_after_comments_trigger_click" + suffix, attempts, PAGE_SETTLE_DELAY_MS);
            ObserveResult observed = observe("observe_comments_after_trigger" + suffix, attempts, ctx);
            if (observed.ok()) {
                current = observed.snapshot();
            }
            if (hasVisibleCommentPanel(current)) {
                return new CommentReveal(current, true);
            }
        }
        return new CommentReveal(current, clicked && hasVisibleCommentPanel(current));
    }

    private CommentReveal pressDouyinCommentsShortcuts(Snapshot current,
                                                       List<Map<String, Object>> attempts,
                                                       ToolContext ctx) {
        for (String key : List.of("x", "k")) {
            ClickPoint focus = douyinVideoFocusPoint(current);
            JsonNode focusMove = clickAtPoint("focus_video_before_comments_shortcut_" + key,
                    focus.x(), focus.y(), attempts, ctx);
            if (ok(focusMove)) {
                localWait("wait_after_video_focus_before_shortcut_" + key, attempts, 250L);
            }

            JsonNode press = callBrowser("press_douyin_comments_shortcut_" + key, attempts,
                    () -> browser.extension_browser_press_key(key, ctx));
            if (isUnknownActionKind(press)) {
                press = callBrowser("fallback_type_comments_shortcut_" + key + "_for_legacy_extension", attempts,
                        () -> browser.extension_browser_type(key, ctx));
            }
            if (!ok(press)) {
                continue;
            }

            localWait("wait_after_comments_shortcut_" + key, attempts, PAGE_SETTLE_DELAY_MS);
            ObserveResult observed = observe("observe_comments_after_shortcut_" + key, attempts, ctx);
            if (observed.ok()) {
                current = observed.snapshot();
            }
            if (hasVisibleCommentPanel(current)) {
                return new CommentReveal(current, true);
            }

            localWait("wait_after_comments_shortcut_" + key + "_second_settle", attempts,
                    FILTER_PANEL_SETTLE_DELAY_MS);
            ObserveResult settled = observe("observe_comments_after_shortcut_" + key + "_second_settle",
                    attempts, ctx);
            if (settled.ok()) {
                current = settled.snapshot();
            }
            if (hasVisibleCommentPanel(current)) {
                return new CommentReveal(current, true);
            }
        }
        return new CommentReveal(current, false);
    }

    private boolean isUnknownActionKind(JsonNode node) {
        String code = node.path("code").asText(node.path("status").asText(""));
        String message = node.path("message").asText("");
        return "UNKNOWN_KIND".equalsIgnoreCase(code)
                || message.toLowerCase(Locale.ROOT).contains("unknown kind")
                || message.toLowerCase(Locale.ROOT).contains("no handler registered");
    }

    private ClickPoint douyinVideoFocusPoint(Snapshot current) {
        int viewportW = current.viewportW();
        int viewportH = current.viewportH();
        return parseTreeLines(current.tree()).stream()
                .filter(line -> {
                    String name = line.name();
                    return name.contains("暂停") || name.contains("播放");
                })
                .filter(line -> viewportW <= 0 || line.x() < viewportW * 0.75)
                .min(Comparator.comparingInt(TreeLine::y).reversed())
                .map(line -> new ClickPoint(line.x() + line.w() / 2.0, line.y() + line.h() / 2.0))
                .orElseGet(() -> {
                    if (viewportW >= 1600) {
                        double x = viewportW * 0.45;
                        double y = viewportH > 0 ? viewportH * 0.50 : 420.0;
                        return new ClickPoint(x, y);
                    }
                    return new ClickPoint(420.0, 420.0);
                });
    }

    private List<ClickPoint> commentClickPoints(TreeLine trigger) {
        double centerX = trigger.x() + trigger.w() / 2.0;
        double centerY = trigger.y() + trigger.h() / 2.0;
        List<ClickPoint> points = new ArrayList<>();
        if (isSideActionCommentSurface(trigger) && !"button".equalsIgnoreCase(trigger.role())) {
            addDistinctPoint(points, centerX, Math.max(0, trigger.y() - 44.0));
            addDistinctPoint(points, centerX, Math.max(0, trigger.y() - 28.0));
        }
        addDistinctPoint(points, centerX, centerY);
        if (isSideActionCommentSurface(trigger)) {
            addDistinctPoint(points, centerX, Math.max(0, centerY - 34.0));
        }
        return points;
    }

    private boolean isSideActionCommentSurface(TreeLine line) {
        return line.x() >= 500 && line.y() >= 140 && line.w() <= 220 && line.h() <= 110;
    }

    private void addDistinctPoint(List<ClickPoint> points, double x, double y) {
        boolean exists = points.stream()
                .anyMatch(point -> Math.abs(point.x() - x) < 1.0 && Math.abs(point.y() - y) < 1.0);
        if (!exists) {
            points.add(new ClickPoint(x, y));
        }
    }

    private CommentReveal clickCommentsByVisibleText(Snapshot current,
                                                     List<Map<String, Object>> attempts,
                                                     ToolContext ctx) {
        for (String role : List.of("button", "link", "tab", "generic")) {
            JsonNode textClick = callBrowser("fallback_click_comments_by_visible_text_" + role, attempts,
                    () -> browser.extension_browser_click("评论", role, null, ctx));
            if (!ok(textClick)) {
                continue;
            }
            localWait("wait_after_text_comments_click_" + role, attempts, PAGE_SETTLE_DELAY_MS);
            ObserveResult observed = observe("observe_comments_after_text_click_" + role, attempts, ctx);
            if (observed.ok()) {
                current = observed.snapshot();
            }
            if (hasVisibleCommentPanel(current)) {
                return new CommentReveal(current, true);
            }
        }
        return new CommentReveal(current, false);
    }

    private SortRun selectOptionViaVisibleTriggerText(String siteName,
                                                      List<String> triggerLabels,
                                                      List<String> optionLabels,
                                                      Snapshot current,
                                                      List<Map<String, Object>> attempts,
                                                      ToolContext ctx) {
        String lastMessage = "";
        for (String label : triggerLabels) {
            JsonNode hover = callBrowser("fallback_hover_filter_trigger_by_visible_text", attempts,
                    () -> browser.extension_browser_hover(label, "button", null, ctx));
            if (ok(hover)) {
                localWait("wait_after_text_filter_hover", attempts, FILTER_PANEL_SETTLE_DELAY_MS);
                ObserveResult panel = observe("observe_filter_panel_after_text_hover", attempts, ctx);
                if (!panel.ok()) {
                    return sortFail("observe_filter_panel_after_text_hover", panel.raw(), attempts);
                }
                current = panel.snapshot();
                SortRun terminal = terminalSortState(siteName, current, optionLabels, attempts);
                if (terminal != null) {
                    return terminal;
                }
                SortSelection option = bestOption(current, optionLabels);
                if (option != null) {
                    return clickObservedOption(siteName, optionLabels, option, attempts, ctx,
                            "click_filter_option_after_text_hover",
                            "wait_after_text_hover_sort_select",
                            "verify_text_hover_sort_select");
                }
                if (filterPanelLooksOpen(current, optionLabels) || hoverClickToggleSensitiveSite(siteName)) {
                    return clickOptionByVisibleText(siteName, optionLabels, current, attempts, ctx,
                            "fallback_click_option_after_text_trigger_hover",
                            "wait_after_text_trigger_hover_option_select",
                            "verify_text_trigger_hover_option_select");
                }
                lastMessage = "hover succeeded, but the filter panel/options were not observed";
            } else {
                lastMessage = hover.path("message").asText(hover.path("code").asText("hover failed"));
            }

            JsonNode click = callBrowser("fallback_click_filter_trigger_by_visible_text", attempts,
                    () -> browser.extension_browser_click(label, "button", null, ctx));
            if (!ok(click)) {
                lastMessage = click.path("message").asText(click.path("code").asText("click failed"));
                continue;
            }

            localWait("wait_after_text_filter_click", attempts, FILTER_PANEL_SETTLE_DELAY_MS);
            ObserveResult panel = observe("observe_filter_panel_after_text_click", attempts, ctx);
            if (!panel.ok()) {
                return sortFail("observe_filter_panel_after_text_click", panel.raw(), attempts);
            }
            current = panel.snapshot();
            SortRun terminal = terminalSortState(siteName, current, optionLabels, attempts);
            if (terminal != null) {
                return terminal;
            }
            SortSelection option = bestOption(current, optionLabels);
            if (option != null) {
                return clickObservedOption(siteName, optionLabels, option, attempts, ctx,
                        "click_filter_option_after_text_click",
                        "wait_after_text_click_sort_select",
                        "verify_text_click_sort_select");
            }
            return clickOptionByVisibleText(siteName, optionLabels, current, attempts, ctx,
                    "fallback_click_option_after_text_trigger_click",
                    "wait_after_text_trigger_click_option_select",
                    "verify_text_trigger_click_option_select");
        }

        return new SortRun(false, "FILTER_TRIGGER_NOT_FOUND",
                "已完成 " + siteName + " 搜索，但没有在结果页观察到「"
                        + String.join("/", triggerLabels) + "」触发按钮；按可见文字悬停/点击兜底也未定位成功"
                        + (lastMessage.isBlank() ? "。" : "：" + lastMessage),
                current, "", attempts);
    }

    @Nullable
    private SortRun terminalSortState(String siteName,
                                      Snapshot current,
                                      List<String> optionLabels,
                                      List<Map<String, Object>> attempts) {
        if (isLoginWall(current)) {
            return new SortRun(false, "LOGIN_REQUIRED",
                    siteName + " 的筛选排序需要登录。请先在这个浏览器里登录账号，然后重新发起任务。",
                    current, "", attempts);
        }
        SortSelection selected = selectedOption(current, optionLabels);
        if (selected != null) {
            return new SortRun(true, "DONE", "", current, selected.label(), attempts);
        }
        return null;
    }

    private SortRun clickObservedOption(String siteName,
                                        List<String> optionLabels,
                                        SortSelection option,
                                        List<Map<String, Object>> attempts,
                                        ToolContext ctx,
                                        String clickStep,
                                        String waitStep,
                                        String verifyStep) {
        JsonNode select = clickAtLine(clickStep, option.line(), attempts, ctx);
        if (!ok(select)) {
            return sortFail(clickStep, select, attempts);
        }
        localWait(waitStep, attempts, PAGE_SETTLE_DELAY_MS);

        ObserveResult afterSelect = observe(verifyStep, attempts, ctx);
        if (!afterSelect.ok()) {
            return sortFail(verifyStep, afterSelect.raw(), attempts);
        }
        Snapshot current = afterSelect.snapshot();
        if (isLoginWall(current)) {
            return new SortRun(false, "LOGIN_REQUIRED",
                    siteName + " 的筛选排序需要登录。请先在这个浏览器里登录账号，然后重新发起任务。",
                    current, "", attempts);
        }

        SortSelection selected = selectedOption(current, optionLabels);
        String selectedLabel = selected == null ? option.label() : selected.label();
        return new SortRun(true, "DONE", "", current, selectedLabel, attempts);
    }

    private SortRun clickOptionByVisibleText(String siteName,
                                             List<String> optionLabels,
                                             Snapshot current,
                                             List<Map<String, Object>> attempts,
                                             ToolContext ctx,
                                             String clickStep,
                                             String waitStep,
                                             String verifyStep) {
        String targetText = optionLabels.getFirst();
        JsonNode textClick = callBrowser(clickStep, attempts,
                () -> browser.extension_browser_click(targetText, "button", null, ctx));
        if (!ok(textClick)) {
            return new SortRun(false, "SORT_OPTION_NOT_FOUND",
                    "已打开/尝试打开 " + siteName + " 筛选控件，但没有观察到「"
                            + targetText + "」选项，"
                            + "按可见文字兜底点击也失败：" + textClick.path("message").asText("unknown"),
                    current, "", attempts);
        }
        localWait(waitStep, attempts, PAGE_SETTLE_DELAY_MS);

        ObserveResult afterTextClick = observe(verifyStep, attempts, ctx);
        if (!afterTextClick.ok()) {
            return sortFail(verifyStep, afterTextClick.raw(), attempts);
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
        return clickAtLine(step, line, BrowserTarget.MAIN, attempts, ctx);
    }

    private JsonNode clickAtLine(String step,
                                 TreeLine line,
                                 BrowserTarget target,
                                 List<Map<String, Object>> attempts,
                                 ToolContext ctx) {
        double x = line.x() + line.w() / 2.0;
        double y = line.y() + line.h() / 2.0;
        return clickAtPoint(step, x, y, target, attempts, ctx);
    }

    private JsonNode clickAtPoint(String step,
                                  double x,
                                  double y,
                                  List<Map<String, Object>> attempts,
                                  ToolContext ctx) {
        return clickAtPoint(step, x, y, BrowserTarget.MAIN, attempts, ctx);
    }

    private JsonNode clickAtPoint(String step,
                                  double x,
                                  double y,
                                  BrowserTarget target,
                                  List<Map<String, Object>> attempts,
                                  ToolContext ctx) {
        return callBrowser(step, attempts, () -> {
            if (target == BrowserTarget.ACTIVE) {
                return browser.extension_browser_click_at_active(x, y, ctx);
            }
            return browser.extension_browser_click_at(x, y, ctx);
        });
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

    private ObserveResult observeActive(String step, List<Map<String, Object>> attempts, ToolContext ctx) {
        JsonNode raw = callBrowser(step, attempts, () -> browser.extension_browser_observe_active("all", ctx));
        if (!ok(raw)) {
            return new ObserveResult(false, raw, Snapshot.empty());
        }
        return new ObserveResult(true, raw, snapshot(raw));
    }

    private ObserveResult observeForTarget(String step,
                                           BrowserTarget target,
                                           List<Map<String, Object>> attempts,
                                           ToolContext ctx) {
        if (target == BrowserTarget.ACTIVE) {
            return observeActive(step + "_active_tab", attempts, ctx);
        }
        return observe(step + "_main_tab", attempts, ctx);
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
        JsonNode viewport = node.path("viewport");
        return new Snapshot(
                node.path("url").asText(""),
                node.path("title").asText(""),
                node.path("tree").asText(""),
                viewport.path("w").asInt(0),
                viewport.path("h").asInt(0));
    }

    private boolean isDouyinSearchDone(String query, Snapshot snap) {
        if (!snap.url().toLowerCase(Locale.ROOT).contains("douyin.com")) {
            return false;
        }
        String lowerUrl = decodeUrl(snap.url()).toLowerCase(Locale.ROOT);
        String lowerTitle = snap.title().toLowerCase(Locale.ROOT);
        String lowerQuery = query.toLowerCase(Locale.ROOT);
        boolean urlMatches = lowerUrl.contains("/search")
                && (lowerUrl.contains(lowerQuery) || lowerUrl.contains(urlEncodeLower(query)));

        String lowerTree = snap.tree().toLowerCase(Locale.ROOT);
        boolean titleMatches = lowerTitle.contains(lowerQuery)
                && (lowerTitle.contains("搜索") || lowerTitle.contains("search"));
        boolean pageMatches = lowerTree.contains(lowerQuery)
                && (lowerUrl.contains("/search") || titleMatches
                || lowerTree.contains("筛选") || lowerTree.contains("排序"))
                && (lowerTree.contains("搜索") || lowerTree.contains("search")
                || lowerTree.contains("综合") || lowerTree.contains("视频")
                || lowerTree.contains("用户"));

        return urlMatches || titleMatches || pageMatches;
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

    private boolean filterPanelLooksOpen(Snapshot snap, List<String> optionLabels) {
        String tree = blankFallback(snap.tree(), "");
        return containsAnyLabel(tree, optionLabels)
                || tree.contains("排序依据")
                || tree.contains("综合排序")
                || tree.contains("最新发布")
                || tree.contains("发布时间")
                || tree.contains("全部时间")
                || tree.contains("一周内")
                || tree.contains("半年内")
                || tree.contains("筛选条件");
    }

    private boolean hoverClickToggleSensitiveSite(String siteName) {
        String normalized = normalizeQuery(siteName).toLowerCase(Locale.ROOT);
        return normalized.contains("抖音") || normalized.contains("douyin");
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

    private boolean isDouyinVideoOpen(Snapshot snap) {
        String lowerUrl = decodeUrl(snap.url()).toLowerCase(Locale.ROOT);
        if (lowerUrl.contains("douyin.com/video/")
                || lowerUrl.contains("/video/")
                || lowerUrl.contains("modal_id=")
                || lowerUrl.contains("aweme_id=")) {
            return true;
        }
        String tree = blankFallback(snap.tree(), "");
        boolean hasVideoActions = tree.contains("评论")
                && (tree.contains("分享") || tree.contains("收藏") || tree.contains("点赞"));
        boolean hasVideoSurface = tree.contains("暂停") || tree.contains("播放")
                || tree.contains("倍速") || tree.contains("全屏")
                || tree.contains("相关视频") || tree.contains("作者");
        boolean stillSearch = lowerUrl.contains("/search")
                && (tree.contains("综合") || tree.contains("筛选") || tree.contains("最多点赞"));
        return hasVideoActions && hasVideoSurface && !stillSearch;
    }

    @Nullable
    private TreeLine firstDouyinVideoResult(Snapshot snap) {
        VideoResultTarget target = firstDouyinVideoTarget(snap);
        return target == null ? null : target.clickLine();
    }

    @Nullable
    private VideoResultTarget firstDouyinVideoTarget(Snapshot snap) {
        List<TreeLine> lines = parseTreeLines(snap.tree());
        return lines.stream()
                .filter(this::isPossibleVideoResult)
                .map(line -> new VideoResultTarget(
                        bestClickableLineForVideoResult(line, lines),
                        line,
                        nearbyLikeCount(line, lines),
                        scoreVideoResult(line, lines)))
                .filter(candidate -> candidate.score() >= 60)
                .max(Comparator.comparingDouble(VideoResultTarget::likeCount)
                        .thenComparingInt(VideoResultTarget::score)
                        .thenComparing(candidate -> -candidate.evidenceLine().y()))
                .orElse(null);
    }

    private boolean isPossibleVideoResult(TreeLine line) {
        String name = line.name();
        if (name.isBlank() || name.length() < 4 || name.length() > 260) {
            return false;
        }
        String role = line.role().toLowerCase(Locale.ROOT);
        if (!(role.equals("article") || role.equals("link") || role.equals("image")
                || role.equals("img") || role.equals("generic") || role.equals("text")
                || role.equals("statictext"))) {
            return false;
        }
        if (line.y() < 110 || line.w() < 60 || line.h() < 16) {
            return false;
        }
        if (isControlText(name) || isNavOnlyText(name)) {
            return false;
        }
        if (role.equals("link") && line.h() <= 36 && name.length() <= 24 && !looksLikePostTitle(name)) {
            return false;
        }
        return true;
    }

    private int scoreVideoResult(TreeLine line, List<TreeLine> lines) {
        String role = line.role().toLowerCase(Locale.ROOT);
        String name = line.name();
        int score = 0;
        if ("article".equals(role)) {
            score += 95;
        } else if ("link".equals(role)) {
            score += 65;
        } else if ("image".equals(role) || "img".equals(role)) {
            score += 55;
        } else {
            score += 35;
        }
        if (line.y() >= 140 && line.y() <= 520) {
            score += 35;
        } else if (line.y() > 520) {
            score += 10;
        }
        if (line.x() >= 160) {
            score += 20;
        } else {
            score -= 30;
        }
        if (line.w() >= 180 || line.h() >= 90) {
            score += 25;
        }
        if (looksLikePostTitle(name)) {
            score += 25;
        }
        if (name.contains("点赞") || name.contains("评论") || name.contains("分享")) {
            score += 8;
        }
        if ("image".equals(role) || "img".equals(role)) {
            score += 20;
            if (line.w() >= 120 && line.h() >= 120) {
                score += 25;
            }
        }
        double likeCount = nearbyLikeCount(line, lines);
        if (likeCount > 0) {
            score += 40;
            if (likeCount >= 100_000) {
                score += 35;
            }
            if (likeCount >= 500_000) {
                score += 35;
            }
            if (name.contains("55.9") || name.contains("559")) {
                score += 20;
            }
        }
        return score;
    }

    private TreeLine bestClickableLineForVideoResult(TreeLine evidence, List<TreeLine> lines) {
        return lines.stream()
                .filter(line -> sameVideoResultCard(evidence, line))
                .filter(line -> isVideoCardClickableSurface(line, evidence))
                .max(Comparator.comparingInt(line -> scoreVideoClickSurface(line, evidence)))
                .orElse(evidence);
    }

    private boolean isVideoCardClickableSurface(TreeLine line, TreeLine evidence) {
        String role = line.role().toLowerCase(Locale.ROOT);
        if (!(role.equals("article") || role.equals("link") || role.equals("image")
                || role.equals("img") || role.equals("generic") || role.equals("text")
                || role.equals("statictext"))) {
            return false;
        }
        if (line.w() < 40 || line.h() < 16) {
            return false;
        }
        String name = line.name();
        if (isControlText(name) || isNavOnlyText(name)) {
            return false;
        }
        return line.y() >= Math.max(100, evidence.y() - 120)
                && line.y() <= evidence.y() + 180;
    }

    private int scoreVideoClickSurface(TreeLine line, TreeLine evidence) {
        String role = line.role().toLowerCase(Locale.ROOT);
        int score = 0;
        if ("image".equals(role) || "img".equals(role)) {
            score += 110;
        } else if ("article".equals(role) || "link".equals(role)) {
            score += 80;
        } else {
            score += 35;
        }
        if (line.w() >= 120 && line.h() >= 120) {
            score += 50;
        }
        String name = line.name();
        if (name.contains("点赞") || name.contains("评论") || name.contains("分享")) {
            score -= 55;
        }
        if (isLikelyAuthorOrProfileClickSurface(line, evidence)) {
            score -= 140;
        }
        int dy = Math.abs(line.y() - evidence.y());
        score += Math.max(0, 50 - dy / 3);
        int dx = Math.abs(line.x() - evidence.x());
        score += Math.max(0, 30 - dx / 8);
        return score;
    }

    private boolean isLikelyAuthorOrProfileClickSurface(TreeLine line, TreeLine evidence) {
        if (line == evidence) {
            return false;
        }
        String role = line.role().toLowerCase(Locale.ROOT);
        if (!(role.equals("link") || role.equals("button"))) {
            return false;
        }
        String name = line.name().trim();
        if (name.isBlank()) {
            return true;
        }
        if (name.contains("作者") || name.contains("主页") || name.contains("粉丝") || name.contains("获赞")) {
            return true;
        }
        return line.w() <= 180
                && line.h() <= 44
                && name.length() <= 42
                && !looksLikePostTitle(name)
                && parseLikeCount(name) == 0;
    }

    private double nearbyLikeCount(TreeLine line, List<TreeLine> lines) {
        double best = parseLikeCount(line.name());
        for (TreeLine other : lines) {
            if (!sameVideoResultCard(line, other)) {
                continue;
            }
            best = Math.max(best, parseLikeCount(other.name()));
        }
        return best;
    }

    private boolean sameVideoResultCard(TreeLine anchor, TreeLine other) {
        return sameVideoCardColumn(anchor, other) && sameVideoCardBand(anchor, other);
    }

    private boolean sameVideoCardColumn(TreeLine anchor, TreeLine other) {
        int overlap = Math.min(anchor.x() + anchor.w(), other.x() + other.w())
                - Math.max(anchor.x(), other.x());
        if (overlap > 0 && overlap >= Math.min(anchor.w(), other.w()) * 0.45) {
            return true;
        }

        double anchorCenter = anchor.x() + anchor.w() / 2.0;
        double otherCenter = other.x() + other.w() / 2.0;
        double maxCenterDistance = Math.max(80.0, Math.min(220.0, Math.max(anchor.w(), other.w()) * 0.45));
        if (Math.abs(anchorCenter - otherCenter) <= maxCenterDistance) {
            return true;
        }

        return other.x() >= anchor.x() - 35 && other.x() <= anchor.x() + Math.min(anchor.w(), 180) + 35;
    }

    private boolean sameVideoCardBand(TreeLine anchor, TreeLine other) {
        double anchorCenter = anchor.y() + anchor.h() / 2.0;
        double otherCenter = other.y() + other.h() / 2.0;
        if (Math.abs(anchorCenter - otherCenter) > 310) {
            return false;
        }
        return other.y() >= anchor.y() - 145
                && other.y() <= anchor.y() + anchor.h() + 275;
    }

    private double parseLikeCount(String text) {
        if (text == null || text.isBlank()) {
            return 0;
        }
        double best = 0;
        String normalized = normalizeQuery(text);
        Matcher prefixLabel = Pattern.compile("(?i)(?:获赞|点赞|赞)\\s*[:：]?\\s*(\\d+(?:\\.\\d+)?)\\s*([万w])?")
                .matcher(normalized);
        while (prefixLabel.find()) {
            best = Math.max(best, parseLikeValue(prefixLabel.group(1), prefixLabel.group(2)));
        }

        Matcher suffixLabel = Pattern.compile("(?i)(\\d+(?:\\.\\d+)?)\\s*([万w])?\\s*(?:获赞|点赞|赞)")
                .matcher(normalized);
        while (suffixLabel.find()) {
            best = Math.max(best, parseLikeValue(suffixLabel.group(1), suffixLabel.group(2)));
        }

        Matcher compactCount = Pattern.compile("(?i)^\\D{0,3}(\\d+(?:\\.\\d+)?)\\s*([万w])\\D{0,3}$")
                .matcher(normalized);
        if (best == 0 && normalized.length() <= 12 && !normalized.matches(".*[个条次天年月].*")
                && compactCount.find()) {
            best = Math.max(best, parseLikeValue(compactCount.group(1), compactCount.group(2)));
        }
        return best;
    }

    private double parseLikeValue(String rawNumber, @Nullable String unit) {
        try {
            double value = Double.parseDouble(rawNumber);
            if (unit != null && (unit.equalsIgnoreCase("w") || unit.equals("万"))) {
                value *= 10_000;
            }
            return value;
        } catch (NumberFormatException ignored) {
            // Ignore malformed fragments and keep scanning the rest of the line.
            return 0;
        }
    }

    private boolean looksLikePostTitle(String name) {
        return name.length() >= 12
                || name.contains("#")
                || name.contains("？")
                || name.contains("?")
                || name.contains("！")
                || name.contains("!")
                || name.contains("...")
                || name.contains("…");
    }

    private boolean hasCommentContent(Snapshot snap) {
        List<TreeLine> lines = parseTreeLines(snap.tree());
        if (hasVisibleCommentPanelHeader(lines, snap) || hasVisibleCommentInput(lines, snap)) {
            return true;
        }
        return visiblePanelCommentMatches(snap).stream()
                .anyMatch(match -> commentAuthorNearMatch(snap.tree(), match.comment()) != null);
    }

    private boolean hasVisibleCommentPanel(Snapshot snap) {
        List<TreeLine> lines = parseTreeLines(snap.tree());
        if (hasVisibleCommentPanelHeader(lines, snap)) {
            return true;
        }

        if (hasVisibleCommentInput(lines, snap)) {
            return true;
        }

        if (hasVisibleDouyinCommentTabPanel(lines, snap) && !visiblePanelCommentMatches(snap).isEmpty()) {
            return true;
        }

        // In Douyin search-modal pages the accessibility tree contains both the
        // opened video and the background search grid. Those grid cards also
        // look like "author + long text" pairs, so row-only evidence would
        // falsely mark the comments panel as open before X/K is pressed.
        if (containsDouyinSearchResultContext(snap)) {
            return false;
        }

        return visiblePanelCommentMatches(snap).stream()
                .anyMatch(match -> commentAuthorNearMatch(snap.tree(), match.comment()) != null);
    }

    private boolean hasVisibleCommentPanelHeader(List<TreeLine> lines, Snapshot snap) {
        int viewportH = viewportH(snap);
        int panelMinX = commentPanelMinX(snap);
        return lines.stream()
                .anyMatch(line -> line.x() >= panelMinX
                        && isVisibleInViewport(line, snap)
                        && line.y() <= Math.max(260, viewportH * 0.45)
                        && isCommentPanelHeader(line.name()));
    }

    private boolean hasVisibleCommentInput(List<TreeLine> lines, Snapshot snap) {
        int panelMinX = commentPanelMinX(snap);
        return lines.stream()
                .anyMatch(line -> line.x() >= panelMinX
                        && isVisibleInViewport(line, snap)
                        && line.name().contains("评论")
                        && (line.name().contains("说点什么")
                        || line.name().contains("写评论")
                        || line.name().contains("发表评论")
                        || line.name().contains("善意")
                        || line.name().contains("友好")));
    }

    private boolean hasVisibleDouyinCommentTabPanel(List<TreeLine> lines, Snapshot snap) {
        int panelMinX = commentPanelMinX(snap);
        boolean detailsTab = false;
        boolean worksTab = false;
        boolean commentTab = false;
        boolean askAiTab = false;
        for (TreeLine line : lines) {
            if (line.x() < panelMinX || !isVisibleInViewport(line, snap)
                    || line.y() > Math.max(220, viewportH(snap) * 0.42)) {
                continue;
            }
            String name = normalizeQuery(line.name());
            detailsTab = detailsTab || name.equals("详情");
            worksTab = worksTab || name.equals("TA的作品") || name.equals("ta的作品");
            commentTab = commentTab || name.equals("评论");
            askAiTab = askAiTab || name.equals("问AI") || name.equals("问ai");
        }
        return commentTab && (detailsTab || worksTab || askAiTab);
    }

    private List<CommentMatch> visiblePanelCommentMatches(Snapshot snap) {
        int panelMinX = commentPanelMinX(snap);
        return commentMatches(snap, "").stream()
                .filter(match -> match.comment().x() >= panelMinX)
                .filter(match -> isVisibleInViewport(match.comment(), snap))
                .toList();
    }

    private boolean containsDouyinSearchResultContext(Snapshot snap) {
        String lowerUrl = decodeUrl(snap.url()).toLowerCase(Locale.ROOT);
        String tree = snap.tree();
        return lowerUrl.contains("/search/")
                || lowerUrl.contains("/jingxuan/search/")
                || tree.contains("为你找到以下结果")
                || tree.contains("筛选")
                || tree.contains("综合")
                || tree.contains("相关搜索");
    }

    private int viewportW(Snapshot snap) {
        return snap.viewportW() > 0 ? snap.viewportW() : 1280;
    }

    private int viewportH(Snapshot snap) {
        return snap.viewportH() > 0 ? snap.viewportH() : 800;
    }

    private int commentPanelMinX(Snapshot snap) {
        return Math.max(520, (int) Math.round(viewportW(snap) * 0.46));
    }

    private boolean isVisibleInViewport(TreeLine line, Snapshot snap) {
        int viewportW = viewportW(snap);
        int viewportH = viewportH(snap);
        return line.x() + line.w() > 0
                && line.y() + line.h() > 0
                && line.x() < viewportW
                && line.y() < viewportH;
    }

    @Nullable
    private TreeLine bestCommentTrigger(String tree) {
        List<TreeLine> lines = parseTreeLines(tree);
        TreeLine explicit = lines.stream()
                .filter(line -> line.name().contains("评论"))
                .filter(line -> !isCommentPanelHeader(line.name()))
                .filter(line -> !isLikelyCommentText(line))
                .map(line -> new CommentTriggerCandidate(line, scoreCommentTrigger(line)))
                .filter(candidate -> candidate.score() >= 35)
                .max(Comparator.comparingInt(CommentTriggerCandidate::score))
                .map(CommentTriggerCandidate::line)
                .orElse(null);
        return explicit == null ? inferredCommentTriggerFromActionBar(lines) : explicit;
    }

    @Nullable
    private TreeLine inferredCommentTriggerFromActionBar(List<TreeLine> lines) {
        List<TreeLine> actionSignals = lines.stream()
                .filter(this::isInteractionActionSignal)
                .toList();
        if (actionSignals.isEmpty()) {
            return null;
        }

        double actionX = actionSignals.stream()
                .mapToDouble(line -> line.x() + line.w() / 2.0)
                .max()
                .orElse(0);
        List<TreeLine> cluster = lines.stream()
                .filter(line -> Math.abs((line.x() + line.w() / 2.0) - actionX) <= 150)
                .filter(line -> line.y() >= 140 && line.w() <= 260 && line.h() <= 120)
                .toList();

        TreeLine like = firstActionLine(cluster, "点赞", "赞");
        TreeLine collect = firstActionLine(cluster, "收藏");
        TreeLine share = firstActionLine(cluster, "分享");
        if (like == null && collect == null && share == null) {
            return null;
        }

        TreeLine numericBetween = cluster.stream()
                .filter(line -> line.name().matches("^[\\d.]+\\s*([万wW])?$"))
                .filter(line -> like == null || line.y() > like.y())
                .filter(line -> collect == null || line.y() < collect.y())
                .min(Comparator.comparingInt(TreeLine::y))
                .orElse(null);
        if (numericBetween != null) {
            return new TreeLine(numericBetween.role(), "评论 " + numericBetween.name(),
                    numericBetween.x(), numericBetween.y(), numericBetween.w(), numericBetween.h());
        }

        double y;
        if (like != null && collect != null) {
            y = ((like.y() + like.h() / 2.0) + (collect.y() + collect.h() / 2.0)) / 2.0;
        } else if (like != null && share != null) {
            y = (like.y() + like.h() / 2.0)
                    + ((share.y() + share.h() / 2.0) - (like.y() + like.h() / 2.0)) / 3.0;
        } else {
            return null;
        }
        return new TreeLine("button", "评论",
                (int) Math.round(actionX - 36), (int) Math.round(y - 24), 72, 48);
    }

    private boolean isInteractionActionSignal(TreeLine line) {
        String name = line.name();
        return line.x() >= 500
                && line.y() >= 140
                && line.w() <= 260
                && line.h() <= 120
                && (name.contains("点赞") || name.contains("收藏") || name.contains("分享")
                || name.matches("^[\\d.]+\\s*([万wW])?$"));
    }

    @Nullable
    private TreeLine firstActionLine(List<TreeLine> lines, String... labels) {
        return lines.stream()
                .filter(line -> {
                    String name = line.name();
                    for (String label : labels) {
                        if (name.contains(label)) {
                            return true;
                        }
                    }
                    return false;
                })
                .min(Comparator.comparingInt(TreeLine::y))
                .orElse(null);
    }

    private int scoreCommentTrigger(TreeLine line) {
        String role = line.role().toLowerCase(Locale.ROOT);
        String name = line.name();
        int score = 0;
        if ("button".equals(role)) {
            score += 80;
        } else if ("link".equals(role) || "tab".equals(role)) {
            score += 50;
        } else {
            score += 25;
        }
        if ("评论".equals(name) || name.matches("评论\\s*\\d*")) {
            score += 45;
        } else if (name.contains("查看评论") || name.contains("全部评论")) {
            score += 35;
        }
        if (line.x() >= 500 || line.y() >= 160) {
            score += 15;
        }
        if (line.w() <= 180 && line.h() <= 80) {
            score += 10;
        }
        return score;
    }

    private boolean isCommentPanelHeader(String name) {
        String trimmed = normalizeQuery(name);
        return trimmed.matches("^全部评论\\s*\\d*$")
                || trimmed.matches("^\\d+\\s*条评论$")
                || trimmed.equals("评论区");
    }

    private List<CommentMatch> commentMatches(Snapshot snap, String targetComment) {
        return parseTreeLines(snap.tree()).stream()
                .filter(this::isLikelyCommentText)
                .filter(line -> isLikelyDouyinCommentColumn(line)
                        || commentAuthorNearMatch(snap.tree(), line) != null)
                .map(line -> new CommentMatch(line, scoreCommentSimilarity(targetComment, line.name())))
                .sorted(Comparator.comparingInt(CommentMatch::score).reversed()
                        .thenComparing(match -> match.comment().y()))
                .toList();
    }

    private boolean isLikelyDouyinCommentColumn(TreeLine line) {
        return line.x() >= 520
                || (line.x() >= 360 && line.y() >= 120 && line.w() <= 760);
    }

    private boolean isLikelyCommentText(TreeLine line) {
        String role = line.role().toLowerCase(Locale.ROOT);
        if (!(role.equals("text") || role.equals("statictext") || role.equals("generic")
                || role.equals("paragraph") || role.equals("link"))) {
            return false;
        }
        String name = line.name().trim();
        if (name.length() < 4 || name.length() > 260) {
            return false;
        }
        if (isControlText(name) || isNavOnlyText(name)) {
            return false;
        }
        if (isAvatarOrAccessoryText(name)) {
            return false;
        }
        if (name.matches("^[\\d.,万wW]+$") || name.matches("^[\\d.,万wW]+\\s*(赞|评论|回复|分享)$")) {
            return false;
        }
        if (name.contains("全部评论") || name.contains("条评论") || name.contains("暂无评论")) {
            return false;
        }
        if (name.length() <= 18 && !containsSentenceSignal(name)) {
            return false;
        }
        return true;
    }

    private boolean isAvatarOrAccessoryText(String name) {
        String trimmed = normalizeQuery(name);
        return trimmed.endsWith("头像")
                || trimmed.contains("作者头像")
                || trimmed.contains("用户头像")
                || trimmed.contains("的头像")
                || trimmed.matches("^.+头像\\s*$");
    }

    private int scoreCommentSimilarity(String targetComment, String candidateComment) {
        String target = normalizeForSimilarity(targetComment);
        String candidate = normalizeForSimilarity(candidateComment);
        if (target.isBlank() || candidate.isBlank()) {
            return 1;
        }
        if (candidate.contains(target) || target.contains(candidate)) {
            return 100;
        }

        Set<String> targetBigrams = charNgrams(target, 2);
        Set<String> candidateBigrams = charNgrams(candidate, 2);
        int commonBigrams = intersectionSize(targetBigrams, candidateBigrams);
        int score = targetBigrams.isEmpty()
                ? 0
                : (int) Math.round(commonBigrams * 50.0 / targetBigrams.size());

        Set<String> targetConcepts = semanticConcepts(targetComment);
        Set<String> candidateConcepts = semanticConcepts(candidateComment);
        score += intersectionSize(targetConcepts, candidateConcepts) * 18;

        Set<String> targetTerms = importantTerms(targetComment);
        Set<String> candidateTerms = importantTerms(candidateComment);
        score += intersectionSize(targetTerms, candidateTerms) * 8;

        return clamp(score, 1, 100);
    }

    private String normalizeForSimilarity(String text) {
        return blankFallback(text, "")
                .toLowerCase(Locale.ROOT)
                .replaceAll("[\\s`'\"“”‘’《》<>\\[\\]()（）【】{}，,。.!！?？:：;；、/\\\\|-]", "");
    }

    private Set<String> charNgrams(String text, int n) {
        if (text.length() < n) {
            return text.isBlank() ? Set.of() : Set.of(text);
        }
        Set<String> grams = new HashSet<>();
        for (int i = 0; i <= text.length() - n; i++) {
            grams.add(text.substring(i, i + n));
        }
        return grams;
    }

    private Set<String> semanticConcepts(String text) {
        String hay = normalizeForSimilarity(text);
        Set<String> concepts = new HashSet<>();
        addConceptIfAny(concepts, hay, "elderly",
                List.of("老年人", "老人", "老年", "长辈", "中老年", "爸妈", "父母"));
        addConceptIfAny(concepts, hay, "smartphone",
                List.of("智能手机", "手机", "app", "应用", "软件"));
        addConceptIfAny(concepts, hay, "cannot_use",
                List.of("玩不懂", "不会用", "不会玩", "用不懂", "用不明白", "看不懂", "搞不懂",
                        "弄不懂", "学不会", "整不明白", "操作不了"));
        addConceptIfAny(concepts, hay, "powerless",
                List.of("无力感", "无力", "无助", "无奈", "挫败", "崩溃", "吃力"));
        return concepts;
    }

    private void addConceptIfAny(Set<String> concepts, String hay, String concept, List<String> labels) {
        if (labels.stream().anyMatch(hay::contains)) {
            concepts.add(concept);
        }
    }

    private Set<String> importantTerms(String text) {
        String normalized = normalizeForSimilarity(text);
        Set<String> terms = new HashSet<>();
        for (String term : List.of("老年人", "老人", "智能手机", "手机", "玩不懂", "不会用",
                "看不懂", "搞不懂", "无力感", "无助", "无奈")) {
            if (normalized.contains(normalizeForSimilarity(term))) {
                terms.add(term);
            }
        }
        return terms;
    }

    private int intersectionSize(Set<String> left, Set<String> right) {
        if (left.isEmpty() || right.isEmpty()) {
            return 0;
        }
        int count = 0;
        for (String value : left) {
            if (right.contains(value)) {
                count += 1;
            }
        }
        return count;
    }

    private boolean containsSentenceSignal(String name) {
        return name.contains("，") || name.contains(",") || name.contains("。")
                || name.contains("！") || name.contains("!") || name.contains("？")
                || name.contains("?") || name.contains("像") || name.contains("感觉")
                || name.contains("一样") || name.contains("就是") || name.contains("真的")
                || name.contains("不会") || name.contains("不懂");
    }

    @Nullable
    private TreeLine commentAuthorNearMatch(String tree, TreeLine comment) {
        List<TreeLine> lines = parseTreeLines(tree);
        TreeLine author = lines.stream()
                .filter(line -> isLikelyCommentAuthor(line, comment))
                .map(line -> new CommentAuthorCandidate(line, scoreCommentAuthor(line, comment)))
                .filter(candidate -> candidate.score() >= 45)
                .max(Comparator.comparingInt(CommentAuthorCandidate::score))
                .map(CommentAuthorCandidate::line)
                .orElse(null);
        if (author != null) {
            return author;
        }
        if ("link".equalsIgnoreCase(comment.role())) {
            return comment;
        }
        return null;
    }

    private boolean isLikelyCommentAuthor(TreeLine line, TreeLine comment) {
        String role = line.role().toLowerCase(Locale.ROOT);
        if (!(role.equals("link") || role.equals("button") || role.equals("text")
                || role.equals("statictext") || role.equals("generic"))) {
            return false;
        }
        String name = line.name().trim();
        if (name.isBlank() || name.length() > 42 || name.equals(comment.name())) {
            return false;
        }
        if (isControlText(name) || isNavOnlyText(name) || isLikelyCommentText(line)) {
            return false;
        }
        int verticalDistance = Math.abs(line.y() - comment.y());
        boolean nearAbove = line.y() <= comment.y() && comment.y() - line.y() <= 110;
        boolean nearBelow = line.y() > comment.y() && verticalDistance <= 45;
        boolean xAligned = Math.abs(line.x() - comment.x()) <= 180
                || (line.x() <= comment.x() && comment.x() - line.x() <= 260);
        return (nearAbove || nearBelow) && xAligned;
    }

    private int scoreCommentAuthor(TreeLine line, TreeLine comment) {
        String role = line.role().toLowerCase(Locale.ROOT);
        int score = 0;
        if ("link".equals(role)) {
            score += 90;
        } else if ("button".equals(role)) {
            score += 45;
        } else {
            score += 25;
        }
        int dy = Math.abs(line.y() - comment.y());
        if (line.y() <= comment.y()) {
            score += Math.max(0, 45 - dy / 2);
        } else {
            score += Math.max(0, 20 - dy / 3);
        }
        score += Math.max(0, 30 - Math.abs(line.x() - comment.x()) / 10);
        if (line.name().length() >= 2 && line.name().length() <= 24) {
            score += 20;
        }
        return score;
    }

    private ProfileOpenResult observeProfileAfterAuthorClick(Snapshot previous,
                                                             String stepPrefix,
                                                             List<Map<String, Object>> attempts,
                                                             ToolContext ctx) {
        Snapshot fallback = previous;
        for (int i = 0; i < PROFILE_OPEN_OBSERVE_ATTEMPTS; i++) {
            String suffix = i == 0 ? "" : "_retry_" + i;
            ObserveResult active = observeActive(stepPrefix + "_active_tab" + suffix, attempts, ctx);
            if (active.ok()) {
                fallback = active.snapshot();
                if (profileLooksOpen(active.snapshot(), previous)) {
                    return new ProfileOpenResult(true, active.snapshot(), BrowserTarget.ACTIVE);
                }
            }

            ObserveResult main = observe(stepPrefix + "_main_tab" + suffix, attempts, ctx);
            if (main.ok()) {
                fallback = main.snapshot();
                if (profileLooksOpen(main.snapshot(), previous)) {
                    return new ProfileOpenResult(true, main.snapshot(), BrowserTarget.MAIN);
                }
            }

            if (i < PROFILE_OPEN_OBSERVE_ATTEMPTS - 1) {
                localWait(stepPrefix + "_wait_for_profile_tab_" + (i + 1), attempts,
                        FILTER_PANEL_SETTLE_DELAY_MS);
            }
        }
        return new ProfileOpenResult(false, fallback, BrowserTarget.MAIN);
    }

    private boolean profileLooksOpen(Snapshot snap) {
        return profileLooksOpen(snap, null);
    }

    private boolean profileLooksOpen(Snapshot snap, @Nullable Snapshot previous) {
        String lowerUrl = decodeUrl(snap.url()).toLowerCase(Locale.ROOT);
        if (lowerUrl.contains("douyin.com/user/") || lowerUrl.contains("/user/")) {
            return true;
        }
        if (previous != null && samePageLocation(previous, snap)) {
            return false;
        }
        if (containsDouyinSearchResultContext(snap) || hasVisibleCommentPanel(snap)) {
            return false;
        }
        String tree = snap.tree();
        return hasProfileActionButton(snap, "关注", "已关注", "互相关注")
                && parseTreeLines(tree).stream()
                .map(TreeLine::name)
                .anyMatch(name -> name.contains("粉丝") || name.contains("获赞"));
    }

    private boolean samePageLocation(Snapshot left, Snapshot right) {
        String leftUrl = decodeUrl(left.url()).trim();
        String rightUrl = decodeUrl(right.url()).trim();
        return !leftUrl.isBlank() && leftUrl.equals(rightUrl);
    }

    private boolean hasProfileActionButton(Snapshot snap, String... labels) {
        return bestProfileAction(snap, List.of(labels)) != null;
    }

    private FollowDmResult clickFollowAndOpenDm(Snapshot current,
                                                BrowserTarget target,
                                                List<Map<String, Object>> attempts,
                                                ToolContext ctx) {
        boolean followClicked = false;
        TreeLine follow = bestProfileAction(current, List.of("关注"));
        if (follow != null && !follow.name().contains("已关注") && !follow.name().contains("互相关注")) {
            JsonNode followClick = clickAtLine("click_profile_follow", follow, target, attempts, ctx);
            if (ok(followClick)) {
                followClicked = true;
                localWait("wait_after_profile_follow_click", attempts, PAGE_SETTLE_DELAY_MS);
                ObserveResult afterFollow = observeForTarget("observe_profile_after_follow", target, attempts, ctx);
                if (afterFollow.ok()) {
                    current = afterFollow.snapshot();
                }
            }
        } else if (profileAlreadyFollowed(current)) {
            followClicked = true;
        }

        TreeLine dm = bestProfileAction(current, List.of("私信", "发私信", "消息"));
        if (dm == null) {
            return new FollowDmResult(current, followClicked, false);
        }

        JsonNode dmClick = clickAtLine("click_profile_private_message", dm, target, attempts, ctx);
        if (!ok(dmClick)) {
            return new FollowDmResult(current, followClicked, false);
        }
        localWait("wait_after_profile_private_message_click", attempts, PAGE_SETTLE_DELAY_MS);
        ObserveResult afterDm = observeForTarget("observe_profile_after_private_message", target, attempts, ctx);
        if (afterDm.ok()) {
            current = afterDm.snapshot();
        }
        return new FollowDmResult(current, followClicked, dmLooksOpen(current));
    }

    @Nullable
    private TreeLine bestProfileAction(Snapshot snap, List<String> labels) {
        return parseTreeLines(snap.tree()).stream()
                .filter(line -> isVisibleInViewport(line, snap))
                .filter(line -> isProfileActionCandidate(line, labels))
                .max(Comparator.comparingInt(line -> scoreProfileAction(line, labels)))
                .orElse(null);
    }

    private boolean isProfileActionCandidate(TreeLine line, List<String> labels) {
        String role = line.role().toLowerCase(Locale.ROOT);
        if (!(role.equals("button") || role.equals("link") || role.equals("generic") || role.equals("text"))) {
            return false;
        }
        String name = normalizeQuery(line.name());
        if (name.isBlank() || name.length() > 24) {
            return false;
        }
        boolean hasLabel = labels.stream().anyMatch(name::contains);
        if (!hasLabel) {
            return false;
        }
        if (name.contains("粉丝") || name.contains("获赞") || name.contains("作品")
                || name.contains("评论") || name.contains("通知")) {
            return false;
        }
        return line.w() >= 24 && line.h() >= 16;
    }

    private int scoreProfileAction(TreeLine line, List<String> labels) {
        String role = line.role().toLowerCase(Locale.ROOT);
        String name = normalizeQuery(line.name());
        int score = 0;
        if ("button".equals(role)) {
            score += 80;
        } else if ("link".equals(role)) {
            score += 45;
        } else {
            score += 20;
        }
        if (labels.stream().anyMatch(label -> name.equals(label))) {
            score += 45;
        }
        if (line.y() >= 80 && line.y() <= 360) {
            score += 25;
        }
        if (line.w() >= 50 && line.w() <= 180 && line.h() <= 60) {
            score += 15;
        }
        return score;
    }

    private boolean profileAlreadyFollowed(Snapshot snap) {
        String tree = snap.tree();
        return tree.contains("已关注") || tree.contains("互相关注");
    }

    private boolean dmLooksOpen(Snapshot snap) {
        String lowerUrl = decodeUrl(snap.url()).toLowerCase(Locale.ROOT);
        List<TreeLine> lines = parseTreeLines(snap.tree());
        String tree = snap.tree();
        return lowerUrl.contains("/im/")
                || lowerUrl.contains("conversation")
                || lowerUrl.contains("chat")
                || lines.stream().anyMatch(line -> {
                    String role = line.role().toLowerCase(Locale.ROOT);
                    String name = normalizeQuery(line.name());
                    return (role.equals("textbox") || role.equals("textarea") || role.equals("input"))
                            && (name.contains("输入消息") || name.contains("说点什么") || name.contains("按Enter发送")
                            || name.contains("按 enter 发送"));
                })
                || lines.stream().anyMatch(line -> {
                    String role = line.role().toLowerCase(Locale.ROOT);
                    String name = normalizeQuery(line.name());
                    return role.equals("button") && name.equals("发送");
                })
                || tree.contains("按 Enter 发送");
    }

    private void appendCandidateComments(List<String> out, List<CommentMatch> matches, int limit) {
        for (CommentMatch match : matches) {
            String text = match.comment().name();
            if (out.stream().noneMatch(existing -> existing.contains(text))) {
                out.add("score=" + match.score() + " " + text);
            }
            if (out.size() >= limit) {
                return;
            }
        }
    }

    private CommentLeadRun commentLeadFail(String status,
                                           String message,
                                           Snapshot snap,
                                           @Nullable CommentMatch match,
                                           @Nullable TreeLine author,
                                           String profileUrl,
                                           List<String> candidateComments,
                                           List<Map<String, Object>> attempts) {
        return new CommentLeadRun(false, status, message, snap, match, author, profileUrl,
                List.copyOf(candidateComments), attempts);
    }

    private FirstCommentEngagementRun firstCommentEngagementFail(String status,
                                                                 String message,
                                                                 Snapshot snap,
                                                                 @Nullable CommentMatch firstComment,
                                                                 @Nullable TreeLine author,
                                                                 String profileUrl,
                                                                 boolean followClicked,
                                                                 boolean dmOpened,
                                                                 List<String> candidateComments,
                                                                 List<Map<String, Object>> attempts) {
        return new FirstCommentEngagementRun(false, status, message, snap, firstComment, author, profileUrl,
                followClicked, dmOpened, List.copyOf(candidateComments), attempts);
    }

    private VideoCommentsRun videoCommentsFail(String status,
                                               String message,
                                               Snapshot snap,
                                               List<Map<String, Object>> attempts) {
        return new VideoCommentsRun(false, status, message, snap, attempts);
    }

    private boolean isControlText(String name) {
        String trimmed = normalizeQuery(name);
        if (trimmed.isBlank()) {
            return true;
        }
        return trimmed.matches("^(搜索|筛选|排序|综合|视频|用户|直播|商品|音乐|话题|地点|更多|展开|收起|回复|点赞|分享|收藏|评论|关注|私信|登录)$")
                || trimmed.matches("^\\d+\\s*(赞|评论|回复|分享|收藏)$")
                || trimmed.contains("登录后")
                || trimmed.contains("扫码登录")
                || trimmed.contains("验证码登录");
    }

    private boolean isNavOnlyText(String name) {
        String trimmed = normalizeQuery(name);
        return trimmed.matches("^(首页|推荐|精选|朋友|关注|商城|消息|我|发布视频|创作者服务中心|综合排序|最多点赞|最新发布|发布时间|全部时间|一周内|半年内)$");
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

    private enum BrowserTarget {
        MAIN,
        ACTIVE
    }

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

    private record CommentLeadRun(boolean done,
                                  String status,
                                  String message,
                                  Snapshot snapshot,
                                  @Nullable CommentMatch match,
                                  @Nullable TreeLine author,
                                  String profileUrl,
                                  List<String> candidateComments,
                                  List<Map<String, Object>> attempts) {}

    private record FirstCommentEngagementRun(boolean done,
                                             String status,
                                             String message,
                                             Snapshot snapshot,
                                             @Nullable CommentMatch firstComment,
                                             @Nullable TreeLine author,
                                             String profileUrl,
                                             boolean followClicked,
                                             boolean dmOpened,
                                             List<String> candidateComments,
                                             List<Map<String, Object>> attempts) {}

    private record FollowDmResult(Snapshot snapshot, boolean followClicked, boolean dmOpened) {}

    private record ProfileOpenResult(boolean ok, Snapshot snapshot, BrowserTarget target) {}

    private record VideoCommentsRun(boolean done,
                                    String status,
                                    String message,
                                    Snapshot snapshot,
                                    List<Map<String, Object>> attempts) {}

    private record VideoOpenAttempt(Snapshot snapshot, boolean clicked) {}

    private record CommentReveal(Snapshot snapshot, boolean opened) {}

    private record ClickPoint(double x, double y) {}

    private record Snapshot(String url, String title, String tree, int viewportW, int viewportH) {
        static Snapshot empty() {
            return new Snapshot("", "", "", 0, 0);
        }
    }

    private record TreeLine(String role, String name, int x, int y, int w, int h) {}

    private record SearchCandidate(TreeLine line, int score) {}

    private record SortSelection(TreeLine line, String label, int score) {}

    private record VideoResultCandidate(TreeLine line, int score) {}

    private record VideoResultTarget(TreeLine clickLine,
                                     TreeLine evidenceLine,
                                     double likeCount,
                                     int score) {}

    private record CommentTriggerCandidate(TreeLine line, int score) {}

    private record CommentMatch(TreeLine comment, int score) {}

    private record CommentAuthorCandidate(TreeLine line, int score) {}

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
