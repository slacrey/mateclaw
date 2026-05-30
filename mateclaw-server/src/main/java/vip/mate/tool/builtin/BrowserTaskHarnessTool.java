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
 * Small task-level browser harnesses for workflows where a low-level
 * click/type loop is too easy to repeat. This deliberately sits above
 * {@link ExtensionBrowserTool}: atomic action success is treated as evidence,
 * while task completion is decided by explicit post-action page state.
 */
@Slf4j
@Component
public class BrowserTaskHarnessTool {

    private static final int MAX_FINGERPRINT_REPEATS = 2;
    private static final String DOUYIN_SEARCH_BASE = "https://www.douyin.com/search/";

    private final ExtensionBrowserTool browser;
    private final ObjectMapper mapper;

    public BrowserTaskHarnessTool(ExtensionBrowserTool browser, ObjectMapper mapper) {
        this.browser = browser;
        this.mapper = mapper;
    }

    @Tool(description = """
            Open Douyin in the USER'S OWN visible Chrome window and search for a keyword with a bounded,
            verified task harness. Use this instead of manually looping over extension_browser_navigate,
            observe, click, and type for "打开抖音搜索..." requests. The harness first navigates to
            Douyin's canonical search URL, verifies URL/page state, falls back to the visible search UI
            at most once, and stops as soon as the search-results state is observed.

            Returns JSON:
              { "ok": true, "status": "DONE", "url": "...", "query": "openclaw", "attempts": [...] }
            or:
              { "ok": false, "status": "FAILED|BLOCKED_LOOP|NO_SESSION|...", "message": "...", "attempts": [...] }
            """)
    public String extension_browser_douyin_search(
            @ToolParam(description = "Search keyword. Example: openclaw") String query,
            @Nullable ToolContext ctx) {
        String normalizedQuery = normalizeQuery(query);
        if (normalizedQuery.isBlank()) {
            return json(Map.of(
                    "ok", false,
                    "status", "INVALID_QUERY",
                    "message", "query is required"));
        }

        List<Map<String, Object>> attempts = new ArrayList<>();
        FingerprintGuard guard = new FingerprintGuard();

        String searchUrl = DOUYIN_SEARCH_BASE + URLEncoder.encode(normalizedQuery, StandardCharsets.UTF_8)
                + "?type=general";

        JsonNode directNav = call("navigate_direct", attempts,
                browser.extension_browser_navigate(searchUrl, "load", ctx));
        if (!ok(directNav)) {
            return failFromTool("navigate_direct", directNav, attempts);
        }

        ObserveResult observed = observe("verify_direct_url", attempts, ctx);
        if (!observed.ok()) {
            return failFromTool("verify_direct_url", observed.raw(), attempts);
        }
        if (isDouyinSearchDone(normalizedQuery, observed.snapshot())) {
            return done(normalizedQuery, observed.snapshot(), "direct_url", attempts);
        }
        guard.repeated(observed.snapshot());

        JsonNode wait = call("wait_after_direct_url", attempts,
                browser.extension_browser_wait("time", 800L, null, null, ctx));
        if (!ok(wait)) {
            return failFromTool("wait_after_direct_url", wait, attempts);
        }

        observed = observe("verify_after_direct_wait", attempts, ctx);
        if (!observed.ok()) {
            return failFromTool("verify_after_direct_wait", observed.raw(), attempts);
        }
        if (isDouyinSearchDone(normalizedQuery, observed.snapshot())) {
            return done(normalizedQuery, observed.snapshot(), "direct_url_after_wait", attempts);
        }
        guard.repeated(observed.snapshot());

        String clickTarget = chooseSearchClickTarget(observed.snapshot());
        JsonNode click = call("fallback_click_search_box", attempts,
                browser.extension_browser_click(clickTarget, "textbox", null, ctx));
        if (!ok(click)) {
            click = call("fallback_click_search_button", attempts,
                    browser.extension_browser_click("搜索", "button", null, ctx));
        }
        if (!ok(click)) {
            return failFromTool("fallback_click_search", click, attempts);
        }

        JsonNode type = call("fallback_type_query_and_enter", attempts,
                browser.extension_browser_type(normalizedQuery + "\n", ctx));
        if (!ok(type)) {
            return failFromTool("fallback_type_query_and_enter", type, attempts);
        }

        JsonNode fallbackWait = call("wait_after_fallback_submit", attempts,
                browser.extension_browser_wait("time", 1_000L, null, null, ctx));
        if (!ok(fallbackWait)) {
            return failFromTool("wait_after_fallback_submit", fallbackWait, attempts);
        }

        observed = observe("verify_after_fallback", attempts, ctx);
        if (!observed.ok()) {
            return failFromTool("verify_after_fallback", observed.raw(), attempts);
        }
        if (isDouyinSearchDone(normalizedQuery, observed.snapshot())) {
            return done(normalizedQuery, observed.snapshot(), "ui_fallback", attempts);
        }
        if (guard.repeated(observed.snapshot())) {
            return blockedLoop(normalizedQuery, observed.snapshot(), attempts);
        }

        return json(Map.of(
                "ok", false,
                "status", "FAILED",
                "query", normalizedQuery,
                "url", observed.snapshot().url(),
                "title", observed.snapshot().title(),
                "message", "Douyin search state was not observed after direct URL and one UI fallback.",
                "attempts", attempts));
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
        if (parsed.hasNonNull("code")) {
            entry.put("code", parsed.path("code").asText());
        }
        if (parsed.hasNonNull("status")) {
            entry.put("status", parsed.path("status").asText());
        }
        if (parsed.hasNonNull("url")) {
            entry.put("url", parsed.path("url").asText());
        }
        if (parsed.hasNonNull("title")) {
            entry.put("title", parsed.path("title").asText());
        }
        if (parsed.has("results")) {
            entry.put("results", mapper.convertValue(parsed.get("results"), List.class));
        }
        if (parsed.hasNonNull("message")) {
            entry.put("message", parsed.path("message").asText());
        }
        return entry;
    }

