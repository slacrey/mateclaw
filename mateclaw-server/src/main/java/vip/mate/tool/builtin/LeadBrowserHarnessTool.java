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

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

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

    private final ExtensionBrowserTool browser;
    private final ObjectMapper mapper;

    public LeadBrowserHarnessTool(ExtensionBrowserTool browser, ObjectMapper mapper) {
        this.browser = browser;
        this.mapper = mapper;
    }

    @Tool(returnDirect = true, description = """
            Lead-acquisition browser harness: open Douyin in the USER'S OWN visible Chrome window
            and search for a keyword using the real visible search box.

            Use this for lead-generation agents when the user asks to open Douyin and search a
            keyword, for example "打开抖音搜索 openclaw" or "用我的浏览器在抖音搜 openclaw".
            Do not compose generic navigate/observe/click/type loops for this same task.

            This harness starts from https://www.douyin.com/, clicks the visible search box,
            types the keyword, submits it, verifies the search URL/page state, and then stops.
            It never starts by navigating directly to https://www.douyin.com/search/<query>.
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

        JsonNode nav = call("navigate_douyin_home", attempts,
                browser.extension_browser_navigate(DOUYIN_HOME, "load", ctx));
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

        JsonNode click = clickSearchBox(observed.snapshot(), attempts, ctx);
        if (!ok(click)) {
            return failRun("click_search_box", click, attempts);
        }

        JsonNode type = call("type_query_and_enter", attempts,
                browser.extension_browser_type(normalizedQuery + "\n", ctx));
        if (!ok(type)) {
            return failRun("type_query_and_enter", type, attempts);
        }

        JsonNode wait = call("wait_after_enter", attempts,
                browser.extension_browser_wait("time", 1_200L, null, null, ctx));
        if (!ok(wait)) {
            return failRun("wait_after_enter", wait, attempts);
        }

        observed = observe("verify_after_enter", attempts, ctx);
        if (!observed.ok()) {
            return failRun("verify_after_enter", observed.raw(), attempts);
        }
        if (isDouyinSearchDone(normalizedQuery, observed.snapshot())) {
            return doneRun(observed.snapshot(), "homepage_search_box", attempts);
        }
        if (guard.repeated(observed.snapshot())) {
            return blockedRun(observed.snapshot(), attempts);
        }

        JsonNode submitClick = call("fallback_click_search_button", attempts,
                browser.extension_browser_click("搜索", "button", null, ctx));
        if (!ok(submitClick)) {
            return failRun("fallback_click_search_button", submitClick, attempts);
        }

        JsonNode submitWait = call("wait_after_button_submit", attempts,
                browser.extension_browser_wait("time", 1_200L, null, null, ctx));
        if (!ok(submitWait)) {
            return failRun("wait_after_button_submit", submitWait, attempts);
        }

        observed = observe("verify_after_button_submit", attempts, ctx);
        if (!observed.ok()) {
            return failRun("verify_after_button_submit", observed.raw(), attempts);
        }
        if (isDouyinSearchDone(normalizedQuery, observed.snapshot())) {
            return doneRun(observed.snapshot(), "homepage_search_button", attempts);
        }
        if (guard.repeated(observed.snapshot())) {
            return blockedRun(observed.snapshot(), attempts);
        }

        return new SearchRun(false, "FAILED",
                "已从抖音首页输入并提交关键词，但没有观察到对应搜索页，已停止继续重试。",
                observed.snapshot(), "", attempts);
    }

    private JsonNode clickSearchBox(Snapshot snap, List<Map<String, Object>> attempts, ToolContext ctx) {
        String target = chooseSearchTarget(snap);
        JsonNode click = call("click_search_textbox", attempts,
                browser.extension_browser_click(target, "textbox", null, ctx));
        if (ok(click)) {
            return click;
        }
        return call("click_search_input_by_button_label", attempts,
                browser.extension_browser_click("搜索", "button", null, ctx));
    }

    private ObserveResult observe(String step, List<Map<String, Object>> attempts, ToolContext ctx) {
        JsonNode raw = call(step, attempts, browser.extension_browser_observe("all", ctx));
        if (!ok(raw)) {
            return new ObserveResult(false, raw, Snapshot.empty());
        }
        return new ObserveResult(true, raw, snapshot(raw));
    }

    private JsonNode call(String step, List<Map<String, Object>> attempts, String raw) {
        JsonNode parsed = parse(raw);
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

    private String chooseSearchTarget(Snapshot snap) {
        String lowerTree = snap.tree().toLowerCase(Locale.ROOT);
        if (lowerTree.contains("搜索")) {
            return "搜索";
        }
        if (lowerTree.contains("search")) {
            return "search";
        }
        return "搜索";
    }

    private String done(String query, Snapshot snap, String via, List<Map<String, Object>> attempts) {
        return "已完成：获客助手已在你的浏览器中打开抖音首页，通过搜索框输入 `" + query + "` 并提交搜索。\n"
                + "状态：DONE\n"
                + "当前页面：" + blankFallback(snap.url(), "unknown") + "\n"
                + "路径：" + via + "\n"
                + "动作次数：" + attempts.size();
    }

    private SearchRun doneRun(Snapshot snap, String via, List<Map<String, Object>> attempts) {
        return new SearchRun(true, "DONE", "", snap, via, attempts);
    }

    private SearchRun blockedRun(Snapshot snap, List<Map<String, Object>> attempts) {
        return new SearchRun(false, "BLOCKED_LOOP",
                "浏览器连续返回同一个页面状态，已停止，避免重复执行同一组搜索动作。",
                snap, "", attempts);
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

    private String normalizeQuery(String query) {
        return query == null ? "" : query.trim();
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

    private record Snapshot(String url, String title, String tree) {
        static Snapshot empty() {
            return new Snapshot("", "", "");
        }
    }

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
