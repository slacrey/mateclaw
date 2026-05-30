package vip.mate.tool.builtin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.support.ToolCallbacks;
import vip.mate.browser.edge.action.TypePayload;

import java.util.ArrayDeque;
import java.util.List;
import java.util.Queue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LeadBrowserHarnessToolTest {

    private ExtensionBrowserTool browser;
    private ObjectMapper mapper;
    private LeadBrowserHarnessTool tool;

    @BeforeEach
    void setUp() {
        browser = mock(ExtensionBrowserTool.class);
        mapper = new ObjectMapper();
        tool = new LeadBrowserHarnessTool(browser, mapper);
    }

    @Test
    void douyinSearch_usesHomepageInputInsteadOfDirectSearchUrl() throws Exception {
        when(browser.extension_browser_navigate(eq("https://www.douyin.com/"), eq("load"), any()))
                .thenReturn(ok());
        Queue<String> observes = new ArrayDeque<>();
        observes.add(observe(
                "https://www.douyin.com/",
                "抖音",
                "Textbox[ref=ref_1]: 搜索你感兴趣的内容 @{700,5 730x72}"));
        observes.add(observe(
                "https://www.douyin.com/",
                "抖音",
                "Textbox[ref=ref_2]: 搜索你感兴趣的内容 @{700,5 730x72}"));
        observes.add(observe(
                "https://www.douyin.com/search/openclaw?type=general",
                "openclaw - 抖音搜索",
                "Textbox[ref=ref_1]: openclaw @{10,10 200x32}\n"
                        + "Tab[ref=ref_2]: 综合 @{10,60 40x20}\n"
                        + "Article[ref=ref_3]: openclaw 视频 @{10,90 200x120}"));
        when(browser.extension_browser_observe(eq("all"), any()))
                .thenAnswer(ignored -> observes.remove());
        when(browser.extension_browser_click_at(eq(1065.0), eq(41.0), any())).thenReturn(ok());
        when(browser.extension_browser_type_at(eq("openclaw\n"), any(), any())).thenReturn(ok());

        String out = tool.lead_browser_douyin_search("openclaw", null);

        assertThat(out).contains("已完成");
        assertThat(out).contains("状态：DONE");
        assertThat(out).contains("homepage_search_box");
        verify(browser).extension_browser_navigate(eq("https://www.douyin.com/"), eq("load"), any());
        verify(browser, never()).extension_browser_navigate(
                eq("https://www.douyin.com/search/openclaw?type=general"), any(), any());
        verify(browser).extension_browser_click_at(eq(1065.0), eq(41.0), any());
        verify(browser).extension_browser_type_at(eq("openclaw\n"), any(), any());
        verify(browser, never()).extension_browser_type(eq("openclaw\n"), any());
        verify(browser, never()).extension_browser_wait(any(), any(), any(), any(), any());
    }

    @Test
    void douyinSearch_returnsLoginRequired_notBlockedLoop_whenLoginWallAppears() throws Exception {
        // Douyin gates search behind a login modal: after typing + Enter the page
        // shows 登录后即可…/扫码登录 and never reaches a /search page. The tool must
        // surface a clear LOGIN_REQUIRED, NOT the confusing BLOCKED_LOOP.
        when(browser.extension_browser_navigate(eq("https://www.douyin.com/"), eq("load"), any()))
                .thenReturn(ok());
        Queue<String> observes = new ArrayDeque<>();
        observes.add(observe("https://www.douyin.com/", "抖音",
                "Textbox[ref=ref_1]: 搜索你感兴趣的内容 @{700,5 730x72}"));
        observes.add(observe("https://www.douyin.com/", "抖音",
                "Textbox[ref=ref_2]: 搜索你感兴趣的内容 @{700,5 730x72}"));
        observes.add(observe("https://www.douyin.com/jingxuan", "抖音",
                "Dialog[ref=ref_9]: 登录后即可搜索更多精彩视频\n"
                        + "Button[ref=ref_10]: 扫码登录\n"
                        + "Button[ref=ref_11]: 验证码登录"));
        when(browser.extension_browser_observe(eq("all"), any()))
                .thenAnswer(ignored -> observes.remove());
        when(browser.extension_browser_click_at(eq(1065.0), eq(41.0), any())).thenReturn(ok());
        when(browser.extension_browser_type_at(eq("openclaw\n"), any(), any())).thenReturn(ok());

        String out = tool.lead_browser_douyin_search("openclaw", null);

        assertThat(out).contains("状态：LOGIN_REQUIRED");
        assertThat(out).contains("登录");
        assertThat(out).doesNotContain("BLOCKED_LOOP");
    }

    @Test
    void douyinSearch_stopsWhenAlreadyOnSearchStateAfterHomeNavigate() throws Exception {
        when(browser.extension_browser_navigate(eq("https://www.douyin.com/"), eq("load"), any()))
                .thenReturn(ok());
        when(browser.extension_browser_observe(eq("all"), any())).thenReturn(observe(
                "https://www.douyin.com/search/openclaw?type=general",
                "openclaw - 抖音搜索",
                "Textbox[ref=ref_1]: openclaw @{10,10 200x32}\n"
                        + "Tab[ref=ref_2]: 综合 @{10,60 40x20}"));

        String out = tool.lead_browser_douyin_search("openclaw", null);

        assertThat(out).contains("已完成");
        assertThat(out).contains("already_on_search");
        verify(browser, never()).extension_browser_click(any(), any(), any(), any());
        verify(browser, never()).extension_browser_type(any(), any());
    }

    @Test
    void douyinSearch_blocksRepeatedSameStateInsteadOfLooping() throws Exception {
        when(browser.extension_browser_navigate(eq("https://www.douyin.com/"), eq("load"), any()))
                .thenReturn(ok());
        when(browser.extension_browser_observe(eq("all"), any())).thenReturn(observe(
                "https://www.douyin.com/",
                "抖音",
                "Textbox[ref=ref_1]: 搜索你感兴趣的内容 @{700,5 730x72}"));
        when(browser.extension_browser_click_at(eq(1065.0), eq(41.0), any())).thenReturn(ok());
        when(browser.extension_browser_type_at(eq("openclaw\n"), any(), any())).thenReturn(ok());

        String out = tool.lead_browser_douyin_search("openclaw", null);

        assertThat(out).contains("未完成");
        assertThat(out).contains("状态：BLOCKED_LOOP");
        verify(browser).extension_browser_click_at(eq(1065.0), eq(41.0), any());
        verify(browser, times(2)).extension_browser_type_at(eq("openclaw\n"), any(), any());
        verify(browser, never()).extension_browser_wait(any(), any(), any(), any(), any());
    }

    @Test
    void douyinSearch_fallsBackToSearchButtonWhenTextboxIsNotExposed() throws Exception {
        when(browser.extension_browser_navigate(eq("https://www.douyin.com/"), eq("load"), any()))
                .thenReturn(ok());
        Queue<String> observes = new ArrayDeque<>();
        observes.add(observe(
                "https://www.douyin.com/",
                "抖音",
                "Button[ref=ref_1]: 搜索 @{1290,6 135x70}"));
        observes.add(observe(
                "https://www.douyin.com/",
                "抖音",
                "Textbox[ref=ref_2]: 搜索你感兴趣的内容 @{700,5 730x72}"));
        observes.add(observe(
                "https://www.douyin.com/search/openclaw?type=general",
                "openclaw - 抖音搜索",
                "Button[ref=ref_1]: 搜索 @{10,10 200x32}\n"
                        + "Tab[ref=ref_2]: 综合 @{10,60 40x20}"));
        when(browser.extension_browser_observe(eq("all"), any()))
                .thenAnswer(ignored -> observes.remove());
        when(browser.extension_browser_click_at(eq(1357.5), eq(41.0), any())).thenReturn(ok());
        when(browser.extension_browser_type_at(eq("openclaw\n"), any(), any())).thenReturn(ok());

        String out = tool.lead_browser_douyin_search("openclaw", null);

        assertThat(out).contains("已完成");
        assertThat(out).contains("状态：DONE");
        verify(browser).extension_browser_click_at(eq(1357.5), eq(41.0), any());
        verify(browser).extension_browser_type_at(eq("openclaw\n"), any(), any());
        verify(browser, never()).extension_browser_type(eq("openclaw\n"), any());
        verify(browser, never()).extension_browser_wait(any(), any(), any(), any(), any());
    }

    @Test
    void douyinSearch_retriesClickWhenDebuggerSessionDetaches() throws Exception {
        when(browser.extension_browser_navigate(eq("https://www.douyin.com/"), eq("load"), any()))
                .thenReturn(ok());
        Queue<String> observes = new ArrayDeque<>();
        observes.add(observe(
                "https://www.douyin.com/",
                "抖音",
                "Textbox[ref=ref_1]: 搜索你感兴趣的内容 @{700,5 730x72}"));
        observes.add(observe(
                "https://www.douyin.com/",
                "抖音",
                "Textbox[ref=ref_2]: 搜索你感兴趣的内容 @{700,5 730x72}"));
        observes.add(observe(
                "https://www.douyin.com/search/openclaw?type=general",
                "openclaw - 抖音搜索",
                "Textbox[ref=ref_1]: openclaw @{10,10 200x32}\n"
                        + "Tab[ref=ref_2]: 综合 @{10,60 40x20}"));
        when(browser.extension_browser_observe(eq("all"), any()))
                .thenAnswer(ignored -> observes.remove());
        when(browser.extension_browser_click_at(eq(1065.0), eq(41.0), any()))
                .thenReturn("{\"ok\":false,\"code\":\"SESSION_DETACHED\",\"message\":\"debugger session detached: Detached while handling command\"}")
                .thenReturn(ok());
        when(browser.extension_browser_type_at(eq("openclaw\n"), any(), any())).thenReturn(ok());

        String out = tool.lead_browser_douyin_search("openclaw", null);

        assertThat(out).contains("已完成");
        assertThat(out).contains("状态：DONE");
        verify(browser, times(2)).extension_browser_click_at(eq(1065.0), eq(41.0), any());
        verify(browser).extension_browser_type_at(eq("openclaw\n"), any(), any());
        verify(browser, never()).extension_browser_type(eq("openclaw\n"), any());
        verify(browser, never()).extension_browser_wait(any(), any(), any(), any(), any());
    }

    @Test
    void douyinSearch_reobservesAndTypesAtInputAfterSearchEntryClick() throws Exception {
        when(browser.extension_browser_navigate(eq("https://www.douyin.com/"), eq("load"), any()))
                .thenReturn(ok());
        Queue<String> observes = new ArrayDeque<>();
        observes.add(observe(
                "https://www.douyin.com/",
                "抖音",
                "Button[ref=ref_1]: 搜索 @{1290,6 135x70}"));
        observes.add(observe(
                "https://www.douyin.com/",
                "抖音",
                "Textbox[ref=ref_2]: 搜索 @{100,40 300x40}"));
        observes.add(observe(
                "https://www.douyin.com/search/openclaw?type=general",
                "openclaw - 抖音搜索",
                "Textbox[ref=ref_2]: openclaw @{100,40 300x40}\n"
                        + "Tab[ref=ref_3]: 综合 @{100,90 40x20}"));
        when(browser.extension_browser_observe(eq("all"), any()))
                .thenAnswer(ignored -> observes.remove());
        when(browser.extension_browser_click_at(eq(1357.5), eq(41.0), any()))
                .thenReturn(ok());
        when(browser.extension_browser_type_at(eq("openclaw\n"), any(), any()))
                .thenReturn(ok());

        String out = tool.lead_browser_douyin_search("openclaw", null);

        assertThat(out).contains("已完成");
        assertThat(out).contains("状态：DONE");
        verify(browser).extension_browser_click_at(eq(1357.5), eq(41.0), any());
        verify(browser).extension_browser_type_at(eq("openclaw\n"), argThat(target ->
                target instanceof TypePayload.FocusTarget focusTarget
                        && focusTarget.x() == 250.0
                        && focusTarget.y() == 60.0), any());
        verify(browser, never()).extension_browser_type(eq("openclaw\n"), any());
    }

    @Test
    void douyinSearch_prefersRankedTopSearchBoxOverSidebarSearch() throws Exception {
        when(browser.extension_browser_navigate(eq("https://www.douyin.com/"), eq("load"), any()))
                .thenReturn(ok());
        Queue<String> observes = new ArrayDeque<>();
        observes.add(observe(
                "https://www.douyin.com/jingxuan",
                "抖音精选",
                "Textbox[ref=ref_top]: 搜索你感兴趣的内容 @{700,5 730x72}\n"
                        + "Button[ref=ref_top_btn]: 搜索 @{1290,6 135x70}\n"
                        + "Link[ref=ref_side]: 搜索 @{58,249 116x56}\n"
                        + "Link[ref=ref_jingxuan]: 精选 @{58,92 130x68}"));
        observes.add(observe(
                "https://www.douyin.com/jingxuan",
                "抖音精选",
                "Textbox[ref=ref_top]: 搜索你感兴趣的内容 @{700,5 730x72}\n"
                        + "Button[ref=ref_top_btn]: 搜索 @{1290,6 135x70}\n"
                        + "Link[ref=ref_side]: 搜索 @{58,249 116x56}"));
        observes.add(observe(
                "https://www.douyin.com/search/openclaw?type=general",
                "openclaw - 抖音搜索",
                "Textbox[ref=ref_top]: openclaw @{700,5 730x72}\n"
                        + "Tab[ref=ref_1]: 综合 @{260,112 40x20}"));
        when(browser.extension_browser_observe(eq("all"), any()))
                .thenAnswer(ignored -> observes.remove());
        when(browser.extension_browser_click_at(eq(1065.0), eq(41.0), any())).thenReturn(ok());
        when(browser.extension_browser_type_at(eq("openclaw\n"), any(), any())).thenReturn(ok());

        String out = tool.lead_browser_douyin_search("openclaw", null);

        assertThat(out).contains("已完成");
        assertThat(out).contains("状态：DONE");
        verify(browser).extension_browser_click_at(eq(1065.0), eq(41.0), any());
        verify(browser, never()).extension_browser_click(eq("搜索"), eq("link"), any(), any());
        verify(browser).extension_browser_type_at(eq("openclaw\n"), argThat(target ->
                target instanceof TypePayload.FocusTarget focusTarget
                        && focusTarget.x() == 1065.0
                        && focusTarget.y() == 41.0), any());
    }

    @Test
    void douyinSearch_isNOTReturnDirect_soItCanChainIntoFollowUpSteps() {
        // returnDirect was removed: a returnDirect tool short-circuits the ReAct
        // loop straight to FinalAnswer (see ToolExecutionExecutor / ObservationDispatcher
        // RETURN_DIRECT_TRIGGERED), which dead-ends multi-step tasks like
        // search → filter → sort → open → comments. The result now flows back into
        // the loop so the agent can keep going (and the guidance steers it to the
        // composable extension_browser_* primitives anyway).
        var cb = List.of(ToolCallbacks.from(tool)).stream()
                .filter(c -> c.getToolDefinition().name().equals("lead_browser_douyin_search"))
                .findFirst()
                .orElseThrow();

        assertThat(cb.getToolMetadata().returnDirect()).isFalse();
    }

    @Test
    void searchForLeads_searchesThenReturnsCandidateSnapshotJson() throws Exception {
        when(browser.extension_browser_navigate(eq("https://www.douyin.com/"), eq("load"), any()))
                .thenReturn(ok());
        Queue<String> observes = new ArrayDeque<>();
        observes.add(observe(
                "https://www.douyin.com/",
                "抖音",
                "Textbox[ref=ref_1]: 搜索你感兴趣的内容 @{700,5 730x72}"));
        observes.add(observe(
                "https://www.douyin.com/",
                "抖音",
                "Textbox[ref=ref_2]: 搜索你感兴趣的内容 @{700,5 730x72}"));
        observes.add(observe(
                "https://www.douyin.com/search/openclaw?type=general",
                "openclaw - 抖音搜索",
                "Textbox[ref=ref_1]: openclaw @{10,10 200x32}\n"
                        + "Tab[ref=ref_2]: 综合 @{10,60 40x20}"));
        observes.add(observe(
                "https://www.douyin.com/search/openclaw?type=general",
                "openclaw - 抖音搜索",
                "Article[ref=ref_1]: OpenClaw 官方账号 AI Agent @{10,90 200x120}\n"
                        + "Link[ref=ref_2]: openclaw.ai @{10,220 120x20}\n"
                        + "Button[ref=ref_3]: 关注 @{160,220 40x20}"));
        when(browser.extension_browser_observe(eq("all"), any()))
                .thenAnswer(ignored -> observes.remove());
        when(browser.extension_browser_click_at(eq(1065.0), eq(41.0), any())).thenReturn(ok());
        when(browser.extension_browser_type_at(eq("openclaw\n"), any(), any())).thenReturn(ok());

        String out = tool.lead_browser_douyin_search_for_leads("openclaw", "AI tools", 2, null);

        JsonNode j = mapper.readTree(out);
        assertThat(j.path("ok").asBoolean()).isTrue();
        assertThat(j.path("status").asText()).isEqualTo("DONE");
        assertThat(j.path("candidate_lines")).hasSize(2);
        assertThat(j.path("candidate_lines").get(0).asText()).contains("OpenClaw");
        verify(browser, never()).extension_browser_wait(any(), any(), any(), any(), any());
    }

    @Test
    void snapshotForLeads_returnsCompactCandidateLines() throws Exception {
        when(browser.extension_browser_observe(eq("all"), any())).thenReturn(observe(
                "https://www.douyin.com/search/openclaw?type=general",
                "openclaw - 抖音搜索",
                "Article[ref=ref_1]: OpenClaw 官方账号 AI Agent @{10,90 200x120}\n"
                        + "Link[ref=ref_2]: openclaw.ai @{10,220 120x20}\n"
                        + "Button[ref=ref_3]: 关注 @{160,220 40x20}"));

        String out = tool.lead_browser_snapshot_for_leads("AI tools", 2, null);

        JsonNode j = mapper.readTree(out);
        assertThat(j.path("ok").asBoolean()).isTrue();
        assertThat(j.path("status").asText()).isEqualTo("DONE");
        assertThat(j.path("candidate_lines")).hasSize(2);
        assertThat(j.path("candidate_lines").get(0).asText()).contains("OpenClaw");
    }

    private String ok() {
        return "{\"ok\":true}";
    }

    private String miss(String message) {
        return "{\"ok\":false,\"code\":\"GROUNDING_MISS\",\"message\":\"" + message + "\"}";
    }

    private String observe(String url, String title, String tree) throws Exception {
        return mapper.writeValueAsString(java.util.Map.of(
                "ok", true,
                "snapshot_id", "snap-1",
                "url", url,
                "title", title,
                "viewport", java.util.Map.of("w", 1280, "h", 800),
                "tree", tree));
    }
}