    private Snapshot snapshot(JsonNode node) {
        return new Snapshot(
                node.path("url").asText(""),
                node.path("title").asText(""),
                node.path("tree").asText(""));
    }

    private boolean isDouyinSearchDone(String query, Snapshot snap) {
        if (!isDouyinUrl(snap.url())) {
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

    private boolean isDouyinUrl(String url) {
        String lower = url.toLowerCase(Locale.ROOT);
        return lower.contains("douyin.com");
    }

    private String chooseSearchClickTarget(Snapshot snap) {
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
        return json(Map.of(
                "ok", true,
                "status", "DONE",
                "query", query,
                "via", via,
                "url", snap.url(),
                "title", snap.title(),
                "attempts", attempts));
    }

    private String blockedLoop(String query, Snapshot snap, List<Map<String, Object>> attempts) {
        return json(Map.of(
                "ok", false,
                "status", "BLOCKED_LOOP",
                "query", query,
                "url", snap.url(),
                "title", snap.title(),
                "message", "The browser returned the same verified state repeatedly; stopped instead of retrying the same action loop.",
                "attempts", attempts));
    }

    private String failFromTool(String step, JsonNode raw, List<Map<String, Object>> attempts) {
        String code = raw.path("code").asText(raw.path("status").asText("FAILED"));
        String message = raw.path("message").asText("Browser task step failed: " + step);
        return json(Map.of(
                "ok", false,
                "status", code,
                "failed_step", step,
                "message", message,
                "attempts", attempts));
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
            log.warn("[BrowserTaskHarnessTool] serialise failed: {}", e.getMessage());
            return "{\"ok\":false,\"status\":\"SERIALISE_FAILED\",\"message\":\""
                    + e.getMessage().replace("\"", "\\\"") + "\"}";
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

    private record ObserveResult(boolean ok, JsonNode raw, Snapshot snapshot) {}

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
            return repeats >= MAX_FINGERPRINT_REPEATS;
        }

        private String trimForFingerprint(String tree) {
            String normalized = tree.replaceAll("\\s+", " ").trim();
            return normalized.length() <= 500 ? normalized : normalized.substring(0, 500);
        }
    }
}
