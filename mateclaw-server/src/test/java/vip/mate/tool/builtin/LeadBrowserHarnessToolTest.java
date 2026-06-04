package vip.mate.tool.builtin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.support.ToolCallbacks;
import vip.mate.browser.edge.action.TypePayload;

import java.util.ArrayDeque;
import java.util.List;
import java.util.Queue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
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
        when(browser.extension_browser_hover_at_linear(anyDouble(), anyDouble(), any())).thenReturn(ok());
        when(browser.extension_browser_click_at_linear(anyDouble(), anyDouble(), any())).thenReturn(ok());
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
    void douyinSearch_doesNotTreatHomePageSearchChromeAsCompletedSearchResults() throws Exception {
        when(browser.extension_browser_navigate(eq("https://www.douyin.com/"), eq("load"), any()))
                .thenReturn(ok());
        Queue<String> observes = new ArrayDeque<>();
        observes.add(observe(
                "https://www.douyin.com/jingxuan",
                "抖音精选电脑版 - 抖音旗下优质视频平台",
                "Textbox[ref=ref_1]: 搜索你感兴趣的内容 @{700,5 730x72}\n"
                        + "Tab[ref=ref_2]: 全部 @{184,68 32x20}\n"
                        + "Text[ref=ref_3]: 精选视频 @{240,100 80x20}"));
        observes.add(observe(
                "https://www.douyin.com/jingxuan",
                "抖音精选电脑版 - 抖音旗下优质视频平台",
                "Textbox[ref=ref_2]: 搜索你感兴趣的内容 @{700,5 730x72}\n"
                        + "Tab[ref=ref_3]: 全部 @{184,68 32x20}\n"
                        + "Text[ref=ref_4]: 精选视频 @{240,100 80x20}"));
        observes.add(observe(
                "https://www.douyin.com/search/crm?type=general",
                "crm - 抖音搜索",
                "Textbox[ref=ref_1]: crm @{700,5 730x72}\n"
                        + "Tab[ref=ref_2]: 综合 @{10,60 40x20}\n"
                        + "Article[ref=ref_3]: crm 获客视频 @{10,90 200x120}"));
        when(browser.extension_browser_observe(eq("all"), any()))
                .thenAnswer(ignored -> observes.remove());
        when(browser.extension_browser_click_at(eq(1065.0), eq(41.0), any())).thenReturn(ok());
        when(browser.extension_browser_type_at(eq("crm\n"), any(), any())).thenReturn(ok());

        String out = tool.lead_browser_douyin_search("crm", null);

        assertThat(out).contains("已完成");
        assertThat(out).contains("homepage_search_box");
        verify(browser).extension_browser_type_at(eq("crm\n"), any(), any());
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
    void siteSearchSelectOption_worksForXiaohongshuStyleSearchAndSortFlow() throws Exception {
        when(browser.extension_browser_navigate(eq("https://www.xiaohongshu.com"), eq("load"), any()))
                .thenReturn(ok());
        Queue<String> observes = new ArrayDeque<>();
        observes.add(observe(
                "https://www.xiaohongshu.com",
                "小红书",
                "Textbox[ref=ref_1]: 搜索小红书 @{300,10 500x40}"));
        observes.add(observe(
                "https://www.xiaohongshu.com",
                "小红书",
                "Textbox[ref=ref_2]: 搜索小红书 @{300,10 500x40}"));
        observes.add(observe(
                "https://www.xiaohongshu.com/search_result?keyword=openclaw",
                "openclaw - 小红书搜索",
                "Textbox[ref=ref_1]: openclaw @{300,10 500x40}\n"
                        + "Button[ref=ref_filter]: 筛选 @{930,96 64x32}\n"
                        + "Article[ref=ref_card]: OpenClaw 使用笔记 @{240,180 260x120}"));
        observes.add(observe(
                "https://www.xiaohongshu.com/search_result?keyword=openclaw",
                "openclaw - 小红书搜索",
                "Textbox[ref=ref_1]: openclaw @{300,10 500x40}\n"
                        + "Button[ref=ref_filter]: 筛选 @{930,96 64x32}\n"
                        + "Button[ref=ref_general]: 综合排序 @{926,168 80x28}\n"
                        + "Button[ref=ref_like]: 最多点赞 @{926,204 80x28}\n"
                        + "Button[ref=ref_latest]: 最新发布 @{926,240 80x28}"));
        observes.add(observe(
                "https://www.xiaohongshu.com/search_result?keyword=openclaw",
                "openclaw - 小红书搜索",
                "Textbox[ref=ref_1]: openclaw @{300,10 500x40}\n"
                        + "Button[ref=ref_filter]: 筛选 @{930,96 64x32}\n"
                        + "Button[ref=ref_like]: 最多点赞 已选 @{926,204 102x28}\n"
                        + "Article[ref=ref_card]: OpenClaw 使用笔记 @{240,180 260x120}"));
        when(browser.extension_browser_observe(eq("all"), any()))
                .thenAnswer(ignored -> observes.remove());
        when(browser.extension_browser_click_at(eq(550.0), eq(30.0), any())).thenReturn(ok());
        when(browser.extension_browser_type_at(eq("openclaw\n"), any(), any())).thenReturn(ok());
        when(browser.extension_browser_hover_at(eq(962.0), eq(112.0), any())).thenReturn(ok());
        when(browser.extension_browser_click_at(eq(966.0), eq(218.0), any())).thenReturn(ok());

        String out = tool.lead_browser_site_search_select_option(
                "www.xiaohongshu.com", "openclaw", "筛选", "最多点赞", "小红书", null);

        JsonNode j = mapper.readTree(out);
        assertThat(j.path("ok").asBoolean()).isTrue();
        assertThat(j.path("status").asText()).isEqualTo("DONE");
        assertThat(j.path("site").asText()).isEqualTo("小红书");
        assertThat(j.path("selected_option").asText()).isEqualTo("最多点赞");
        assertThat(j.path("query").asText()).isEqualTo("openclaw");
        verify(browser).extension_browser_navigate(eq("https://www.xiaohongshu.com"), eq("load"), any());
        verify(browser).extension_browser_click_at(eq(550.0), eq(30.0), any());
        verify(browser).extension_browser_hover_at(eq(962.0), eq(112.0), any());
        verify(browser).extension_browser_click_at(eq(966.0), eq(218.0), any());
    }

    @Test
    void siteSearchSelectOption_delegatesDouyinMostLikedToDedicatedHarness() throws Exception {
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
                "Textbox[ref=ref_1]: openclaw @{700,5 730x72}\n"
                        + "Button[ref=ref_filter]: 筛选 @{930,96 64x32}"));
        observes.add(observe(
                "https://www.douyin.com/search/openclaw?type=general",
                "openclaw - 抖音搜索",
                "Button[ref=ref_filter]: 筛选 @{930,96 64x32}\n"
                        + "Text[ref=ref_sort]: 排序依据 @{926,136 68x24}\n"
                        + "Button[ref=ref_like]: 最多点赞 @{926,204 80x28}"));
        observes.add(observe(
                "https://www.douyin.com/search/openclaw?type=general",
                "openclaw - 抖音搜索",
                "Button[ref=ref_filter]: 筛选 @{930,96 64x32}\n"
                        + "Button[ref=ref_like]: 最多点赞 已选 @{926,204 102x28}"));
        when(browser.extension_browser_observe(eq("all"), any()))
                .thenAnswer(ignored -> observes.remove());
        when(browser.extension_browser_click_at(eq(1065.0), eq(41.0), any())).thenReturn(ok());
        when(browser.extension_browser_type_at(eq("openclaw\n"), any(), any())).thenReturn(ok());
        when(browser.extension_browser_hover_at(eq(962.0), eq(112.0), any())).thenReturn(ok());
        when(browser.extension_browser_click_at(eq(966.0), eq(218.0), any())).thenReturn(ok());

        String out = tool.lead_browser_site_search_select_option(
                "https://www.douyin.com", "openclaw", "筛选", "最多点赞", "抖音", null);

        JsonNode j = mapper.readTree(out);
        assertThat(j.path("ok").asBoolean()).isTrue();
        assertThat(j.path("status").asText()).isEqualTo("DONE");
        assertThat(j.path("site").asText()).isEqualTo("抖音");
        assertThat(j.path("delegated_tool").asText()).isEqualTo("lead_browser_douyin_search_sort_most_liked");
        verify(browser).extension_browser_navigate(eq("https://www.douyin.com/"), eq("load"), any());
        verify(browser, never()).extension_browser_navigate(eq("https://www.douyin.com"), eq("load"), any());
    }

    @Test
    void douyinSearchSortMostLiked_searchesThenHoversFilterAndClicksMostLiked() throws Exception {
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
                "Textbox[ref=ref_1]: openclaw @{700,5 730x72}\n"
                        + "Button[ref=ref_filter]: 筛选 @{930,96 64x32}\n"
                        + "Article[ref=ref_card]: OpenClaw 官方账号 @{240,180 260x120}"));
        observes.add(observe(
                "https://www.douyin.com/search/openclaw?type=general",
                "openclaw - 抖音搜索",
                "Textbox[ref=ref_1]: openclaw @{700,5 730x72}\n"
                        + "Button[ref=ref_filter]: 筛选 @{930,96 64x32}\n"
                        + "Text[ref=ref_sort]: 排序依据 @{926,136 68x24}\n"
                        + "Button[ref=ref_general]: 综合排序 @{926,168 80x28}\n"
                        + "Button[ref=ref_like]: 最多点赞 @{926,204 80x28}\n"
                        + "Button[ref=ref_latest]: 最新发布 @{926,240 80x28}"));
        observes.add(observe(
                "https://www.douyin.com/search/openclaw?type=general",
                "openclaw - 抖音搜索",
                "Textbox[ref=ref_1]: openclaw @{700,5 730x72}\n"
                        + "Button[ref=ref_filter]: 筛选 @{930,96 64x32}\n"
                        + "Button[ref=ref_like]: 最多点赞 已选 @{926,204 102x28}\n"
                        + "Article[ref=ref_card]: OpenClaw 官方账号 @{240,180 260x120}"));
        when(browser.extension_browser_observe(eq("all"), any()))
                .thenAnswer(ignored -> observes.remove());
        when(browser.extension_browser_click_at(eq(1065.0), eq(41.0), any())).thenReturn(ok());
        when(browser.extension_browser_type_at(eq("openclaw\n"), any(), any())).thenReturn(ok());
        when(browser.extension_browser_hover_at(eq(962.0), eq(112.0), any())).thenReturn(ok());
        when(browser.extension_browser_click_at(eq(966.0), eq(218.0), any())).thenReturn(ok());

        String out = tool.lead_browser_douyin_search_sort_most_liked("openclaw", null);

        JsonNode j = mapper.readTree(out);
        assertThat(j.path("ok").asBoolean()).isTrue();
        assertThat(j.path("status").asText()).isEqualTo("DONE");
        assertThat(j.path("sort").asText()).isEqualTo("最多点赞");
        assertThat(j.path("query").asText()).isEqualTo("openclaw");
        verify(browser).extension_browser_hover_at(eq(962.0), eq(112.0), any());
        verify(browser).extension_browser_click_at(eq(966.0), eq(218.0), any());
        verify(browser, never()).extension_browser_hover(eq("筛选"), any(), any(), any());
    }

    @Test
    void douyinSearchSortMostLiked_clicksOptionTextAfterHoverInsteadOfTogglingFilterClosed() throws Exception {
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
                "Textbox[ref=ref_1]: openclaw @{700,5 730x72}\n"
                        + "Button[ref=ref_filter]: 筛选 @{930,96 64x32}"));
        observes.add(observe(
                "https://www.douyin.com/search/openclaw?type=general",
                "openclaw - 抖音搜索",
                "Textbox[ref=ref_1]: openclaw @{700,5 730x72}\n"
                        + "Button[ref=ref_filter]: 筛选 @{930,96 64x32}"));
        observes.add(observe(
                "https://www.douyin.com/search/openclaw?type=general",
                "openclaw - 抖音搜索",
                "Button[ref=ref_filter]: 筛选 @{930,96 64x32}\n"
                        + "Text[ref=ref_sort]: 排序依据 @{926,136 68x24}\n"
                        + "Button[ref=ref_like]: 最多点赞 @{926,204 80x28}"));
        observes.add(observe(
                "https://www.douyin.com/search/openclaw?type=general",
                "openclaw - 抖音搜索",
                "Button[ref=ref_filter]: 筛选 @{930,96 64x32}\n"
                        + "Button[ref=ref_like]: 最多点赞 已选 @{926,204 102x28}"));
        when(browser.extension_browser_observe(eq("all"), any()))
                .thenAnswer(ignored -> observes.remove());
        when(browser.extension_browser_click_at(eq(1065.0), eq(41.0), any())).thenReturn(ok());
        when(browser.extension_browser_type_at(eq("openclaw\n"), any(), any())).thenReturn(ok());
        when(browser.extension_browser_hover_at(eq(962.0), eq(112.0), any())).thenReturn(ok());
        when(browser.extension_browser_click(eq("最多点赞"), eq("button"), any(), any())).thenReturn(ok());

        String out = tool.lead_browser_douyin_search_sort_most_liked("openclaw", null);

        JsonNode j = mapper.readTree(out);
        assertThat(j.path("ok").asBoolean()).isTrue();
        assertThat(j.path("status").asText()).isEqualTo("DONE");
        verify(browser).extension_browser_hover_at(eq(962.0), eq(112.0), any());
        verify(browser, never()).extension_browser_click_at(eq(962.0), eq(112.0), any());
        verify(browser).extension_browser_click(eq("最多点赞"), eq("button"), any(), any());
    }

    @Test
    void douyinSearchSortMostLiked_fallsBackToOptionTextWhenFilterHoverTimesOut() throws Exception {
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
                "Textbox[ref=ref_1]: openclaw @{700,5 730x72}\n"
                        + "Button[ref=ref_filter]: 筛选 @{930,96 64x32}"));
        observes.add(observe(
                "https://www.douyin.com/search/openclaw?type=general",
                "openclaw - 抖音搜索",
                "Button[ref=ref_filter]: 筛选 @{930,96 64x32}\n"
                        + "Button[ref=ref_like]: 最多点赞 已选 @{926,204 102x28}"));
        when(browser.extension_browser_observe(eq("all"), any()))
                .thenAnswer(ignored -> observes.remove());
        when(browser.extension_browser_click_at(eq(1065.0), eq(41.0), any())).thenReturn(ok());
        when(browser.extension_browser_type_at(eq("openclaw\n"), any(), any())).thenReturn(ok());
        when(browser.extension_browser_hover_at(eq(962.0), eq(112.0), any()))
                .thenReturn(miss("hover timeout"));
        when(browser.extension_browser_click(eq("最多点赞"), eq("button"), any(), any())).thenReturn(ok());

        String out = tool.lead_browser_douyin_search_sort_most_liked("openclaw", null);

        JsonNode j = mapper.readTree(out);
        assertThat(j.path("ok").asBoolean()).isTrue();
        assertThat(j.path("status").asText()).isEqualTo("DONE");
        assertThat(j.path("sort").asText()).isEqualTo("最多点赞");
        assertThat(j.path("attempts").toString())
                .contains("fallback_click_option_by_visible_text_after_hover_failure");
        verify(browser).extension_browser_hover_at(eq(962.0), eq(112.0), any());
        verify(browser).extension_browser_click(eq("最多点赞"), eq("button"), any(), any());
        verify(browser, never()).extension_browser_click_at(eq(962.0), eq(112.0), any());
    }

    @Test
    void douyinSearchSortMostLiked_clicksObservedTextOptionWhenPanelAlreadyContainsMostLiked() throws Exception {
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
                "Textbox[ref=ref_1]: openclaw @{700,5 730x72}\n"
                        + "Button[ref=ref_filter]: 筛选 @{930,96 64x32}"));
        observes.add(observe(
                "https://www.douyin.com/search/openclaw?type=general",
                "openclaw - 抖音搜索",
                "Button[ref=ref_filter]: 筛选 @{930,96 64x32}\n"
                        + "Text[ref=ref_sort]: 排序依据 @{926,136 68x24}\n"
                        + "Text[ref=ref_like]: 最多点赞 @{926,204 80x28}"));
        observes.add(observe(
                "https://www.douyin.com/search/openclaw?type=general",
                "openclaw - 抖音搜索",
                "Button[ref=ref_filter]: 筛选 @{930,96 64x32}\n"
                        + "Text[ref=ref_like]: 最多点赞 已选 @{926,204 102x28}"));
        when(browser.extension_browser_observe(eq("all"), any()))
                .thenAnswer(ignored -> observes.remove());
        when(browser.extension_browser_click_at(eq(1065.0), eq(41.0), any())).thenReturn(ok());
        when(browser.extension_browser_type_at(eq("openclaw\n"), any(), any())).thenReturn(ok());
        when(browser.extension_browser_hover_at(eq(962.0), eq(112.0), any())).thenReturn(ok());
        when(browser.extension_browser_click_at(eq(966.0), eq(218.0), any())).thenReturn(ok());

        String out = tool.lead_browser_douyin_search_sort_most_liked("openclaw", null);

        JsonNode j = mapper.readTree(out);
        assertThat(j.path("ok").asBoolean()).isTrue();
        assertThat(j.path("status").asText()).isEqualTo("DONE");
        assertThat(j.path("sort").asText()).isEqualTo("最多点赞");
        verify(browser).extension_browser_click_at(eq(966.0), eq(218.0), any());
        verify(browser, never()).extension_browser_click(eq("最多点赞"), eq("button"), any(), any());
    }

    @Test
    void douyinSearchSortMostLiked_fallsBackToVisibleTextClickWhenOptionIsMissingFromTree() throws Exception {
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
                "Textbox[ref=ref_1]: openclaw @{700,5 730x72}\n"
                        + "Button[ref=ref_filter]: 筛选 @{930,96 64x32}"));
        observes.add(observe(
                "https://www.douyin.com/search/openclaw?type=general",
                "openclaw - 抖音搜索",
                "Button[ref=ref_filter]: 筛选 @{930,96 64x32}\n"
                        + "Button[ref=ref_general]: 综合排序 @{926,168 80x28}"));
        observes.add(observe(
                "https://www.douyin.com/search/openclaw?type=general",
                "openclaw - 抖音搜索",
                "Button[ref=ref_filter]: 筛选 @{930,96 64x32}\n"
                        + "Button[ref=ref_general]: 综合排序 @{926,168 80x28}"));
        observes.add(observe(
                "https://www.douyin.com/search/openclaw?type=general",
                "openclaw - 抖音搜索",
                "Button[ref=ref_filter]: 筛选 @{930,96 64x32}\n"
                        + "Button[ref=ref_like]: 最多点赞 已选 @{926,204 102x28}"));
        when(browser.extension_browser_observe(eq("all"), any()))
                .thenAnswer(ignored -> observes.remove());
        when(browser.extension_browser_click_at(eq(1065.0), eq(41.0), any())).thenReturn(ok());
        when(browser.extension_browser_type_at(eq("openclaw\n"), any(), any())).thenReturn(ok());
        when(browser.extension_browser_hover_at(eq(962.0), eq(112.0), any())).thenReturn(ok());
        when(browser.extension_browser_click(eq("最多点赞"), eq("button"), any(), any())).thenReturn(ok());

        String out = tool.lead_browser_douyin_search_sort_most_liked("openclaw", null);

        JsonNode j = mapper.readTree(out);
        assertThat(j.path("ok").asBoolean()).isTrue();
        assertThat(j.path("status").asText()).isEqualTo("DONE");
        verify(browser).extension_browser_hover_at(eq(962.0), eq(112.0), any());
        verify(browser, never()).extension_browser_click_at(eq(962.0), eq(112.0), any());
        verify(browser).extension_browser_click(eq("最多点赞"), eq("button"), any(), any());
    }

    @Test
    void douyinSearchSortMostLiked_usesVisibleTextHoverWhenFilterMissingFromTree() throws Exception {
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
                "Textbox[ref=ref_1]: openclaw @{700,5 730x72}\n"
                        + "Article[ref=ref_card]: OpenClaw 官方账号 @{240,180 260x120}"));
        observes.add(observe(
                "https://www.douyin.com/search/openclaw?type=general",
                "openclaw - 抖音搜索",
                "Textbox[ref=ref_1]: openclaw @{700,5 730x72}\n"
                        + "Article[ref=ref_card]: OpenClaw 官方账号 @{240,180 260x120}"));
        observes.add(observe(
                "https://www.douyin.com/search/openclaw?type=general",
                "openclaw - 抖音搜索",
                "Text[ref=ref_sort]: 排序依据 @{926,136 68x24}\n"
                        + "Button[ref=ref_general]: 综合排序 @{926,168 80x28}\n"
                        + "Button[ref=ref_like]: 最多点赞 @{926,204 80x28}"));
        observes.add(observe(
                "https://www.douyin.com/search/openclaw?type=general",
                "openclaw - 抖音搜索",
                "Button[ref=ref_like]: 最多点赞 已选 @{926,204 102x28}\n"
                        + "Article[ref=ref_card]: OpenClaw 官方账号 @{240,180 260x120}"));
        when(browser.extension_browser_observe(eq("all"), any()))
                .thenAnswer(ignored -> observes.remove());
        when(browser.extension_browser_click_at(eq(1065.0), eq(41.0), any())).thenReturn(ok());
        when(browser.extension_browser_type_at(eq("openclaw\n"), any(), any())).thenReturn(ok());
        when(browser.extension_browser_hover(eq("筛选"), eq("button"), any(), any())).thenReturn(ok());
        when(browser.extension_browser_click_at(eq(966.0), eq(218.0), any())).thenReturn(ok());

        String out = tool.lead_browser_douyin_search_sort_most_liked("openclaw", null);

        JsonNode j = mapper.readTree(out);
        assertThat(j.path("ok").asBoolean()).isTrue();
        assertThat(j.path("status").asText()).isEqualTo("DONE");
        assertThat(j.path("sort").asText()).isEqualTo("最多点赞");
        verify(browser).extension_browser_hover(eq("筛选"), eq("button"), any(), any());
        verify(browser, never()).extension_browser_click(eq("筛选"), eq("button"), any(), any());
        verify(browser).extension_browser_click_at(eq(966.0), eq(218.0), any());
    }

    @Test
    void douyinSearchSortMostLiked_doesNotClickFilterAfterSuccessfulVisibleTextHover() throws Exception {
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
                "Textbox[ref=ref_1]: openclaw @{700,5 730x72}\n"
                        + "Article[ref=ref_card]: OpenClaw 官方账号 @{240,180 260x120}"));
        observes.add(observe(
                "https://www.douyin.com/search/openclaw?type=general",
                "openclaw - 抖音搜索",
                "Textbox[ref=ref_1]: openclaw @{700,5 730x72}\n"
                        + "Article[ref=ref_card]: OpenClaw 官方账号 @{240,180 260x120}"));
        observes.add(observe(
                "https://www.douyin.com/search/openclaw?type=general",
                "openclaw - 抖音搜索",
                "Text[ref=ref_sort]: 排序依据 @{926,136 68x24}"));
        observes.add(observe(
                "https://www.douyin.com/search/openclaw?type=general",
                "openclaw - 抖音搜索",
                "Button[ref=ref_like]: 最多点赞 已选 @{926,204 102x28}\n"
                        + "Article[ref=ref_card]: OpenClaw 官方账号 @{240,180 260x120}"));
        when(browser.extension_browser_observe(eq("all"), any()))
                .thenAnswer(ignored -> observes.remove());
        when(browser.extension_browser_click_at(eq(1065.0), eq(41.0), any())).thenReturn(ok());
        when(browser.extension_browser_type_at(eq("openclaw\n"), any(), any())).thenReturn(ok());
        when(browser.extension_browser_hover(eq("筛选"), eq("button"), any(), any())).thenReturn(ok());
        when(browser.extension_browser_click(eq("最多点赞"), eq("button"), any(), any())).thenReturn(ok());

        String out = tool.lead_browser_douyin_search_sort_most_liked("openclaw", null);

        JsonNode j = mapper.readTree(out);
        assertThat(j.path("ok").asBoolean()).isTrue();
        assertThat(j.path("status").asText()).isEqualTo("DONE");
        verify(browser).extension_browser_hover(eq("筛选"), eq("button"), any(), any());
        verify(browser, never()).extension_browser_click(eq("筛选"), eq("button"), any(), any());
        verify(browser).extension_browser_click(eq("最多点赞"), eq("button"), any(), any());
    }

    @Test
    void douyinSearchSortMostLiked_returnsClearFailureWhenVisibleTextFallbackAlsoMisses() throws Exception {
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
                "Textbox[ref=ref_1]: openclaw @{700,5 730x72}\n"
                        + "Button[ref=ref_filter]: 筛选 @{930,96 64x32}"));
        observes.add(observe(
                "https://www.douyin.com/search/openclaw?type=general",
                "openclaw - 抖音搜索",
                "Button[ref=ref_filter]: 筛选 @{930,96 64x32}\n"
                        + "Button[ref=ref_general]: 综合排序 @{926,168 80x28}"));
        observes.add(observe(
                "https://www.douyin.com/search/openclaw?type=general",
                "openclaw - 抖音搜索",
                "Button[ref=ref_filter]: 筛选 @{930,96 64x32}\n"
                        + "Button[ref=ref_general]: 综合排序 @{926,168 80x28}"));
        when(browser.extension_browser_observe(eq("all"), any()))
                .thenAnswer(ignored -> observes.remove());
        when(browser.extension_browser_click_at(eq(1065.0), eq(41.0), any())).thenReturn(ok());
        when(browser.extension_browser_type_at(eq("openclaw\n"), any(), any())).thenReturn(ok());
        when(browser.extension_browser_hover_at(eq(962.0), eq(112.0), any())).thenReturn(ok());
        when(browser.extension_browser_click(eq("最多点赞"), eq("button"), any(), any()))
                .thenReturn(miss("vision: no model configured"));

        String out = tool.lead_browser_douyin_search_sort_most_liked("openclaw", null);

        JsonNode j = mapper.readTree(out);
        assertThat(j.path("ok").asBoolean()).isFalse();
        assertThat(j.path("status").asText()).isEqualTo("SORT_OPTION_NOT_FOUND");
        assertThat(j.path("message").asText()).contains("最多点赞");
        assertThat(j.path("message").asText()).contains("vision");
        verify(browser).extension_browser_hover_at(eq(962.0), eq(112.0), any());
        verify(browser, never()).extension_browser_click_at(eq(962.0), eq(112.0), any());
        verify(browser).extension_browser_click(eq("最多点赞"), eq("button"), any(), any());
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

    @Test
    void douyinOpenFirstVideoComments_opensVideoAndCommentsOnly() throws Exception {
        Queue<String> observes = new ArrayDeque<>();
        observes.add(observe(
                "https://www.douyin.com/search/openclaw?type=general",
                "openclaw - 抖音搜索",
                "Textbox[ref=ref_1]: openclaw @{700,5 520x60}\n"
                        + "Button[ref=ref_2]: 筛选 @{930,90 64x44}\n"
                        + "Text[ref=ref_title_low]: OpenClaw 3.0部署教程 @{220,135 260x28}\n"
                        + "Image[ref=ref_low]: OpenClaw 3.0部署教程 @{220,168 260x190}\n"
                        + "Text[ref=ref_like_low]: 点赞7.4万 @{220,368 100x28}\n"
                        + "Text[ref=ref_title_high]: OpenClaw 爆款案例拆解 @{560,135 260x28}\n"
                        + "Image[ref=ref_high]: OpenClaw 爆款案例拆解 @{560,168 260x190}\n"
                        + "Text[ref=ref_like_high]: 55.9w点赞 @{560,368 120x28}"));
        observes.add(observe(
                "https://www.douyin.com/video/123",
                "如何50秒一键部署OpenClaw",
                "Button[ref=ref_5]: 评论 328 @{1120,420 72x48}\n"
                        + "Button[ref=ref_6]: 分享 @{1120,500 72x48}\n"
                        + "Button[ref=ref_7]: 暂停 @{450,700 72x48}"));
        observes.add(observe(
                "https://www.douyin.com/video/123",
                "如何50秒一键部署OpenClaw",
                "Button[ref=ref_5]: 评论 328 @{1120,420 72x48}\n"
                        + "Text[ref=ref_8]: 全部评论 @{760,120 90x28}\n"
                        + "Link[ref=ref_9]: 小明同学 @{760,180 82x26}\n"
                        + "Text[ref=ref_10]: 有一种老年人玩不懂智能手机一样的无力感 @{760,214 340x42}"));
        when(browser.extension_browser_observe(eq("all"), any()))
                .thenAnswer(ignored -> observes.remove());
        when(browser.extension_browser_hover_at(eq(690.0), eq(263.0), any())).thenReturn(ok());
        when(browser.extension_browser_click_at(eq(690.0), eq(263.0), any())).thenReturn(ok());
        when(browser.extension_browser_click_at(eq(1156.0), eq(444.0), any())).thenReturn(ok());

        String out = tool.lead_browser_douyin_open_first_video_comments(null);

        JsonNode j = mapper.readTree(out);
        assertThat(j.path("ok").asBoolean()).isTrue();
        assertThat(j.path("status").asText()).isEqualTo("DONE_COMMENTS_OPENED");
        assertThat(j.path("url").asText()).contains("/video/");
        verify(browser).extension_browser_hover_at(eq(690.0), eq(263.0), any());
        verify(browser).extension_browser_click_at(eq(690.0), eq(263.0), any());
        verify(browser, never()).extension_browser_click_at(eq(350.0), eq(263.0), any());
        verify(browser).extension_browser_click_at(eq(1156.0), eq(444.0), any());
        verify(browser, never()).extension_browser_click(eq("关注"), eq("button"), any(), any());
    }

    @Test
    void douyinOpenFirstVideoComments_usesShortcutXWhenCommentClickDoesNotRevealPanel() throws Exception {
        Queue<String> observes = new ArrayDeque<>();
        observes.add(observe(
                "https://www.douyin.com/search/openclaw?type=general",
                "openclaw - 抖音搜索",
                "Article[ref=ref_1]: 如何50秒一键部署OpenClaw，全自动赚钱 点赞55.9w @{240,160 360x260}"));
        observes.add(observe(
                "https://www.douyin.com/video/123",
                "如何50秒一键部署OpenClaw",
                "Button[ref=ref_5]: 评论 328 @{1120,420 72x48}\n"
                        + "Button[ref=ref_6]: 分享 @{1120,500 72x48}\n"
                        + "Button[ref=ref_7]: 暂停 @{450,700 72x48}"));
        observes.add(observe(
                "https://www.douyin.com/video/123",
                "如何50秒一键部署OpenClaw",
                "Text[ref=ref_8]: 全部评论 @{760,120 90x28}\n"
                        + "Link[ref=ref_9]: 小明同学 @{760,180 82x26}\n"
                        + "Text[ref=ref_10]: 有一种老年人玩不懂智能手机一样的无力感 @{760,214 340x42}"));
        when(browser.extension_browser_observe(eq("all"), any()))
                .thenAnswer(ignored -> observes.remove());
        when(browser.extension_browser_hover_at(eq(420.0), eq(290.0), any())).thenReturn(ok());
        when(browser.extension_browser_click_at(eq(420.0), eq(290.0), any())).thenReturn(ok());
        when(browser.extension_browser_click_at(eq(486.0), eq(724.0), any())).thenReturn(ok());
        when(browser.extension_browser_press_key(eq("x"), any())).thenReturn(ok());

        String out = tool.lead_browser_douyin_open_first_video_comments(null);

        JsonNode j = mapper.readTree(out);
        assertThat(j.path("ok").asBoolean()).isTrue();
        assertThat(j.path("status").asText()).isEqualTo("DONE_COMMENTS_OPENED");
        // On Douyin video pages the X shortcut is tried before the unstable
        // comment-icon/text click path.
        verify(browser, never()).extension_browser_click_at(eq(1156.0), eq(444.0), any());
        verify(browser, never()).extension_browser_click(eq("评论"), any(), any(), any());
        verify(browser).extension_browser_press_key(eq("x"), any());
        verify(browser, never()).extension_browser_type(eq("x"), any());
        verify(browser, never()).extension_browser_click(eq("关注"), eq("button"), any(), any());
    }

    @Test
    void douyinSearchSortOpenFirstVideoComments_runsEndToEndAndUsesShortcutX() throws Exception {
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
                "Textbox[ref=ref_1]: openclaw @{700,5 730x72}\n"
                        + "Button[ref=ref_filter]: 筛选 @{930,96 64x32}"));
        observes.add(observe(
                "https://www.douyin.com/search/openclaw?type=general",
                "openclaw - 抖音搜索",
                "Button[ref=ref_filter]: 筛选 @{930,96 64x32}\n"
                        + "Text[ref=ref_sort]: 排序依据 @{926,136 68x24}\n"
                        + "Button[ref=ref_like]: 最多点赞 @{926,204 80x28}"));
        observes.add(observe(
                "https://www.douyin.com/search/openclaw?type=general",
                "openclaw - 抖音搜索",
                "Button[ref=ref_filter]: 筛选 @{930,96 64x32}\n"
                        + "Button[ref=ref_like]: 最多点赞 已选 @{926,204 102x28}\n"
                        + "Text[ref=ref_title_low]: OpenClaw 3.0部署教程 @{220,135 260x28}\n"
                        + "Image[ref=ref_low]: OpenClaw 3.0部署教程 @{220,168 260x190}\n"
                        + "Text[ref=ref_like_low]: 点赞7.4万 @{220,368 100x28}\n"
                        + "Text[ref=ref_title_high]: 央视新闻 OpenClaw @{560,135 260x28}\n"
                        + "Image[ref=ref_high]: 全网都在养的龙虾 @{560,168 260x190}\n"
                        + "Text[ref=ref_like_high]: 55.9w点赞 @{560,368 120x28}"));
        observes.add(observe(
                "https://www.douyin.com/video/123",
                "全网都在养的龙虾",
                "Text[ref=ref_comment]: 151 @{1142,420 36x24}\n"
                        + "Text[ref=ref_like]: 点赞 55.7万 @{1118,338 84x24}\n"
                        + "Text[ref=ref_collect]: 收藏 11.3万 @{1118,500 92x24}\n"
                        + "Button[ref=ref_pause]: 暂停 @{450,700 72x48}"));
        observes.add(observe(
                "https://www.douyin.com/video/123",
                "全网都在养的龙虾",
                "Text[ref=ref_header]: 全部评论 @{760,120 90x28}\n"
                        + "Link[ref=ref_author]: 小明同学 @{760,180 82x26}\n"
                        + "Text[ref=ref_body]: 有一种老年人玩不懂智能手机一样的无力感 @{760,214 340x42}"));
        when(browser.extension_browser_observe(eq("all"), any()))
                .thenAnswer(ignored -> observes.remove());
        when(browser.extension_browser_click_at(eq(1065.0), eq(41.0), any())).thenReturn(ok());
        when(browser.extension_browser_type_at(eq("openclaw\n"), any(), any())).thenReturn(ok());
        when(browser.extension_browser_hover_at(eq(962.0), eq(112.0), any())).thenReturn(ok());
        when(browser.extension_browser_click_at(eq(966.0), eq(218.0), any())).thenReturn(ok());
        when(browser.extension_browser_hover_at(eq(690.0), eq(263.0), any())).thenReturn(ok());
        when(browser.extension_browser_click_at(eq(690.0), eq(263.0), any())).thenReturn(ok());
        when(browser.extension_browser_click_at(eq(486.0), eq(724.0), any())).thenReturn(ok());
        when(browser.extension_browser_press_key(eq("x"), any())).thenReturn(ok());

        String out = tool.lead_browser_douyin_search_sort_open_first_video_comments("openclaw", null);

        JsonNode j = mapper.readTree(out);
        assertThat(j.path("ok").asBoolean()).isTrue();
        assertThat(j.path("status").asText()).isEqualTo("DONE_COMMENTS_OPENED");
        assertThat(j.path("sort").asText()).isEqualTo("最多点赞");
        // Douyin comments should open through the native X shortcut first. This
        // avoids the unstable text/icon click path that misses the speech-bubble
        // icon or clicks a neighboring author/card on the real page.
        verify(browser, never()).extension_browser_click(eq("评论"), any(), any(), any());
        verify(browser).extension_browser_press_key(eq("x"), any());
        verify(browser, never()).extension_browser_click(eq("关注"), eq("button"), any(), any());
    }

    @Test
    void douyinOpenFirstVideoComments_doesNotTreatSearchModalBackgroundCardsAsCommentsAndFallsBackToK()
            throws Exception {
        String videoModalWithoutComments = observe(
                "https://www.douyin.com/jingxuan/search/openclaw?modal_id=7615879690590375183&type=general",
                "发现更多精彩视频 - 抖音搜索",
                "Text[ref=ref_filter]: 筛选 @{1184,66 36x26}\n"
                        + "Text[ref=ref_search_result]: 这个教程看起来像老年人玩不懂手机一样不会操作 @{790,352 199x91}\n"
                        + "Text[ref=ref_author]: @技术爬爬虾 @{807,405 70x20}\n"
                        + "Text[ref=ref_like]: 55.7万 @{1221,276 35x18}\n"
                        + "Text[ref=ref_comment_count]: 151 @{1229,335 20x18}\n"
                        + "Text[ref=ref_collect]: 11.3万 @{1221,394 35x18}\n"
                        + "Text[ref=ref_share]: 21.7万 @{1221,453 35x18}\n"
                        + "Video[ref=ref_video]: @{0,0 1280x527}",
                1280,
                575);
        Queue<String> observes = new ArrayDeque<>();
        observes.add(videoModalWithoutComments);
        observes.add(videoModalWithoutComments);
        observes.add(videoModalWithoutComments);
        observes.add(observe(
                "https://www.douyin.com/jingxuan/search/openclaw?modal_id=7615879690590375183&type=general",
                "发现更多精彩视频 - 抖音搜索",
                "Text[ref=ref_header]: 全部评论 @{760,120 90x28}\n"
                        + "Link[ref=ref_author]: 小明同学 @{760,180 82x26}\n"
                        + "Text[ref=ref_body]: 有一种老年人玩不懂智能手机一样的无力感 @{760,214 340x42}",
                1280,
                575));
        when(browser.extension_browser_observe(eq("all"), any()))
                .thenAnswer(ignored -> observes.remove());
        when(browser.extension_browser_click_at(eq(420.0), eq(420.0), any())).thenReturn(ok());
        when(browser.extension_browser_press_key(eq("x"), any())).thenReturn(ok());
        when(browser.extension_browser_press_key(eq("k"), any())).thenReturn(ok());

        String out = tool.lead_browser_douyin_open_first_video_comments(null);

        JsonNode j = mapper.readTree(out);
        assertThat(j.path("ok").asBoolean()).isTrue();
        assertThat(j.path("status").asText()).isEqualTo("DONE_COMMENTS_OPENED");
        verify(browser).extension_browser_press_key(eq("x"), any());
        verify(browser).extension_browser_press_key(eq("k"), any());
        verify(browser, never()).extension_browser_click(eq("评论"), any(), any(), any());
    }

    @Test
    void douyinOpenFirstVideoComments_keepsShortcutFocusInsideExistingCommentPanel() throws Exception {
        String openPanelButNotEnoughContent = observe(
                "https://www.douyin.com/jingxuan/search/openclaw?modal_id=7615879690590375183&type=general",
                "发现更多精彩视频 - 抖音搜索",
                "Text[ref=ref_detail_tab]: 详情 @{660,84 32x24}\n"
                        + "Text[ref=ref_works_tab]: TA的作品 @{735,84 72x24}\n"
                        + "Text[ref=ref_comment_tab]: 评论 @{842,84 32x24}\n"
                        + "Text[ref=ref_ai_tab]: 问AI @{918,84 36x24}\n"
                        + "Text[ref=ref_comment_count]: 151 @{1229,335 20x18}\n"
                        + "Video[ref=ref_video]: @{0,0 1280x527}",
                1280,
                575);
        Queue<String> observes = new ArrayDeque<>();
        observes.add(openPanelButNotEnoughContent);
        observes.add(openPanelButNotEnoughContent);
        observes.add(observe(
                "https://www.douyin.com/jingxuan/search/openclaw?modal_id=7615879690590375183&type=general",
                "发现更多精彩视频 - 抖音搜索",
                "Text[ref=ref_detail_tab]: 详情 @{660,84 32x24}\n"
                        + "Text[ref=ref_comment_tab]: 评论 @{842,84 32x24}\n"
                        + "Text[ref=ref_header]: 全部评论 @{690,118 90x24}\n"
                        + "Link[ref=ref_author]: 小明同学 @{690,180 82x26}\n"
                        + "Text[ref=ref_body]: 有一种老年人玩不懂智能手机一样的无力感 @{690,214 340x42}",
                1280,
                575));
        when(browser.extension_browser_observe(eq("all"), any()))
                .thenAnswer(ignored -> observes.remove());
        when(browser.extension_browser_click_at(anyDouble(), anyDouble(), any())).thenReturn(ok());
        when(browser.extension_browser_press_key(eq("x"), any())).thenReturn(ok());

        String out = tool.lead_browser_douyin_open_first_video_comments(null);

        JsonNode j = mapper.readTree(out);
        assertThat(j.path("ok").asBoolean()).isTrue();
        assertThat(j.path("status").asText()).isEqualTo("DONE_COMMENTS_OPENED");
        assertThat(j.path("attempts").toString()).contains("focus_before_comments_shortcut_x");
        assertThat(j.path("attempts").toString()).doesNotContain("focus_video_before_comments_shortcut");
        ArgumentCaptor<Double> focusX = ArgumentCaptor.forClass(Double.class);
        verify(browser).extension_browser_click_at(focusX.capture(), anyDouble(), any());
        assertThat(focusX.getAllValues()).allSatisfy(x -> assertThat(x).isGreaterThanOrEqualTo(760.0));
        verify(browser, never()).extension_browser_click_at(eq(420.0), eq(420.0), any());
        verify(browser, never()).extension_browser_click_at(eq(1229.0), eq(335.0), any());
    }

    @Test
    void douyinOpenFirstVideoComments_fallsBackForLegacyExtensionWithoutPressKeyHandler() throws Exception {
        Queue<String> observes = new ArrayDeque<>();
        observes.add(observe(
                "https://www.douyin.com/search/openclaw?type=general",
                "openclaw - 抖音搜索",
                "Article[ref=ref_1]: 如何50秒一键部署OpenClaw，全自动赚钱 点赞55.9w @{240,160 360x260}"));
        observes.add(observe(
                "https://www.douyin.com/video/123",
                "如何50秒一键部署OpenClaw",
                "Button[ref=ref_5]: 评论 328 @{1120,420 72x48}\n"
                        + "Button[ref=ref_6]: 分享 @{1120,500 72x48}\n"
                        + "Button[ref=ref_7]: 暂停 @{450,700 72x48}"));
        observes.add(observe(
                "https://www.douyin.com/video/123",
                "如何50秒一键部署OpenClaw",
                "Text[ref=ref_8]: 全部评论 @{760,120 90x28}\n"
                        + "Text[ref=ref_10]: 有一种老年人玩不懂智能手机一样的无力感 @{760,214 340x42}"));
        when(browser.extension_browser_observe(eq("all"), any()))
                .thenAnswer(ignored -> observes.remove());
        when(browser.extension_browser_hover_at(eq(420.0), eq(290.0), any())).thenReturn(ok());
        when(browser.extension_browser_click_at(eq(420.0), eq(290.0), any())).thenReturn(ok());
        when(browser.extension_browser_click_at(eq(486.0), eq(724.0), any())).thenReturn(ok());
        when(browser.extension_browser_press_key(eq("x"), any())).thenReturn(
                "{\"ok\":false,\"code\":\"UNKNOWN_KIND\",\"message\":\"no handler registered for action kind 'press_key'\"}");
        when(browser.extension_browser_type(eq("x"), any())).thenReturn(ok());

        String out = tool.lead_browser_douyin_open_first_video_comments(null);

        JsonNode j = mapper.readTree(out);
        assertThat(j.path("ok").asBoolean()).isTrue();
        assertThat(j.path("status").asText()).isEqualTo("DONE_COMMENTS_OPENED");
        verify(browser).extension_browser_press_key(eq("x"), any());
        verify(browser).extension_browser_type(eq("x"), any());
    }

    @Test
    void douyinOpenFirstVideoComments_prefersCoverOverAuthorProfileLinkWhenOpeningFirstVideo() throws Exception {
        Queue<String> observes = new ArrayDeque<>();
        observes.add(observe(
                "https://www.douyin.com/search/openclaw?type=general",
                "openclaw - 抖音搜索",
                "Link[ref=ref_author_low]: 低赞作者 @{220,135 72x26}\n"
                        + "Image[ref=ref_low]: OpenClaw 3.0部署教程 @{220,168 260x190}\n"
                        + "Text[ref=ref_like_low]: 点赞7.4万 @{220,368 100x28}\n"
                        + "Link[ref=ref_author_high]: 鱼老板 @{560,135 72x26}\n"
                        + "Image[ref=ref_high]: 如何50秒一键部署OpenClaw @{560,168 260x190}\n"
                        + "Text[ref=ref_like_high]: 55.9w点赞 @{560,368 120x28}"));
        observes.add(observe(
                "https://www.douyin.com/video/123",
                "如何50秒一键部署OpenClaw",
                "Button[ref=ref_5]: 评论 328 @{1120,420 72x48}\n"
                        + "Button[ref=ref_6]: 分享 @{1120,500 72x48}\n"
                        + "Button[ref=ref_7]: 暂停 @{450,700 72x48}"));
        observes.add(observe(
                "https://www.douyin.com/video/123",
                "如何50秒一键部署OpenClaw",
                "Text[ref=ref_8]: 全部评论 @{760,120 90x28}\n"
                        + "Link[ref=ref_9]: 小明同学 @{760,180 82x26}\n"
                        + "Text[ref=ref_10]: 有一种老年人玩不懂智能手机一样的无力感 @{760,214 340x42}"));
        when(browser.extension_browser_observe(eq("all"), any()))
                .thenAnswer(ignored -> observes.remove());
        when(browser.extension_browser_hover_at(eq(690.0), eq(263.0), any())).thenReturn(ok());
        when(browser.extension_browser_click_at(eq(690.0), eq(263.0), any())).thenReturn(ok());
        when(browser.extension_browser_click_at(eq(1156.0), eq(444.0), any())).thenReturn(ok());

        String out = tool.lead_browser_douyin_open_first_video_comments(null);

        JsonNode j = mapper.readTree(out);
        assertThat(j.path("ok").asBoolean()).isTrue();
        assertThat(j.path("status").asText()).isEqualTo("DONE_COMMENTS_OPENED");
        verify(browser).extension_browser_hover_at(eq(690.0), eq(263.0), any());
        verify(browser).extension_browser_click_at(eq(690.0), eq(263.0), any());
        verify(browser, never()).extension_browser_click_at(eq(596.0), eq(148.0), any());
        verify(browser, never()).extension_browser_click(eq("关注"), eq("button"), any(), any());
    }

    @Test
    void douyinOpenFirstVideoComments_focusesWideVideoSurfaceBeforeShortcutX() throws Exception {
        Queue<String> observes = new ArrayDeque<>();
        observes.add(observe(
                "https://www.douyin.com/search/openclaw?type=general",
                "openclaw - 抖音搜索",
                "Article[ref=ref_1]: 全网都在养的龙虾 点赞55.9w @{620,160 360x260}",
                2000,
                800));
        observes.add(observe(
                "https://www.douyin.com/video/123",
                "全网都在养的龙虾",
                "Text[ref=ref_like]: 点赞 55.7万 @{1918,338 84x24}\n"
                        + "Text[ref=ref_comment_count]: 151 @{1942,520 36x24}\n"
                        + "Text[ref=ref_collect]: 收藏 11.3万 @{1918,620 92x24}",
                2000,
                800));
        observes.add(observe(
                "https://www.douyin.com/video/123",
                "全网都在养的龙虾",
                "Text[ref=ref_header]: 全部评论 @{1260,120 90x28}\n"
                        + "Link[ref=ref_author]: 小明同学 @{1260,180 82x26}\n"
                        + "Text[ref=ref_body]: 有一种老年人玩不懂智能手机一样的无力感 @{1260,214 340x42}",
                2000,
                800));
        observes.add(observe(
                "https://www.douyin.com/video/123",
                "全网都在养的龙虾",
                "Text[ref=ref_header]: 全部评论 @{1260,120 90x28}\n"
                        + "Link[ref=ref_author]: 小明同学 @{1260,180 82x26}\n"
                        + "Text[ref=ref_body]: 有一种老年人玩不懂智能手机一样的无力感 @{1260,214 340x42}",
                2000,
                800));
        when(browser.extension_browser_observe(eq("all"), any()))
                .thenAnswer(ignored -> observes.remove());
        when(browser.extension_browser_hover_at(eq(800.0), eq(290.0), any())).thenReturn(ok());
        when(browser.extension_browser_click_at(eq(800.0), eq(290.0), any())).thenReturn(ok());
        when(browser.extension_browser_click_at(eq(900.0), eq(400.0), any())).thenReturn(ok());
        when(browser.extension_browser_press_key(eq("x"), any())).thenReturn(ok());

        String out = tool.lead_browser_douyin_open_first_video_comments(null);

        JsonNode j = mapper.readTree(out);
        assertThat(j.path("ok").asBoolean()).isTrue();
        assertThat(j.path("status").asText()).isEqualTo("DONE_COMMENTS_OPENED");
        verify(browser).extension_browser_click_at(eq(900.0), eq(400.0), any());
        verify(browser).extension_browser_press_key(eq("x"), any());
        verify(browser, never()).extension_browser_click(eq("评论"), any(), any(), any());
    }

    @Test
    void douyinOpenFirstVideoComments_doesNotTreatPreloadedCommentDataAsOpenedPanel() throws Exception {
        Queue<String> observes = new ArrayDeque<>();
        observes.add(observe(
                "https://www.douyin.com/search/openclaw?type=general",
                "openclaw - 抖音搜索",
                "Article[ref=ref_1]: 全网都在养的龙虾 点赞55.9w @{240,160 360x260}"));
        observes.add(observe(
                "https://www.douyin.com/video/123",
                "全网都在养的龙虾",
                "Text[ref=ref_like]: 点赞 55.7万 @{1118,338 84x24}\n"
                        + "Text[ref=ref_comment_count]: 151 @{1142,420 36x24}\n"
                        + "Text[ref=ref_collect]: 收藏 11.3万 @{1118,500 92x24}\n"
                        + "Text[ref=hidden_header]: 全部评论 @{-5000,-5000 90x28}\n"
                        + "Link[ref=hidden_author]: 小明同学 @{-5000,-4940 82x26}\n"
                        + "Text[ref=hidden_body]: 有一种老年人玩不懂智能手机一样的无力感 @{-5000,-4900 340x42}\n"
                        + "Button[ref=ref_pause]: 暂停 @{450,700 72x48}"));
        observes.add(observe(
                "https://www.douyin.com/video/123",
                "全网都在养的龙虾",
                "Text[ref=ref_header]: 全部评论 @{760,120 90x28}\n"
                        + "Link[ref=ref_author]: 小明同学 @{760,180 82x26}\n"
                        + "Text[ref=ref_body]: 有一种老年人玩不懂智能手机一样的无力感 @{760,214 340x42}"));
        when(browser.extension_browser_observe(eq("all"), any()))
                .thenAnswer(ignored -> observes.remove());
        when(browser.extension_browser_hover_at(eq(420.0), eq(290.0), any())).thenReturn(ok());
        when(browser.extension_browser_click_at(eq(420.0), eq(290.0), any())).thenReturn(ok());
        when(browser.extension_browser_click_at(eq(486.0), eq(724.0), any())).thenReturn(ok());
        when(browser.extension_browser_press_key(eq("x"), any())).thenReturn(ok());

        String out = tool.lead_browser_douyin_open_first_video_comments(null);

        JsonNode j = mapper.readTree(out);
        assertThat(j.path("ok").asBoolean()).isTrue();
        assertThat(j.path("status").asText()).isEqualTo("DONE_COMMENTS_OPENED");
        verify(browser).extension_browser_press_key(eq("x"), any());
        verify(browser, never()).extension_browser_click(eq("评论"), any(), any(), any());
    }

    @Test
    void douyinOpenFirstVideoComments_doesNotClickCommentButtonWhenPanelAlreadyVisible() throws Exception {
        Queue<String> observes = new ArrayDeque<>();
        observes.add(observe(
                "https://www.douyin.com/search/openclaw?type=general",
                "openclaw - 抖音搜索",
                "Article[ref=ref_1]: 如何50秒一键部署OpenClaw，全自动赚钱 点赞55.9w @{240,160 360x260}"));
        observes.add(observe(
                "https://www.douyin.com/video/123",
                "如何50秒一键部署OpenClaw",
                "Button[ref=ref_5]: 评论 328 @{1120,420 72x48}\n"
                        + "Text[ref=ref_8]: 全部评论 @{760,120 90x28}\n"
                        + "Link[ref=ref_9]: 小明同学 @{760,180 82x26}\n"
                        + "Text[ref=ref_10]: 有一种老年人玩不懂智能手机一样的无力感 @{760,214 340x42}"));
        observes.add(observe(
                "https://www.douyin.com/video/123",
                "如何50秒一键部署OpenClaw",
                "Button[ref=ref_5]: 评论 328 @{1120,420 72x48}\n"
                        + "Text[ref=ref_8]: 全部评论 @{760,120 90x28}\n"
                        + "Link[ref=ref_9]: 小明同学 @{760,180 82x26}\n"
                        + "Text[ref=ref_10]: 有一种老年人玩不懂智能手机一样的无力感 @{760,214 340x42}"));
        when(browser.extension_browser_observe(eq("all"), any()))
                .thenAnswer(ignored -> observes.remove());
        when(browser.extension_browser_hover_at(eq(420.0), eq(290.0), any())).thenReturn(ok());
        when(browser.extension_browser_click_at(eq(420.0), eq(290.0), any())).thenReturn(ok());
        when(browser.extension_browser_click_at(eq(1156.0), eq(444.0), any())).thenReturn(ok());

        String out = tool.lead_browser_douyin_open_first_video_comments(null);

        JsonNode j = mapper.readTree(out);
        assertThat(j.path("ok").asBoolean()).isTrue();
        assertThat(j.path("status").asText()).isEqualTo("DONE_COMMENTS_OPENED");
        verify(browser, never()).extension_browser_click_at(eq(1156.0), eq(444.0), any());
        verify(browser, never()).extension_browser_click(eq("关注"), eq("button"), any(), any());
    }

    @Test
    void douyinOpenFirstVideoComments_clicksNonButtonCommentActionIcon() throws Exception {
        Queue<String> observes = new ArrayDeque<>();
        observes.add(observe(
                "https://www.douyin.com/search/openclaw?type=general",
                "openclaw - 抖音搜索",
                "Article[ref=ref_1]: 如何50秒一键部署OpenClaw，全自动赚钱 点赞55.9w @{240,160 360x260}"));
        observes.add(observe(
                "https://www.douyin.com/video/123",
                "如何50秒一键部署OpenClaw",
                "Text[ref=ref_like]: 点赞 55.7万 @{1118,338 84x24}\n"
                        + "Text[ref=ref_comment]: 评论 151 @{1120,420 72x24}\n"
                        + "Text[ref=ref_collect]: 收藏 11.3万 @{1118,500 92x24}\n"
                        + "Text[ref=ref_share]: 分享 21.7万 @{1118,580 92x24}\n"
                        + "Button[ref=ref_pause]: 暂停 @{450,700 72x48}"));
        observes.add(observe(
                "https://www.douyin.com/video/123",
                "如何50秒一键部署OpenClaw",
                "Text[ref=ref_comment]: 评论 151 @{1120,420 72x24}\n"
                        + "Text[ref=ref_header]: 全部评论 @{760,120 90x28}\n"
                        + "Link[ref=ref_author]: 小明同学 @{760,180 82x26}\n"
                        + "Text[ref=ref_body]: 有一种老年人玩不懂智能手机一样的无力感 @{760,214 340x42}"));
        when(browser.extension_browser_observe(eq("all"), any()))
                .thenAnswer(ignored -> observes.remove());
        when(browser.extension_browser_hover_at(eq(420.0), eq(290.0), any())).thenReturn(ok());
        when(browser.extension_browser_click_at(eq(420.0), eq(290.0), any())).thenReturn(ok());
        when(browser.extension_browser_click_at(eq(1156.0), eq(392.0), any())).thenReturn(ok());

        String out = tool.lead_browser_douyin_open_first_video_comments(null);

        JsonNode j = mapper.readTree(out);
        assertThat(j.path("ok").asBoolean()).isTrue();
        assertThat(j.path("status").asText()).isEqualTo("DONE_COMMENTS_OPENED");
        verify(browser).extension_browser_click_at(eq(1156.0), eq(392.0), any());
        verify(browser, never()).extension_browser_click(eq("关注"), eq("button"), any(), any());
    }

    @Test
    void douyinOpenFirstVideoComments_infersCommentActionBetweenLikeAndCollect() throws Exception {
        Queue<String> observes = new ArrayDeque<>();
        observes.add(observe(
                "https://www.douyin.com/search/openclaw?type=general",
                "openclaw - 抖音搜索",
                "Article[ref=ref_1]: 如何50秒一键部署OpenClaw，全自动赚钱 点赞55.9w @{240,160 360x260}"));
        observes.add(observe(
                "https://www.douyin.com/video/123",
                "如何50秒一键部署OpenClaw",
                "Text[ref=ref_like]: 点赞 55.7万 @{1118,338 84x24}\n"
                        + "Text[ref=ref_comment_count]: 151 @{1142,420 36x24}\n"
                        + "Text[ref=ref_collect]: 收藏 11.3万 @{1118,500 92x24}\n"
                        + "Text[ref=ref_share]: 分享 21.7万 @{1118,580 92x24}\n"
                        + "Button[ref=ref_pause]: 暂停 @{450,700 72x48}"));
        observes.add(observe(
                "https://www.douyin.com/video/123",
                "如何50秒一键部署OpenClaw",
                "Text[ref=ref_header]: 全部评论 @{760,120 90x28}\n"
                        + "Link[ref=ref_author]: 小明同学 @{760,180 82x26}\n"
                        + "Text[ref=ref_body]: 有一种老年人玩不懂智能手机一样的无力感 @{760,214 340x42}"));
        when(browser.extension_browser_observe(eq("all"), any()))
                .thenAnswer(ignored -> observes.remove());
        when(browser.extension_browser_hover_at(eq(420.0), eq(290.0), any())).thenReturn(ok());
        when(browser.extension_browser_click_at(eq(420.0), eq(290.0), any())).thenReturn(ok());
        when(browser.extension_browser_click_at(eq(1160.0), eq(392.0), any())).thenReturn(ok());

        String out = tool.lead_browser_douyin_open_first_video_comments(null);

        JsonNode j = mapper.readTree(out);
        assertThat(j.path("ok").asBoolean()).isTrue();
        assertThat(j.path("status").asText()).isEqualTo("DONE_COMMENTS_OPENED");
        verify(browser).extension_browser_click_at(eq(1160.0), eq(392.0), any());
        verify(browser, never()).extension_browser_click(eq("关注"), eq("button"), any(), any());
    }

    @Test
    void douyinOpenFirstVideoComments_stopsWhenClickDoesNotOpenVideo() throws Exception {
        Queue<String> observes = new ArrayDeque<>();
        observes.add(observe(
                "https://www.douyin.com/search/openclaw?type=general",
                "openclaw - 抖音搜索",
                "Article[ref=ref_1]: 如何50秒一键部署OpenClaw，全自动赚钱 点赞13.1万 @{240,160 360x260}\n"
                        + "Button[ref=ref_2]: 筛选 @{930,90 64x44}"));
        observes.add(observe(
                "https://www.douyin.com/search/openclaw?type=general",
                "openclaw - 抖音搜索",
                "Article[ref=ref_1]: 如何50秒一键部署OpenClaw，全自动赚钱 点赞13.1万 @{240,160 360x260}\n"
                        + "Button[ref=ref_2]: 筛选 @{930,90 64x44}"));
        when(browser.extension_browser_observe(eq("all"), any()))
                .thenAnswer(ignored -> observes.remove());
        when(browser.extension_browser_hover_at(eq(420.0), eq(290.0), any())).thenReturn(ok());
        when(browser.extension_browser_click_at(eq(420.0), eq(290.0), any())).thenReturn(ok());

        String out = tool.lead_browser_douyin_open_first_video_comments(null);

        JsonNode j = mapper.readTree(out);
        assertThat(j.path("ok").asBoolean()).isFalse();
        assertThat(j.path("status").asText()).isEqualTo("VIDEO_OPEN_FAILED");
        assertThat(j.path("message").asText()).contains("没有观察到视频页");
        verify(browser).extension_browser_hover_at(eq(420.0), eq(290.0), any());
        verify(browser).extension_browser_click_at(eq(420.0), eq(290.0), any());
        verify(browser, never()).extension_browser_click(eq("评论"), eq("button"), any(), any());
    }

    @Test
    void douyinOpenFirstVideoComments_retriesAlternateVideoCardPointsWhenFirstClickStaysOnSearch() throws Exception {
        Queue<String> observes = new ArrayDeque<>();
        observes.add(observe(
                "https://www.douyin.com/jingxuan/search/openclaw?type=general",
                "发现更多精彩视频 - 抖音搜索",
                "Text[ref=ref_title_high]: 央视新闻 OpenClaw @{560,135 260x28}\n"
                        + "Image[ref=ref_high]: 全网都在养的龙虾 @{560,168 260x190}\n"
                        + "Text[ref=ref_like_high]: 55.9w点赞 @{560,368 120x28}"));
        observes.add(observe(
                "https://www.douyin.com/jingxuan/search/openclaw?type=general",
                "发现更多精彩视频 - 抖音搜索",
                "Text[ref=ref_title_high]: 央视新闻 OpenClaw @{560,135 260x28}\n"
                        + "Image[ref=ref_high]: 全网都在养的龙虾 @{560,168 260x190}\n"
                        + "Text[ref=ref_like_high]: 55.9w点赞 @{560,368 120x28}"));
        observes.add(observe(
                "https://www.douyin.com/jingxuan/search/openclaw?modal_id=7615879690590375183&type=general",
                "发现更多精彩视频 - 抖音搜索",
                "Text[ref=ref_comment]: 151 @{1229,335 20x18}\n"
                        + "Text[ref=ref_like]: 55.7万 @{1221,276 35x18}\n"
                        + "Text[ref=ref_collect]: 11.3万 @{1221,394 35x18}\n"
                        + "Video[ref=ref_video]: @{0,0 1280x527}",
                1280,
                575));
        observes.add(observe(
                "https://www.douyin.com/jingxuan/search/openclaw?modal_id=7615879690590375183&type=general",
                "发现更多精彩视频 - 抖音搜索",
                "Text[ref=ref_detail_tab]: 详情 @{660,84 32x24}\n"
                        + "Text[ref=ref_works_tab]: TA的作品 @{735,84 72x24}\n"
                        + "Text[ref=ref_comment_tab]: 评论 @{842,84 32x24}\n"
                        + "Text[ref=ref_ai_tab]: 问AI @{918,84 36x24}\n"
                        + "Link[ref=ref_author_1]: 川流不息 @{690,150 90x26}\n"
                        + "Text[ref=ref_comment_1]: 这个工具对新手来说真的有点复杂 @{690,186 320x42}",
                1280,
                575));
        when(browser.extension_browser_observe(eq("all"), any()))
                .thenAnswer(ignored -> observes.remove());
        when(browser.extension_browser_hover_at(eq(690.0), eq(263.0), any())).thenReturn(ok());
        when(browser.extension_browser_click_at(eq(690.0), eq(263.0), any())).thenReturn(ok());
        when(browser.extension_browser_click_at(eq(690.0), eq(149.0), any())).thenReturn(ok());
        when(browser.extension_browser_click_at(eq(420.0), eq(420.0), any())).thenReturn(ok());
        when(browser.extension_browser_press_key(eq("x"), any())).thenReturn(ok());

        String out = tool.lead_browser_douyin_open_first_video_comments(null);

        JsonNode j = mapper.readTree(out);
        assertThat(j.path("ok").asBoolean()).isTrue();
        assertThat(j.path("status").asText()).isEqualTo("DONE_COMMENTS_OPENED");
        verify(browser).extension_browser_click_at(eq(690.0), eq(263.0), any());
        verify(browser, atLeastOnce()).extension_browser_click_at(anyDouble(), anyDouble(), any());
    }

    @Test
    void douyinCommentLead_opensFirstVideo_matchesCommentAndOpensAuthorProfile() throws Exception {
        Queue<String> observes = new ArrayDeque<>();
        observes.add(observe(
                "https://www.douyin.com/search/openclaw?type=general",
                "openclaw - 抖音搜索",
                "Textbox[ref=ref_1]: openclaw @{700,5 520x60}\n"
                        + "Button[ref=ref_2]: 筛选 @{930,90 64x44}\n"
                        + "Article[ref=ref_3]: 如何50秒一键部署OpenClaw，全自动赚钱 点赞13.1万 @{240,160 360x260}\n"
                        + "Article[ref=ref_4]: AI龙虾-AI智能体OpenClaw史诗级广告 @{630,160 360x260}"));
        observes.add(observe(
                "https://www.douyin.com/video/123",
                "如何50秒一键部署OpenClaw",
                "Button[ref=ref_5]: 评论 328 @{1120,420 72x48}\n"
                        + "Button[ref=ref_6]: 分享 @{1120,500 72x48}\n"
                        + "Button[ref=ref_7]: 暂停 @{450,700 72x48}\n"
                        + "Text[ref=ref_6]: 如何50秒一键部署OpenClaw，全自动赚钱 @{120,620 420x32}"));
        observes.add(observe(
                "https://www.douyin.com/video/123",
                "如何50秒一键部署OpenClaw",
                "Button[ref=ref_5]: 评论 328 @{1120,420 72x48}\n"
                        + "Text[ref=ref_7]: 全部评论 @{760,120 90x28}\n"
                        + "Link[ref=ref_8]: 小明同学 @{760,180 82x26}\n"
                        + "Text[ref=ref_9]: 有一种老年人玩不懂智能手机一样的无力感，教程太快了 @{760,214 380x42}\n"
                        + "Link[ref=ref_10]: 另一个用户 @{760,292 92x26}\n"
                        + "Text[ref=ref_11]: 求完整教程 @{760,326 140x24}"));
        observes.add(observe(
                "https://www.douyin.com/user/MS4wLjABAAAA",
                "小明同学的主页",
                "Text[ref=ref_12]: 小明同学 @{520,160 100x30}\n"
                        + "Button[ref=ref_13]: 关注 @{920,160 88x36}\n"
                        + "Text[ref=ref_14]: 粉丝 128 @{520,220 120x28}\n"
                        + "Text[ref=ref_15]: 作品 12 @{660,220 110x28}"));
        when(browser.extension_browser_observe(eq("all"), any()))
                .thenAnswer(ignored -> observes.remove());
        when(browser.extension_browser_hover_at(anyDouble(), anyDouble(), any())).thenReturn(ok());
        when(browser.extension_browser_click_at(anyDouble(), anyDouble(), any())).thenReturn(ok());

        String out = tool.lead_browser_douyin_open_first_video_match_comment_user(
                "有一种和“老年人玩不懂智能手机”一样的无力感", 2, null);

        JsonNode j = mapper.readTree(out);
        assertThat(j.path("ok").asBoolean()).isTrue();
        assertThat(j.path("status").asText()).isEqualTo("DONE_PROFILE_OPENED");
        assertThat(j.path("matched_comment").asText()).contains("老年人玩不懂智能手机");
        assertThat(j.path("matched_author").asText()).isEqualTo("小明同学");
        assertThat(j.path("profile_url").asText()).contains("/user/");
        assertThat(j.path("next_action").asText()).contains("extension_browser_click");
        assertThat(j.path("next_action").asText()).contains("关注");
        verify(browser).extension_browser_click_at(eq(420.0), eq(290.0), any());
        verify(browser).extension_browser_click_at(eq(1156.0), eq(444.0), any());
        ArgumentCaptor<Double> parkX = ArgumentCaptor.forClass(Double.class);
        verify(browser).extension_browser_hover_at_linear(parkX.capture(), anyDouble(), any());
        assertThat(parkX.getAllValues()).allSatisfy(x -> assertThat(x).isGreaterThanOrEqualTo(760.0));
        verify(browser).extension_browser_click_at_linear(eq(801.0), eq(193.0), any());
        verify(browser, never()).extension_browser_click_at(eq(801.0), eq(193.0), any());
        verify(browser, never()).extension_browser_click(eq("关注"), eq("button"), any(), any());
    }

    @Test
    void douyinCommentLead_returnsNoMatchWithCandidateComments() throws Exception {
        Queue<String> observes = new ArrayDeque<>();
        observes.add(observe(
                "https://www.douyin.com/search/openclaw?type=general",
                "openclaw - 抖音搜索",
                "Article[ref=ref_1]: 如何50秒一键部署OpenClaw，全自动赚钱 点赞13.1万 @{240,160 360x260}"));
        observes.add(observe(
                "https://www.douyin.com/video/123",
                "如何50秒一键部署OpenClaw",
                "Button[ref=ref_comment]: 评论 128 @{1120,420 72x48}\n"
                        + "Text[ref=ref_2]: 全部评论 @{760,120 90x28}\n"
                        + "Link[ref=ref_3]: 小红 @{760,180 60x26}\n"
                        + "Text[ref=ref_4]: 这个教程很清楚，已经跑通了 @{760,214 280x32}"));
        observes.add(observe(
                "https://www.douyin.com/video/123",
                "如何50秒一键部署OpenClaw",
                "Button[ref=ref_comment]: 评论 128 @{1120,420 72x48}\n"
                        + "Text[ref=ref_2]: 全部评论 @{760,120 90x28}\n"
                        + "Link[ref=ref_3]: 小红 @{760,180 60x26}\n"
                        + "Text[ref=ref_4]: 这个教程很清楚，已经跑通了 @{760,214 280x32}"));
        observes.add(observe(
                "https://www.douyin.com/video/123",
                "如何50秒一键部署OpenClaw",
                "Text[ref=ref_5]: 全部评论 @{760,120 90x28}\n"
                        + "Link[ref=ref_6]: 小蓝 @{760,180 60x26}\n"
                        + "Text[ref=ref_7]: 有没有下一集，想看更多案例 @{760,214 280x32}"));
        when(browser.extension_browser_observe(eq("all"), any()))
                .thenAnswer(ignored -> observes.remove());
        when(browser.extension_browser_hover_at(eq(420.0), eq(290.0), any())).thenReturn(ok());
        when(browser.extension_browser_click_at(eq(420.0), eq(290.0), any())).thenReturn(ok());
        when(browser.extension_browser_click_at(eq(1156.0), eq(444.0), any())).thenReturn(ok());
        when(browser.extension_browser_hover_at(anyDouble(), anyDouble(), any())).thenReturn(ok());
        when(browser.extension_browser_scroll_at(eq("down"), eq(650), anyDouble(), anyDouble(), any()))
                .thenReturn(ok());

        String out = tool.lead_browser_douyin_open_first_video_match_comment_user(
                "有一种和“老年人玩不懂智能手机”一样的无力感", 1, null);

        JsonNode j = mapper.readTree(out);
        assertThat(j.path("ok").asBoolean()).isFalse();
        assertThat(j.path("status").asText()).isEqualTo("COMMENT_MATCH_NOT_FOUND");
        assertThat(j.path("candidate_comments")).isNotEmpty();
        verify(browser).extension_browser_scroll_at(eq("down"), eq(650), anyDouble(), anyDouble(), any());
        verify(browser, never()).extension_browser_scroll(eq("down"), eq(650), any());
        verify(browser, never()).extension_browser_click(eq("关注"), eq("button"), any(), any());
    }

    @Test
    void douyinCommentLeadAfterCommentDetailKeepsMouseParkedInCommentPanel() throws Exception {
        Queue<String> observes = new ArrayDeque<>();
        observes.add(observe(
                "https://www.douyin.com/jingxuan/search/openclaw?modal_id=7615879690590375183&type=general",
                "发现更多精彩视频 - 抖音搜索",
                "Text[ref=ref_detail_tab]: 详情 @{660,84 32x24}\n"
                        + "Text[ref=ref_comment_tab]: 评论 @{842,84 32x24}\n"
                        + "Text[ref=ref_ai_tab]: 问AI @{918,84 36x24}\n"
                        + "Button[ref=ref_comment_count]: 评论 151 @{1220,335 72x48}\n"
                        + "Link[ref=ref_author]: 小红 @{690,180 60x26}\n"
                        + "Text[ref=ref_comment]: 这个教程很清楚，已经跑通了 @{690,214 280x32}\n"
                        + "Video[ref=ref_video]: @{0,0 1280x527}",
                1280,
                575));
        observes.add(observe(
                "https://www.douyin.com/jingxuan/search/openclaw?modal_id=7615879690590375183&type=general&comment_id=abc",
                "发现更多精彩视频 - 抖音搜索",
                "Text[ref=ref_detail_tab]: 详情 @{690,84 32x24}\n"
                        + "Text[ref=ref_comment_tab]: 评论 @{842,84 32x24}\n"
                        + "Text[ref=ref_reply_title]: 相关回复 @{690,118 80x24}\n"
                        + "Link[ref=ref_author_2]: 小蓝 @{690,180 60x26}\n"
                        + "Text[ref=ref_comment_2]: 有没有下一集，想看更多案例 @{690,214 280x32}\n"
                        + "Video[ref=ref_video]: @{0,0 1280x527}",
                1280,
                575));
        when(browser.extension_browser_observe(eq("all"), any()))
                .thenAnswer(ignored -> observes.remove());
        when(browser.extension_browser_scroll_at(eq("down"), eq(650), anyDouble(), anyDouble(), any()))
                .thenReturn(ok());

        String out = tool.lead_browser_douyin_open_first_video_match_comment_user(
                "有一种和“老年人玩不懂智能手机”一样的无力感", 1, null);

        JsonNode j = mapper.readTree(out);
        assertThat(j.path("ok").asBoolean()).isFalse();
        assertThat(j.path("status").asText()).isEqualTo("COMMENT_MATCH_NOT_FOUND");
        ArgumentCaptor<Double> parkX = ArgumentCaptor.forClass(Double.class);
        verify(browser, atLeastOnce()).extension_browser_hover_at_linear(parkX.capture(), anyDouble(), any());
        assertThat(parkX.getAllValues()).allSatisfy(x -> assertThat(x).isGreaterThanOrEqualTo(760.0));
        verify(browser, atLeastOnce()).extension_browser_scroll_at(
                eq("down"), eq(650), anyDouble(), anyDouble(), any());
        verify(browser, never()).extension_browser_scroll(eq("down"), eq(650), any());
        verify(browser, never()).extension_browser_click_at(eq(420.0), eq(420.0), any());
    }

    @Test
    void douyinMatchCommentFollowOpenDmTypeDraft_matchesAuthorAndCommentThenTypesInActiveTab() throws Exception {
        Queue<String> mainObserves = new ArrayDeque<>();
        mainObserves.add(observe(
                "https://www.douyin.com/jingxuan/search/openclaw?modal_id=7615879690590375183&type=general",
                "发现更多精彩视频 - 抖音搜索",
                "Text[ref=ref_detail_tab]: 详情 @{1252,21 36x24}\n"
                        + "Text[ref=ref_works_tab]: TA的作品 @{1346,21 78x24}\n"
                        + "Text[ref=ref_comment_tab]: 评论 @{1480,21 36x24}\n"
                        + "Text[ref=ref_ai_tab]: 问AI @{1574,21 40x24}\n"
                        + "Text[ref=ref_count]: 全部评论(151) @{1252,103 130x24}\n"
                        + "Link[ref=ref_author_1]: 花里胡哨 @{1302,133 90x26}\n"
                        + "Text[ref=ref_comment_1]: 普通人先别折腾这些工具 @{1302,169 280x42}\n"
                        + "Link[ref=ref_author_2]: 木马 @{1302,244 56x26}\n"
                        + "Text[ref=ref_comment_2]: 对于99%的人用豆包就行了。 @{1302,280 320x42}\n"
                        + "Text[ref=ref_end]: 暂时没有更多 @{1302,720 120x24}",
                1760,
                783));
        Queue<String> activeObserves = new ArrayDeque<>();
        activeObserves.add(observe(
                "https://www.douyin.com/user/MS4wLjABMOCK",
                "木马的主页",
                "Text[ref=ref_name]: 木马 @{520,150 80x30}\n"
                        + "Button[ref=ref_follow]: 关注 @{920,160 88x36}\n"
                        + "Button[ref=ref_dm]: 私信 @{1018,160 88x36}\n"
                        + "Text[ref=ref_fans]: 粉丝 128 @{520,220 120x28}\n"
                        + "Text[ref=ref_likes]: 获赞 2048 @{660,220 110x28}"));
        activeObserves.add(observe(
                "https://www.douyin.com/user/MS4wLjABMOCK",
                "木马的主页",
                "Text[ref=ref_name]: 木马 @{520,150 80x30}\n"
                        + "Button[ref=ref_followed]: 已关注 @{920,160 88x36}\n"
                        + "Button[ref=ref_dm]: 私信 @{1018,160 88x36}\n"
                        + "Text[ref=ref_fans]: 粉丝 128 @{520,220 120x28}\n"
                        + "Text[ref=ref_likes]: 获赞 2048 @{660,220 110x28}"));
        activeObserves.add(observe(
                "https://www.douyin.com/im/conversation/456",
                "私信",
                "Text[ref=ref_peer]: 木马 @{520,80 80x30}\n"
                        + "Textbox[ref=ref_input]: 输入消息 @{520,520 420x44}\n"
                        + "Button[ref=ref_send]: 发送 @{960,520 72x44}"));
        activeObserves.add(observe(
                "https://www.douyin.com/im/conversation/456",
                "私信",
                "Text[ref=ref_peer]: 木马 @{520,80 80x30}\n"
                        + "Textbox[ref=ref_input]: 你好 @{520,520 420x44}\n"
                        + "Button[ref=ref_send]: 发送 @{960,520 72x44}"));
        when(browser.extension_browser_observe(eq("all"), any()))
                .thenAnswer(ignored -> mainObserves.remove());
        when(browser.extension_browser_observe_active(eq("all"), any()))
                .thenAnswer(ignored -> activeObserves.remove());
        when(browser.extension_browser_click_at_linear(eq(1330.0), eq(257.0), any())).thenReturn(ok());
        when(browser.extension_browser_click_at_active(eq(964.0), eq(178.0), any())).thenReturn(ok());
        when(browser.extension_browser_click_at_active(eq(1062.0), eq(178.0), any())).thenReturn(ok());
        when(browser.extension_browser_type_at_active(eq("你好"), any(), any())).thenReturn(ok());

        String out = tool.lead_browser_douyin_match_comment_follow_open_dm_type_draft(
                "他叫木马",
                "对于99%的人用豆包就行了。",
                "你好",
                8,
                null);

        JsonNode j = mapper.readTree(out);
        assertThat(j.path("ok").asBoolean()).isTrue();
        assertThat(j.path("status").asText()).isEqualTo("DONE_DM_DRAFT_TYPED");
        assertThat(j.path("matched_author").asText()).contains("木马");
        assertThat(j.path("matched_comment").asText()).contains("豆包");
        assertThat(j.path("follow_clicked").asBoolean()).isTrue();
        assertThat(j.path("dm_opened").asBoolean()).isTrue();
        assertThat(j.path("dm_draft_typed").asBoolean()).isTrue();
        verify(browser).extension_browser_click_at_linear(eq(1330.0), eq(257.0), any());
        verify(browser).extension_browser_type_at_active(eq("你好"), any(), any());
        verify(browser, never()).extension_browser_click_at_active(eq(996.0), eq(542.0), any());
    }

    @Test
    void douyinMatchCommentFollowOpenDmTypeDraft_matchesCommentWhenAuthorHintIsOnlyWeakContext() throws Exception {
        Queue<String> mainObserves = new ArrayDeque<>();
        mainObserves.add(observe(
                "https://www.douyin.com/jingxuan/search/openclaw?modal_id=7615879690590375183&type=general",
                "发现更多精彩视频 - 抖音搜索",
                "Text[ref=ref_detail_tab]: 详情 @{1252,21 36x24}\n"
                        + "Text[ref=ref_works_tab]: TA的作品 @{1346,21 78x24}\n"
                        + "Text[ref=ref_comment_tab]: 评论 @{1480,21 36x24}\n"
                        + "Text[ref=ref_ai_tab]: 问AI @{1574,21 40x24}\n"
                        + "Text[ref=ref_count]: 全部评论(151) @{1252,103 130x24}\n"
                        + "Link[ref=ref_author_1]: 木马 @{1302,133 56x26}\n"
                        + "Text[ref=ref_comment_1]: 普通人先别折腾这些工具 @{1302,169 280x42}\n"
                        + "Link[ref=ref_author_2]: Ly @{1302,244 32x26}\n"
                        + "Text[ref=ref_comment_2]: 对于99%的人用豆包就行了。 @{1302,280 320x42}\n"
                        + "Text[ref=ref_end]: 暂时没有更多 @{1302,720 120x24}",
                1760,
                783));
        Queue<String> activeObserves = new ArrayDeque<>();
        activeObserves.add(observe(
                "https://www.douyin.com/user/MS4wLjABLY",
                "Ly的主页",
                "Text[ref=ref_name]: Ly @{520,150 80x30}\n"
                        + "Button[ref=ref_follow]: 关注 @{920,160 88x36}\n"
                        + "Button[ref=ref_dm]: 私信 @{1018,160 88x36}\n"
                        + "Text[ref=ref_fans]: 粉丝 128 @{520,220 120x28}\n"
                        + "Text[ref=ref_likes]: 获赞 2048 @{660,220 110x28}"));
        activeObserves.add(observe(
                "https://www.douyin.com/user/MS4wLjABLY",
                "Ly的主页",
                "Text[ref=ref_name]: Ly @{520,150 80x30}\n"
                        + "Button[ref=ref_followed]: 已关注 @{920,160 88x36}\n"
                        + "Button[ref=ref_dm]: 私信 @{1018,160 88x36}\n"
                        + "Text[ref=ref_fans]: 粉丝 128 @{520,220 120x28}\n"
                        + "Text[ref=ref_likes]: 获赞 2048 @{660,220 110x28}"));
        activeObserves.add(observe(
                "https://www.douyin.com/im/conversation/ly",
                "私信",
                "Text[ref=ref_peer]: Ly @{520,80 80x30}\n"
                        + "Textbox[ref=ref_input]: 输入消息 @{520,520 420x44}\n"
                        + "Button[ref=ref_send]: 发送 @{960,520 72x44}"));
        activeObserves.add(observe(
                "https://www.douyin.com/im/conversation/ly",
                "私信",
                "Text[ref=ref_peer]: Ly @{520,80 80x30}\n"
                        + "Textbox[ref=ref_input]: 你好 @{520,520 420x44}\n"
                        + "Button[ref=ref_send]: 发送 @{960,520 72x44}"));
        when(browser.extension_browser_observe(eq("all"), any()))
                .thenAnswer(ignored -> mainObserves.remove());
        when(browser.extension_browser_observe_active(eq("all"), any()))
                .thenAnswer(ignored -> activeObserves.remove());
        when(browser.extension_browser_click_at_linear(eq(1318.0), eq(257.0), any())).thenReturn(ok());
        when(browser.extension_browser_click_at_active(eq(964.0), eq(178.0), any())).thenReturn(ok());
        when(browser.extension_browser_click_at_active(eq(1062.0), eq(178.0), any())).thenReturn(ok());
        when(browser.extension_browser_type_at_active(eq("你好"), any(), any())).thenReturn(ok());

        String out = tool.lead_browser_douyin_match_comment_follow_open_dm_type_draft(
                "他叫木马",
                "对于99%的人用豆包就行了。",
                "你好",
                8,
                null);

        JsonNode j = mapper.readTree(out);
        assertThat(j.path("ok").asBoolean()).isTrue();
        assertThat(j.path("status").asText()).isEqualTo("DONE_DM_DRAFT_TYPED");
        assertThat(j.path("matched_author").asText()).contains("Ly");
        assertThat(j.path("matched_author").asText()).doesNotContain("木马");
        assertThat(j.path("matched_comment").asText()).contains("豆包");
        assertThat(j.path("match_score").asInt()).isGreaterThanOrEqualTo(60);
        assertThat(j.path("follow_clicked").asBoolean()).isTrue();
        assertThat(j.path("dm_opened").asBoolean()).isTrue();
        assertThat(j.path("dm_draft_typed").asBoolean()).isTrue();
        verify(browser).extension_browser_click_at_linear(eq(1318.0), eq(257.0), any());
        verify(browser).extension_browser_type_at_active(eq("你好"), any(), any());
    }

    @Test
    void douyinMatchCommentFollowOpenDmTypeDraft_neverTypesDraftIntoMateClawBackendActiveTab() throws Exception {
        Queue<String> mainObserves = new ArrayDeque<>();
        String commentsPage = observe(
                "https://www.douyin.com/jingxuan/search/openclaw?modal_id=7615879690590375183&type=general",
                "发现更多精彩视频 - 抖音搜索",
                "Text[ref=ref_detail_tab]: 详情 @{1252,21 36x24}\n"
                        + "Text[ref=ref_comment_tab]: 评论 @{1480,21 36x24}\n"
                        + "Text[ref=ref_count]: 全部评论(151) @{1252,103 130x24}\n"
                        + "Link[ref=ref_author]: Ly @{1302,244 32x26}\n"
                        + "Text[ref=ref_comment]: 对于99%的人用豆包就行了。 @{1302,280 320x42}\n"
                        + "Text[ref=ref_end]: 暂时没有更多 @{1302,720 120x24}",
                1760,
                783);
        mainObserves.add(commentsPage);
        mainObserves.add(commentsPage);
        mainObserves.add(commentsPage);
        mainObserves.add(commentsPage);
        mainObserves.add(commentsPage);
        Queue<String> activeObserves = new ArrayDeque<>();
        String mateClawChat = observe(
                "http://localhost:5173/login",
                "MateClaw",
                "Text[ref=ref_agent]: 获客专家 @{900,180 120x30}\n"
                        + "Textbox[ref=ref_chat_input]: 输入消息 @{920,820 420x54}\n"
                        + "Button[ref=ref_send]: 发送 @{1450,820 72x44}\n"
                        + "Text[ref=ref_summary]: 任务已经执行完成 @{980,300 220x40}",
                2048,
                1000);
        activeObserves.add(mateClawChat);
        activeObserves.add(mateClawChat);
        activeObserves.add(mateClawChat);
        activeObserves.add(mateClawChat);
        when(browser.extension_browser_observe(eq("all"), any()))
                .thenAnswer(ignored -> mainObserves.remove());
        when(browser.extension_browser_observe_active(eq("all"), any()))
                .thenAnswer(ignored -> activeObserves.remove());
        when(browser.extension_browser_click_at_linear(eq(1318.0), eq(257.0), any())).thenReturn(ok());

        String out = tool.lead_browser_douyin_match_comment_follow_open_dm_type_draft(
                "",
                "对于99%的人用豆包就行了。",
                "你好",
                0,
                null);

        JsonNode j = mapper.readTree(out);
        assertThat(j.path("ok").asBoolean()).isFalse();
        assertThat(j.path("status").asText()).isEqualTo("PROFILE_OPEN_FAILED");
        assertThat(j.path("matched_author").asText()).contains("Ly");
        assertThat(j.path("matched_comment").asText()).contains("豆包");
        verify(browser).extension_browser_click_at_linear(eq(1318.0), eq(257.0), any());
        verify(browser, never()).extension_browser_click_at_active(anyDouble(), anyDouble(), any());
        verify(browser, never()).extension_browser_type_at_active(any(), any(), any());
        verify(browser, never()).extension_browser_type_at(any(), any(), any());
        verify(browser, never()).extension_browser_type(any(), any());
    }

    @Test
    void douyinMatchCommentFollowOpenDmTypeDraft_doesNotTypeWhenDmObservationFallsBackToMateClawTab() throws Exception {
        Queue<String> mainObserves = new ArrayDeque<>();
        mainObserves.add(observe(
                "https://www.douyin.com/jingxuan/search/openclaw?modal_id=7615879690590375183&type=general",
                "发现更多精彩视频 - 抖音搜索",
                "Text[ref=ref_comment_tab]: 评论 @{1480,21 36x24}\n"
                        + "Text[ref=ref_count]: 全部评论(151) @{1252,103 130x24}\n"
                        + "Link[ref=ref_author]: Ly @{1302,244 32x26}\n"
                        + "Text[ref=ref_comment]: 对于99%的人用豆包就行了。 @{1302,280 320x42}\n"
                        + "Text[ref=ref_end]: 暂时没有更多 @{1302,720 120x24}",
                1760,
                783));
        Queue<String> activeObserves = new ArrayDeque<>();
        activeObserves.add(observe(
                "https://www.douyin.com/user/MS4wLjABLY",
                "Ly的主页",
                "Text[ref=ref_name]: Ly @{520,150 80x30}\n"
                        + "Button[ref=ref_follow]: 关注 @{920,160 88x36}\n"
                        + "Button[ref=ref_dm]: 私信 @{1018,160 88x36}\n"
                        + "Text[ref=ref_fans]: 粉丝 128 @{520,220 120x28}"));
        activeObserves.add(observe(
                "https://www.douyin.com/user/MS4wLjABLY",
                "Ly的主页",
                "Text[ref=ref_name]: Ly @{520,150 80x30}\n"
                        + "Button[ref=ref_followed]: 已关注 @{920,160 88x36}\n"
                        + "Button[ref=ref_dm]: 私信 @{1018,160 88x36}\n"
                        + "Text[ref=ref_fans]: 粉丝 128 @{520,220 120x28}"));
        String mateClawChat = observe(
                "http://localhost:5173/login",
                "MateClaw",
                "Textbox[ref=ref_chat_input]: 输入消息 @{920,820 420x54}\n"
                        + "Button[ref=ref_send]: 发送 @{1450,820 72x44}",
                2048,
                1000);
        activeObserves.add(mateClawChat);
        activeObserves.add(mateClawChat);
        activeObserves.add(mateClawChat);
        activeObserves.add(mateClawChat);
        activeObserves.add(mateClawChat);
        when(browser.extension_browser_observe(eq("all"), any()))
                .thenAnswer(ignored -> mainObserves.isEmpty() ? mateClawChat : mainObserves.remove());
        when(browser.extension_browser_observe_active(eq("all"), any()))
                .thenAnswer(ignored -> activeObserves.remove());
        when(browser.extension_browser_click_at_linear(eq(1318.0), eq(257.0), any())).thenReturn(ok());
        when(browser.extension_browser_click_at_active(eq(964.0), eq(178.0), any())).thenReturn(ok());
        when(browser.extension_browser_click_at_active(eq(1062.0), eq(178.0), any())).thenReturn(ok());

        String out = tool.lead_browser_douyin_match_comment_follow_open_dm_type_draft(
                "",
                "对于99%的人用豆包就行了。",
                "你好",
                0,
                null);

        JsonNode j = mapper.readTree(out);
        assertThat(j.path("ok").asBoolean()).isFalse();
        assertThat(j.path("status").asText()).isEqualTo("PARTIAL_PROFILE_FOLLOW_ATTEMPTED");
        assertThat(j.path("follow_attempted").asBoolean()).isTrue();
        assertThat(j.path("follow_clicked").asBoolean()).isTrue();
        assertThat(j.path("dm_opened").asBoolean()).isFalse();
        assertThat(j.path("dm_draft_typed").asBoolean()).isFalse();
        verify(browser).extension_browser_click_at_active(eq(964.0), eq(178.0), any());
        verify(browser).extension_browser_click_at_active(eq(1062.0), eq(178.0), any());
        verify(browser, never()).extension_browser_type_at_active(any(), any(), any());
        verify(browser, never()).extension_browser_type_at(any(), any(), any());
        verify(browser, never()).extension_browser_type(any(), any());
    }

    @Test
    void douyinMatchCommentFollowOpenDmTypeDraft_continuesAcrossMultipleSemanticMatchesInNewTabs() throws Exception {
        Queue<String> mainObserves = new ArrayDeque<>();
        mainObserves.add(observe(
                "https://www.douyin.com/jingxuan/search/openclaw?modal_id=7615879690590375183&type=general",
                "发现更多精彩视频 - 抖音搜索",
                "Text[ref=ref_detail_tab]: 详情 @{1252,21 36x24}\n"
                        + "Text[ref=ref_works_tab]: TA的作品 @{1346,21 78x24}\n"
                        + "Text[ref=ref_comment_tab]: 评论 @{1480,21 36x24}\n"
                        + "Text[ref=ref_ai_tab]: 问AI @{1574,21 40x24}\n"
                        + "Text[ref=ref_count]: 全部评论(151) @{1252,103 130x24}\n"
                        + "Link[ref=ref_author_1]: Ly @{1302,244 32x26}\n"
                        + "Text[ref=ref_comment_1]: 对于99%的人用豆包就行了。 @{1302,280 320x42}\n"
                        + "Link[ref=ref_author_2]: 宝宝甜妹 @{1302,360 90x26}\n"
                        + "Text[ref=ref_comment_2]: 九成以上普通人用豆包就行了，没必要折腾这个。 @{1302,396 420x42}\n"
                        + "Text[ref=ref_end]: 暂时没有更多 @{1302,720 120x24}",
                1760,
                783));

        Queue<String> activeObserves = new ArrayDeque<>();
        activeObserves.add(observe(
                "https://www.douyin.com/user/MS4wLjABLY",
                "Ly的主页",
                "Text[ref=ref_name]: Ly @{520,150 80x30}\n"
                        + "Button[ref=ref_follow]: 关注 @{920,160 88x36}\n"
                        + "Button[ref=ref_dm]: 私信 @{1018,160 88x36}\n"
                        + "Text[ref=ref_fans]: 粉丝 128 @{520,220 120x28}"));
        activeObserves.add(observe(
                "https://www.douyin.com/user/MS4wLjABLY",
                "Ly的主页",
                "Text[ref=ref_name]: Ly @{520,150 80x30}\n"
                        + "Button[ref=ref_followed]: 已关注 @{920,160 88x36}\n"
                        + "Button[ref=ref_dm]: 私信 @{1018,160 88x36}\n"
                        + "Text[ref=ref_fans]: 粉丝 128 @{520,220 120x28}"));
        activeObserves.add(observe(
                "https://www.douyin.com/im/conversation/ly",
                "私信",
                "Text[ref=ref_peer]: Ly @{520,80 80x30}\n"
                        + "Textbox[ref=ref_input]: 输入消息 @{520,520 420x44}\n"
                        + "Button[ref=ref_send]: 发送 @{960,520 72x44}"));
        activeObserves.add(observe(
                "https://www.douyin.com/im/conversation/ly",
                "私信",
                "Text[ref=ref_peer]: Ly @{520,80 80x30}\n"
                        + "Textbox[ref=ref_input]: 你好 @{520,520 420x44}\n"
                        + "Button[ref=ref_send]: 发送 @{960,520 72x44}"));
        activeObserves.add(observe(
                "https://www.douyin.com/user/MS4wLjABBAOBAO",
                "宝宝甜妹的主页",
                "Text[ref=ref_name]: 宝宝甜妹 @{520,150 120x30}\n"
                        + "Button[ref=ref_follow]: 关注 @{920,160 88x36}\n"
                        + "Button[ref=ref_dm]: 私信 @{1018,160 88x36}\n"
                        + "Text[ref=ref_fans]: 粉丝 66 @{520,220 120x28}"));
        activeObserves.add(observe(
                "https://www.douyin.com/user/MS4wLjABBAOBAO",
                "宝宝甜妹的主页",
                "Text[ref=ref_name]: 宝宝甜妹 @{520,150 120x30}\n"
                        + "Button[ref=ref_followed]: 已关注 @{920,160 88x36}\n"
                        + "Button[ref=ref_dm]: 私信 @{1018,160 88x36}\n"
                        + "Text[ref=ref_fans]: 粉丝 66 @{520,220 120x28}"));
        activeObserves.add(observe(
                "https://www.douyin.com/im/conversation/baobao",
                "私信",
                "Text[ref=ref_peer]: 宝宝甜妹 @{520,80 120x30}\n"
                        + "Textbox[ref=ref_input]: 输入消息 @{520,520 420x44}\n"
                        + "Button[ref=ref_send]: 发送 @{960,520 72x44}"));
        activeObserves.add(observe(
                "https://www.douyin.com/im/conversation/baobao",
                "私信",
                "Text[ref=ref_peer]: 宝宝甜妹 @{520,80 120x30}\n"
                        + "Textbox[ref=ref_input]: 你好 @{520,520 420x44}\n"
                        + "Button[ref=ref_send]: 发送 @{960,520 72x44}"));

        when(browser.extension_browser_observe(eq("all"), any()))
                .thenAnswer(ignored -> mainObserves.remove());
        when(browser.extension_browser_observe_active(eq("all"), any()))
                .thenAnswer(ignored -> activeObserves.remove());
        when(browser.extension_browser_click_at_linear(eq(1318.0), eq(257.0), any())).thenReturn(ok());
        when(browser.extension_browser_click_at_linear(eq(1347.0), eq(373.0), any())).thenReturn(ok());
        when(browser.extension_browser_click_at_active(eq(964.0), eq(178.0), any())).thenReturn(ok());
        when(browser.extension_browser_click_at_active(eq(1062.0), eq(178.0), any())).thenReturn(ok());
        when(browser.extension_browser_type_at_active(eq("你好"), any(), any())).thenReturn(ok());

        String out = tool.lead_browser_douyin_match_comment_follow_open_dm_type_draft(
                "他叫木马",
                "对于99%的人用豆包就行了。",
                "你好",
                8,
                null);

        JsonNode j = mapper.readTree(out);
        assertThat(j.path("ok").asBoolean()).isTrue();
        assertThat(j.path("status").asText()).isEqualTo("DONE_DM_DRAFT_TYPED");
        assertThat(j.path("engagement_count").asInt()).isEqualTo(2);
        assertThat(j.path("engagement_results").toString()).contains("Ly");
        assertThat(j.path("engagement_results").toString()).contains("宝宝甜妹");
        assertThat(j.path("engagement_results").get(0).path("target").asText()).isEqualTo("active");
        assertThat(j.path("engagement_results").get(1).path("dm_draft_typed").asBoolean()).isTrue();
        verify(browser).extension_browser_click_at_linear(eq(1318.0), eq(257.0), any());
        verify(browser).extension_browser_click_at_linear(eq(1347.0), eq(373.0), any());
        verify(browser, times(2)).extension_browser_click_at_active(eq(964.0), eq(178.0), any());
        verify(browser, times(2)).extension_browser_click_at_active(eq(1062.0), eq(178.0), any());
        verify(browser, times(2)).extension_browser_type_at_active(eq("你好"), any(), any());
        verify(browser, never()).extension_browser_navigate(
                argThat(url -> url != null && url.contains("douyin.com/user/")), any(), any());
    }

    @Test
    void douyinMatchCommentFollowOpenDmTypeDraft_rechecksMainCommentsAfterActiveTabEngagement() throws Exception {
        Queue<String> mainObserves = new ArrayDeque<>();
        mainObserves.add(observe(
                "https://www.douyin.com/jingxuan/search/openclaw?modal_id=7615879690590375183&type=general",
                "发现更多精彩视频 - 抖音搜索",
                "Text[ref=ref_detail_tab]: 详情 @{1252,21 36x24}\n"
                        + "Text[ref=ref_comment_tab]: 评论 @{1480,21 36x24}\n"
                        + "Text[ref=ref_count]: 全部评论(151) @{1252,103 130x24}\n"
                        + "Link[ref=ref_author_1]: Ly @{1302,244 32x26}\n"
                        + "Text[ref=ref_comment_1]: 对于99%的人用豆包就行了。 @{1302,280 320x42}",
                1760,
                783));
        mainObserves.add(observe(
                "https://www.douyin.com/jingxuan/search/openclaw?modal_id=7615879690590375183&type=general",
                "发现更多精彩视频 - 抖音搜索",
                "Text[ref=ref_detail_tab]: 详情 @{1252,21 36x24}\n"
                        + "Text[ref=ref_comment_tab]: 评论 @{1480,21 36x24}\n"
                        + "Text[ref=ref_count]: 全部评论(151) @{1252,103 130x24}\n"
                        + "Link[ref=ref_author_2]: 老李 @{1302,360 56x26}\n"
                        + "Text[ref=ref_comment_2]: 普通人对于99%的场景用豆包就行了。 @{1302,396 420x42}\n"
                        + "Text[ref=ref_end]: 暂时没有更多 @{1302,720 120x24}",
                1760,
                783));

        Queue<String> activeObserves = new ArrayDeque<>();
        activeObserves.add(observe(
                "https://www.douyin.com/user/MS4wLjABLY",
                "Ly的主页",
                "Text[ref=ref_name]: Ly @{520,150 80x30}\n"
                        + "Button[ref=ref_follow]: 关注 @{920,160 88x36}\n"
                        + "Button[ref=ref_dm]: 私信 @{1018,160 88x36}\n"
                        + "Text[ref=ref_fans]: 粉丝 128 @{520,220 120x28}"));
        activeObserves.add(observe(
                "https://www.douyin.com/user/MS4wLjABLY",
                "Ly的主页",
                "Text[ref=ref_name]: Ly @{520,150 80x30}\n"
                        + "Button[ref=ref_followed]: 已关注 @{920,160 88x36}\n"
                        + "Button[ref=ref_dm]: 私信 @{1018,160 88x36}\n"
                        + "Text[ref=ref_fans]: 粉丝 128 @{520,220 120x28}"));
        activeObserves.add(observe(
                "https://www.douyin.com/im/conversation/ly",
                "私信",
                "Text[ref=ref_peer]: Ly @{520,80 80x30}\n"
                        + "Textbox[ref=ref_input]: 输入消息 @{520,520 420x44}\n"
                        + "Button[ref=ref_send]: 发送 @{960,520 72x44}"));
        activeObserves.add(observe(
                "https://www.douyin.com/im/conversation/ly",
                "私信",
                "Text[ref=ref_peer]: Ly @{520,80 80x30}\n"
                        + "Textbox[ref=ref_input]: 你好 @{520,520 420x44}\n"
                        + "Button[ref=ref_send]: 发送 @{960,520 72x44}"));
        activeObserves.add(observe(
                "https://www.douyin.com/user/MS4wLjABLAOLI",
                "老李的主页",
                "Text[ref=ref_name]: 老李 @{520,150 80x30}\n"
                        + "Button[ref=ref_follow]: 关注 @{920,160 88x36}\n"
                        + "Button[ref=ref_dm]: 私信 @{1018,160 88x36}\n"
                        + "Text[ref=ref_fans]: 粉丝 88 @{520,220 120x28}"));
        activeObserves.add(observe(
                "https://www.douyin.com/user/MS4wLjABLAOLI",
                "老李的主页",
                "Text[ref=ref_name]: 老李 @{520,150 80x30}\n"
                        + "Button[ref=ref_followed]: 已关注 @{920,160 88x36}\n"
                        + "Button[ref=ref_dm]: 私信 @{1018,160 88x36}\n"
                        + "Text[ref=ref_fans]: 粉丝 88 @{520,220 120x28}"));
        activeObserves.add(observe(
                "https://www.douyin.com/im/conversation/laoli",
                "私信",
                "Text[ref=ref_peer]: 老李 @{520,80 80x30}\n"
                        + "Textbox[ref=ref_input]: 输入消息 @{520,520 420x44}\n"
                        + "Button[ref=ref_send]: 发送 @{960,520 72x44}"));
        activeObserves.add(observe(
                "https://www.douyin.com/im/conversation/laoli",
                "私信",
                "Text[ref=ref_peer]: 老李 @{520,80 80x30}\n"
                        + "Textbox[ref=ref_input]: 你好 @{520,520 420x44}\n"
                        + "Button[ref=ref_send]: 发送 @{960,520 72x44}"));

        when(browser.extension_browser_observe(eq("all"), any()))
                .thenAnswer(ignored -> mainObserves.remove());
        when(browser.extension_browser_observe_active(eq("all"), any()))
                .thenAnswer(ignored -> activeObserves.remove());
        when(browser.extension_browser_click_at_linear(eq(1318.0), eq(257.0), any())).thenReturn(ok());
        when(browser.extension_browser_click_at_linear(eq(1330.0), eq(373.0), any())).thenReturn(ok());
        when(browser.extension_browser_click_at_active(eq(964.0), eq(178.0), any())).thenReturn(ok());
        when(browser.extension_browser_click_at_active(eq(1062.0), eq(178.0), any())).thenReturn(ok());
        when(browser.extension_browser_type_at_active(eq("你好"), any(), any())).thenReturn(ok());

        String out = tool.lead_browser_douyin_match_comment_follow_open_dm_type_draft(
                "",
                "对于99%的人用豆包就行了。",
                "你好",
                8,
                null);

        JsonNode j = mapper.readTree(out);
        assertThat(j.path("ok").asBoolean()).isTrue();
        assertThat(j.path("engagement_count").asInt()).isEqualTo(2);
        assertThat(j.path("engagement_results").toString()).contains("Ly");
        assertThat(j.path("engagement_results").toString()).contains("老李");
        verify(browser).extension_browser_click_at_linear(eq(1318.0), eq(257.0), any());
        verify(browser).extension_browser_click_at_linear(eq(1330.0), eq(373.0), any());
        verify(browser, times(2)).extension_browser_type_at_active(eq("你好"), any(), any());
    }

    @Test
    void douyinMatchCommentFollowOpenDmTypeDraft_scrollsPastNearMatchUntilExactEntityComment() throws Exception {
        Queue<String> mainObserves = new ArrayDeque<>();
        mainObserves.add(observe(
                "https://www.douyin.com/jingxuan/search/openclaw?modal_id=7615879690590375183&type=general",
                "发现更多精彩视频 - 抖音搜索",
                "Text[ref=ref_detail_tab]: 详情 @{1252,21 36x24}\n"
                        + "Text[ref=ref_works_tab]: TA的作品 @{1346,21 78x24}\n"
                        + "Text[ref=ref_comment_tab]: 评论 @{1480,21 36x24}\n"
                        + "Text[ref=ref_ai_tab]: 问AI @{1574,21 40x24}\n"
                        + "Text[ref=ref_count]: 全部评论(151) @{1252,103 130x24}\n"
                        + "Link[ref=ref_author_1]: 宝宝甜妹 @{1302,244 90x26}\n"
                        + "Text[ref=ref_comment_1]: 九成以上的普通人目前根本没必要用这个东西，目前应用场景也就是一些电脑端的工作可以使用 @{1302,280 520x60}",
                1760,
                783));
        mainObserves.add(observe(
                "https://www.douyin.com/jingxuan/search/openclaw?modal_id=7615879690590375183&type=general",
                "发现更多精彩视频 - 抖音搜索",
                "Text[ref=ref_detail_tab]: 详情 @{1252,21 36x24}\n"
                        + "Text[ref=ref_comment_tab]: 评论 @{1480,21 36x24}\n"
                        + "Text[ref=ref_count]: 全部评论(151) @{1252,103 130x24}\n"
                        + "Link[ref=ref_author_2]: Ly @{1302,244 32x26}\n"
                        + "Text[ref=ref_comment_2]: 对于99%的人用豆包就行了。 @{1302,280 320x42}\n"
                        + "Text[ref=ref_end]: 暂时没有更多 @{1302,720 120x24}",
                1760,
                783));

        Queue<String> activeObserves = new ArrayDeque<>();
        activeObserves.add(observe(
                "https://www.douyin.com/user/MS4wLjABLY",
                "Ly的主页",
                "Text[ref=ref_name]: Ly @{520,150 80x30}\n"
                        + "Button[ref=ref_follow]: 关注 @{920,160 88x36}\n"
                        + "Button[ref=ref_dm]: 私信 @{1018,160 88x36}\n"
                        + "Text[ref=ref_fans]: 粉丝 128 @{520,220 120x28}"));
        activeObserves.add(observe(
                "https://www.douyin.com/user/MS4wLjABLY",
                "Ly的主页",
                "Text[ref=ref_name]: Ly @{520,150 80x30}\n"
                        + "Button[ref=ref_followed]: 已关注 @{920,160 88x36}\n"
                        + "Button[ref=ref_dm]: 私信 @{1018,160 88x36}\n"
                        + "Text[ref=ref_fans]: 粉丝 128 @{520,220 120x28}"));
        activeObserves.add(observe(
                "https://www.douyin.com/im/conversation/ly",
                "私信",
                "Text[ref=ref_peer]: Ly @{520,80 80x30}\n"
                        + "Textbox[ref=ref_input]: 输入消息 @{520,520 420x44}\n"
                        + "Button[ref=ref_send]: 发送 @{960,520 72x44}"));
        activeObserves.add(observe(
                "https://www.douyin.com/im/conversation/ly",
                "私信",
                "Text[ref=ref_peer]: Ly @{520,80 80x30}\n"
                        + "Textbox[ref=ref_input]: 你好 @{520,520 420x44}\n"
                        + "Button[ref=ref_send]: 发送 @{960,520 72x44}"));

        when(browser.extension_browser_observe(eq("all"), any()))
                .thenAnswer(ignored -> mainObserves.remove());
        when(browser.extension_browser_observe_active(eq("all"), any()))
                .thenAnswer(ignored -> activeObserves.remove());
        when(browser.extension_browser_scroll_at(eq("down"), eq(650), anyDouble(), anyDouble(), any()))
                .thenReturn(ok());
        when(browser.extension_browser_click_at_linear(eq(1318.0), eq(257.0), any())).thenReturn(ok());
        when(browser.extension_browser_click_at_active(eq(964.0), eq(178.0), any())).thenReturn(ok());
        when(browser.extension_browser_click_at_active(eq(1062.0), eq(178.0), any())).thenReturn(ok());
        when(browser.extension_browser_type_at_active(eq("你好"), any(), any())).thenReturn(ok());

        String out = tool.lead_browser_douyin_match_comment_follow_open_dm_type_draft(
                "",
                "对于99%的人用豆包就行了。",
                "你好",
                8,
                null);

        JsonNode j = mapper.readTree(out);
        assertThat(j.path("ok").asBoolean()).isTrue();
        assertThat(j.path("matched_author").asText()).contains("Ly");
        assertThat(j.path("matched_comment").asText()).contains("豆包");
        assertThat(j.path("engagement_results").toString()).doesNotContain("宝宝甜妹");
        verify(browser).extension_browser_scroll_at(eq("down"), eq(650), anyDouble(), anyDouble(), any());
        verify(browser, never()).extension_browser_click_at_linear(eq(1347.0), eq(257.0), any());
        verify(browser).extension_browser_click_at_linear(eq(1318.0), eq(257.0), any());
    }

    @Test
    void douyinMatchCommentFollowOpenDmTypeDraft_readsExactCommentInLeftShiftedCommentPanel() throws Exception {
        Queue<String> mainObserves = new ArrayDeque<>();
        mainObserves.add(observe(
                "https://www.douyin.com/jingxuan/search/openclaw?modal_id=7615879690590375183&type=general",
                "发现更多精彩视频 - 抖音搜索",
                "Text[ref=ref_detail_tab]: 详情 @{98,21 36x24}\n"
                        + "Text[ref=ref_comment_tab]: 评论 @{238,21 36x24}\n"
                        + "Text[ref=ref_count]: 全部评论(151) @{98,60 130x24}\n"
                        + "Link[ref=ref_author_1]: Ly @{142,118 32x26}\n"
                        + "Text[ref=ref_comment_1]: 对于99%的人用豆包就行了。 @{142,154 320x42}\n"
                        + "Text[ref=ref_end]: 暂时没有更多 @{142,720 120x24}",
                520,
                783));
        Queue<String> activeObserves = new ArrayDeque<>();
        activeObserves.add(observe(
                "https://www.douyin.com/user/MS4wLjABLY",
                "Ly的主页",
                "Text[ref=ref_name]: Ly @{520,150 80x30}\n"
                        + "Button[ref=ref_follow]: 关注 @{920,160 88x36}\n"
                        + "Button[ref=ref_dm]: 私信 @{1018,160 88x36}\n"
                        + "Text[ref=ref_fans]: 粉丝 128 @{520,220 120x28}"));
        activeObserves.add(observe(
                "https://www.douyin.com/user/MS4wLjABLY",
                "Ly的主页",
                "Text[ref=ref_name]: Ly @{520,150 80x30}\n"
                        + "Button[ref=ref_followed]: 已关注 @{920,160 88x36}\n"
                        + "Button[ref=ref_dm]: 私信 @{1018,160 88x36}\n"
                        + "Text[ref=ref_fans]: 粉丝 128 @{520,220 120x28}"));
        activeObserves.add(observe(
                "https://www.douyin.com/im/conversation/ly",
                "私信",
                "Text[ref=ref_peer]: Ly @{520,80 80x30}\n"
                        + "Textbox[ref=ref_input]: 输入消息 @{520,520 420x44}\n"
                        + "Button[ref=ref_send]: 发送 @{960,520 72x44}"));
        activeObserves.add(observe(
                "https://www.douyin.com/im/conversation/ly",
                "私信",
                "Text[ref=ref_peer]: Ly @{520,80 80x30}\n"
                        + "Textbox[ref=ref_input]: 你好 @{520,520 420x44}\n"
                        + "Button[ref=ref_send]: 发送 @{960,520 72x44}"));

        when(browser.extension_browser_observe(eq("all"), any()))
                .thenAnswer(ignored -> mainObserves.remove());
        when(browser.extension_browser_observe_active(eq("all"), any()))
                .thenAnswer(ignored -> activeObserves.remove());
        when(browser.extension_browser_click_at_linear(eq(158.0), eq(131.0), any())).thenReturn(ok());
        when(browser.extension_browser_click_at_active(eq(964.0), eq(178.0), any())).thenReturn(ok());
        when(browser.extension_browser_click_at_active(eq(1062.0), eq(178.0), any())).thenReturn(ok());
        when(browser.extension_browser_type_at_active(eq("你好"), any(), any())).thenReturn(ok());

        String out = tool.lead_browser_douyin_match_comment_follow_open_dm_type_draft(
                "",
                "对于99%的人用豆包就行了。",
                "你好",
                8,
                null);

        JsonNode j = mapper.readTree(out);
        assertThat(j.path("ok").asBoolean()).isTrue();
        assertThat(j.path("matched_author").asText()).contains("Ly");
        assertThat(j.path("matched_comment").asText()).contains("豆包");
        verify(browser).extension_browser_click_at_linear(eq(158.0), eq(131.0), any());
    }

    @Test
    void douyinMatchCommentFollowOpenDmTypeDraft_withoutMaxScrollsFindsCommentAfterFormerDefaultBudget() throws Exception {
        Queue<String> mainObserves = new ArrayDeque<>();
        for (int i = 0; i < 25; i++) {
            mainObserves.add(observe(
                    "https://www.douyin.com/jingxuan/search/openclaw?modal_id=7615879690590375183&type=general",
                    "发现更多精彩视频 - 抖音搜索",
                    "Text[ref=ref_comment_tab]: 评论 @{1480,21 36x24}\n"
                            + "Text[ref=ref_count]: 全部评论(151) @{1252,103 130x24}\n"
                            + "Link[ref=ref_author_" + i + "]: 路人" + i + " @{1302,244 80x26}\n"
                            + "Text[ref=ref_comment_" + i + "]: 这是一条普通用户评论，用来模拟评论区继续加载到第" + i + "批。 @{1302,280 520x42}",
                    1760,
                    783));
        }
        mainObserves.add(observe(
                "https://www.douyin.com/jingxuan/search/openclaw?modal_id=7615879690590375183&type=general",
                "发现更多精彩视频 - 抖音搜索",
                "Text[ref=ref_comment_tab]: 评论 @{1480,21 36x24}\n"
                        + "Text[ref=ref_count]: 全部评论(151) @{1252,103 130x24}\n"
                        + "Link[ref=ref_author_ly]: Ly @{1302,244 32x26}\n"
                        + "Text[ref=ref_comment_ly]: 对于99%的人用豆包就行了。 @{1302,280 320x42}",
                1760,
                783));
        mainObserves.add(observe(
                "https://www.douyin.com/jingxuan/search/openclaw?modal_id=7615879690590375183&type=general",
                "发现更多精彩视频 - 抖音搜索",
                "Text[ref=ref_comment_tab]: 评论 @{1480,21 36x24}\n"
                        + "Text[ref=ref_count]: 全部评论(151) @{1252,103 130x24}\n"
                        + "Link[ref=ref_author_ly]: Ly @{1302,244 32x26}\n"
                        + "Text[ref=ref_comment_ly]: 对于99%的人用豆包就行了。 @{1302,280 320x42}\n"
                        + "Text[ref=ref_end]: 暂时没有更多 @{1302,720 120x24}",
                1760,
                783));

        Queue<String> activeObserves = new ArrayDeque<>();
        activeObserves.add(observe(
                "https://www.douyin.com/user/MS4wLjABLY",
                "Ly的主页",
                "Text[ref=ref_name]: Ly @{520,150 80x30}\n"
                        + "Button[ref=ref_follow]: 关注 @{920,160 88x36}\n"
                        + "Button[ref=ref_dm]: 私信 @{1018,160 88x36}\n"
                        + "Text[ref=ref_fans]: 粉丝 128 @{520,220 120x28}"));
        activeObserves.add(observe(
                "https://www.douyin.com/user/MS4wLjABLY",
                "Ly的主页",
                "Text[ref=ref_name]: Ly @{520,150 80x30}\n"
                        + "Button[ref=ref_followed]: 已关注 @{920,160 88x36}\n"
                        + "Button[ref=ref_dm]: 私信 @{1018,160 88x36}\n"
                        + "Text[ref=ref_fans]: 粉丝 128 @{520,220 120x28}"));
        activeObserves.add(observe(
                "https://www.douyin.com/im/conversation/ly",
                "私信",
                "Text[ref=ref_peer]: Ly @{520,80 80x30}\n"
                        + "Textbox[ref=ref_input]: 输入消息 @{520,520 420x44}\n"
                        + "Button[ref=ref_send]: 发送 @{960,520 72x44}"));
        activeObserves.add(observe(
                "https://www.douyin.com/im/conversation/ly",
                "私信",
                "Text[ref=ref_peer]: Ly @{520,80 80x30}\n"
                        + "Textbox[ref=ref_input]: 你好 @{520,520 420x44}\n"
                        + "Button[ref=ref_send]: 发送 @{960,520 72x44}"));

        when(browser.extension_browser_observe(eq("all"), any()))
                .thenAnswer(ignored -> mainObserves.remove());
        when(browser.extension_browser_observe_active(eq("all"), any()))
                .thenAnswer(ignored -> activeObserves.remove());
        when(browser.extension_browser_scroll_at(eq("down"), eq(650), anyDouble(), anyDouble(), any()))
                .thenReturn(ok());
        when(browser.extension_browser_click_at_linear(eq(1318.0), eq(257.0), any())).thenReturn(ok());
        when(browser.extension_browser_click_at_active(eq(964.0), eq(178.0), any())).thenReturn(ok());
        when(browser.extension_browser_click_at_active(eq(1062.0), eq(178.0), any())).thenReturn(ok());
        when(browser.extension_browser_type_at_active(eq("你好"), any(), any())).thenReturn(ok());

        String out = tool.lead_browser_douyin_match_comment_follow_open_dm_type_draft(
                "",
                "对于99%的人用豆包就行了。",
                "你好",
                null,
                null);

        JsonNode j = mapper.readTree(out);
        assertThat(j.path("ok").asBoolean()).isTrue();
        assertThat(j.path("status").asText()).isEqualTo("DONE_DM_DRAFT_TYPED");
        assertThat(j.path("matched_author").asText()).contains("Ly");
        assertThat(j.path("matched_comment").asText()).contains("豆包");
        assertThat(j.path("scan_stop_reason").asText()).isNotEqualTo("MAX_SCROLLS");
        verify(browser, times(25)).extension_browser_scroll_at(
                eq("down"), eq(650), anyDouble(), anyDouble(), any());
        verify(browser).extension_browser_type_at_active(eq("你好"), any(), any());
    }

    @Test
    void douyinMatchCommentFollowOpenDmTypeDraft_expandsRepliesBeforeMatching() throws Exception {
        Queue<String> mainObserves = new ArrayDeque<>();
        mainObserves.add(observe(
                "https://www.douyin.com/jingxuan/search/openclaw?modal_id=7615879690590375183&type=general",
                "发现更多精彩视频 - 抖音搜索",
                "Text[ref=ref_comment_tab]: 评论 @{1480,21 36x24}\n"
                        + "Text[ref=ref_count]: 全部评论(151) @{1252,103 130x24}\n"
                        + "Link[ref=ref_author_parent]: 老李 @{1302,244 52x26}\n"
                        + "Text[ref=ref_comment_parent]: 豆包，说它可以，要不试试 @{1302,280 320x42}\n"
                        + "Button[ref=ref_reply_expand]: 展开1条回复 @{1302,338 110x26}",
                1760,
                783));
        mainObserves.add(observe(
                "https://www.douyin.com/jingxuan/search/openclaw?modal_id=7615879690590375183&type=general",
                "发现更多精彩视频 - 抖音搜索",
                "Text[ref=ref_comment_tab]: 评论 @{1480,21 36x24}\n"
                        + "Text[ref=ref_count]: 全部评论(151) @{1252,103 130x24}\n"
                        + "Link[ref=ref_author_parent]: 老李 @{1302,244 52x26}\n"
                        + "Text[ref=ref_comment_parent]: 豆包，说它可以，要不试试 @{1302,280 320x42}\n"
                        + "Link[ref=ref_author_ly]: Ly @{1330,352 32x26}\n"
                        + "Text[ref=ref_comment_ly]: 对于99%的人用豆包就行了。 @{1330,388 320x42}\n"
                        + "Text[ref=ref_end]: 暂时没有更多 @{1302,720 120x24}",
                1760,
                783));
        mainObserves.add(observe(
                "https://www.douyin.com/jingxuan/search/openclaw?modal_id=7615879690590375183&type=general",
                "发现更多精彩视频 - 抖音搜索",
                "Text[ref=ref_comment_tab]: 评论 @{1480,21 36x24}\n"
                        + "Text[ref=ref_count]: 全部评论(151) @{1252,103 130x24}\n"
                        + "Link[ref=ref_author_parent]: 老李 @{1302,244 52x26}\n"
                        + "Text[ref=ref_comment_parent]: 豆包，说它可以，要不试试 @{1302,280 320x42}\n"
                        + "Link[ref=ref_author_ly]: Ly @{1330,352 32x26}\n"
                        + "Text[ref=ref_comment_ly]: 对于99%的人用豆包就行了。 @{1330,388 320x42}\n"
                        + "Text[ref=ref_end]: 暂时没有更多 @{1302,720 120x24}",
                1760,
                783));

        Queue<String> activeObserves = new ArrayDeque<>();
        activeObserves.add(observe(
                "https://www.douyin.com/user/MS4wLjABLY",
                "Ly的主页",
                "Text[ref=ref_name]: Ly @{520,150 80x30}\n"
                        + "Button[ref=ref_follow]: 关注 @{920,160 88x36}\n"
                        + "Button[ref=ref_dm]: 私信 @{1018,160 88x36}\n"
                        + "Text[ref=ref_fans]: 粉丝 128 @{520,220 120x28}"));
        activeObserves.add(observe(
                "https://www.douyin.com/user/MS4wLjABLY",
                "Ly的主页",
                "Text[ref=ref_name]: Ly @{520,150 80x30}\n"
                        + "Button[ref=ref_followed]: 已关注 @{920,160 88x36}\n"
                        + "Button[ref=ref_dm]: 私信 @{1018,160 88x36}\n"
                        + "Text[ref=ref_fans]: 粉丝 128 @{520,220 120x28}"));
        activeObserves.add(observe(
                "https://www.douyin.com/im/conversation/ly",
                "私信",
                "Text[ref=ref_peer]: Ly @{520,80 80x30}\n"
                        + "Textbox[ref=ref_input]: 输入消息 @{520,520 420x44}\n"
                        + "Button[ref=ref_send]: 发送 @{960,520 72x44}"));
        activeObserves.add(observe(
                "https://www.douyin.com/im/conversation/ly",
                "私信",
                "Text[ref=ref_peer]: Ly @{520,80 80x30}\n"
                        + "Textbox[ref=ref_input]: 你好 @{520,520 420x44}\n"
                        + "Button[ref=ref_send]: 发送 @{960,520 72x44}"));
        when(browser.extension_browser_observe(eq("all"), any()))
                .thenAnswer(ignored -> mainObserves.remove());
        when(browser.extension_browser_observe_active(eq("all"), any()))
                .thenAnswer(ignored -> activeObserves.remove());
        when(browser.extension_browser_click_at_linear(eq(1357.0), eq(351.0), any())).thenReturn(ok());
        when(browser.extension_browser_click_at_linear(eq(1346.0), eq(365.0), any())).thenReturn(ok());
        when(browser.extension_browser_click_at_active(eq(964.0), eq(178.0), any())).thenReturn(ok());
        when(browser.extension_browser_click_at_active(eq(1062.0), eq(178.0), any())).thenReturn(ok());
        when(browser.extension_browser_type_at_active(eq("你好"), any(), any())).thenReturn(ok());

        String out = tool.lead_browser_douyin_match_comment_follow_open_dm_type_draft(
                "",
                "对于99%的人用豆包就行了。",
                "你好",
                null,
                null);

        JsonNode j = mapper.readTree(out);
        assertThat(j.path("ok").asBoolean()).isTrue();
        assertThat(j.path("matched_author").asText()).contains("Ly");
        assertThat(j.path("matched_comment").asText()).contains("豆包");
        assertThat(j.path("scanned_comment_count").asInt()).isEqualTo(2);
        verify(browser).extension_browser_click_at_linear(eq(1357.0), eq(351.0), any());
        verify(browser).extension_browser_click_at_linear(eq(1346.0), eq(365.0), any());
    }

    @Test
    void douyinMatchCommentFollowOpenDmTypeDraft_matchesShortCommentNearPanelEdge() throws Exception {
        Queue<String> mainObserves = new ArrayDeque<>();
        mainObserves.add(observe(
                "https://www.douyin.com/jingxuan/search/openclaw?modal_id=7615879690590375183&type=general",
                "发现更多精彩视频 - 抖音搜索",
                "Text[ref=ref_like]: 55.7万 @{270,312 72x28}\n"
                        + "Text[ref=ref_comment_count]: 151 @{286,430 34x28}\n"
                        + "Text[ref=ref_detail_tab]: 详情 @{512,21 36x24}\n"
                        + "Text[ref=ref_comment_tab]: 评论 @{696,21 36x24}\n"
                        + "Text[ref=ref_count]: 全部评论(151) @{512,103 130x24}\n"
                        + "Link[ref=ref_author_prev]: 川流不息（有关注必回） @{512,180 170x26}\n"
                        + "Text[ref=ref_comment_prev]: 希望广大网民群众，一定要听国家的意见，保护好自己的利益！ @{512,216 520x42}\n"
                        + "Link[ref=ref_author_ly]: Ly @{512,466 32x26}\n"
                        + "Text[ref=ref_comment_ly]: 对于99%的人用豆包就行了。 @{512,505 330x42}\n"
                        + "Text[ref=ref_end]: 暂时没有更多 @{512,704 120x24}",
                967,
                729));
        Queue<String> activeObserves = new ArrayDeque<>();
        activeObserves.add(observe(
                "https://www.douyin.com/user/MS4wLjABLY",
                "Ly的主页",
                "Text[ref=ref_name]: Ly @{520,150 80x30}\n"
                        + "Button[ref=ref_follow]: 关注 @{920,160 88x36}\n"
                        + "Button[ref=ref_dm]: 私信 @{1018,160 88x36}\n"
                        + "Text[ref=ref_fans]: 粉丝 128 @{520,220 120x28}"));
        activeObserves.add(observe(
                "https://www.douyin.com/user/MS4wLjABLY",
                "Ly的主页",
                "Text[ref=ref_name]: Ly @{520,150 80x30}\n"
                        + "Button[ref=ref_followed]: 已关注 @{920,160 88x36}\n"
                        + "Button[ref=ref_dm]: 私信 @{1018,160 88x36}\n"
                        + "Text[ref=ref_fans]: 粉丝 128 @{520,220 120x28}"));
        activeObserves.add(observe(
                "https://www.douyin.com/im/conversation/ly",
                "私信",
                "Text[ref=ref_peer]: Ly @{520,80 80x30}\n"
                        + "Textbox[ref=ref_input]: 输入消息 @{520,520 420x44}\n"
                        + "Button[ref=ref_send]: 发送 @{960,520 72x44}"));
        activeObserves.add(observe(
                "https://www.douyin.com/im/conversation/ly",
                "私信",
                "Text[ref=ref_peer]: Ly @{520,80 80x30}\n"
                        + "Textbox[ref=ref_input]: 你好 @{520,520 420x44}\n"
                        + "Button[ref=ref_send]: 发送 @{960,520 72x44}"));
        when(browser.extension_browser_observe(eq("all"), any()))
                .thenAnswer(ignored -> mainObserves.remove());
        when(browser.extension_browser_observe_active(eq("all"), any()))
                .thenAnswer(ignored -> activeObserves.remove());
        when(browser.extension_browser_click_at_linear(eq(528.0), eq(479.0), any())).thenReturn(ok());
        when(browser.extension_browser_click_at_active(eq(964.0), eq(178.0), any())).thenReturn(ok());
        when(browser.extension_browser_click_at_active(eq(1062.0), eq(178.0), any())).thenReturn(ok());
        when(browser.extension_browser_type_at_active(eq("你好"), any(), any())).thenReturn(ok());

        String out = tool.lead_browser_douyin_match_comment_follow_open_dm_type_draft(
                "",
                "对于99%的人用豆包就行了。",
                "你好",
                null,
                null);

        JsonNode j = mapper.readTree(out);
        assertThat(j.path("ok").asBoolean()).isTrue();
        assertThat(j.path("matched_author").asText()).contains("Ly");
        assertThat(j.path("matched_comment").asText()).contains("豆包");
        assertThat(j.path("match_score").asInt()).isEqualTo(100);
        verify(browser).extension_browser_click_at_linear(eq(528.0), eq(479.0), any());
    }

    @Test
    void douyinMatchCommentFollowOpenDmTypeDraft_keepsScrollPointInRightPanelWhenCommentRowsAreLeftShifted() throws Exception {
        Queue<String> mainObserves = new ArrayDeque<>();
        mainObserves.add(observe(
                "https://www.douyin.com/jingxuan/search/openclaw?modal_id=7615879690590375183&type=general",
                "发现更多精彩视频 - 抖音搜索",
                "Text[ref=ref_detail_tab]: 详情 @{660,84 32x24}\n"
                        + "Text[ref=ref_comment_tab]: 评论 @{842,84 32x24}\n"
                        + "Text[ref=ref_ai_tab]: 问AI @{918,84 36x24}\n"
                        + "Link[ref=ref_author_1]: 宝宝甜妹 @{142,244 90x26}\n"
                        + "Text[ref=ref_comment_1]: 九成以上的普通人目前根本没必要用这个东西 @{142,280 420x42}\n"
                        + "Video[ref=ref_video]: @{0,0 1280x527}",
                1280,
                575));
        mainObserves.add(observe(
                "https://www.douyin.com/jingxuan/search/openclaw?modal_id=7615879690590375183&type=general",
                "发现更多精彩视频 - 抖音搜索",
                "Text[ref=ref_detail_tab]: 详情 @{660,84 32x24}\n"
                        + "Text[ref=ref_comment_tab]: 评论 @{842,84 32x24}\n"
                        + "Text[ref=ref_ai_tab]: 问AI @{918,84 36x24}\n"
                        + "Link[ref=ref_author_2]: Ly @{142,244 32x26}\n"
                        + "Text[ref=ref_comment_2]: 对于99%的人用豆包就行了。 @{142,280 320x42}\n"
                        + "Text[ref=ref_end]: 暂时没有更多 @{142,520 120x24}\n"
                        + "Video[ref=ref_video]: @{0,0 1280x527}",
                1280,
                575));
        Queue<String> activeObserves = new ArrayDeque<>();
        activeObserves.add(observe(
                "https://www.douyin.com/user/MS4wLjABLY",
                "Ly的主页",
                "Text[ref=ref_name]: Ly @{520,150 80x30}\n"
                        + "Button[ref=ref_follow]: 关注 @{920,160 88x36}\n"
                        + "Button[ref=ref_dm]: 私信 @{1018,160 88x36}\n"
                        + "Text[ref=ref_fans]: 粉丝 128 @{520,220 120x28}"));
        activeObserves.add(observe(
                "https://www.douyin.com/user/MS4wLjABLY",
                "Ly的主页",
                "Text[ref=ref_name]: Ly @{520,150 80x30}\n"
                        + "Button[ref=ref_followed]: 已关注 @{920,160 88x36}\n"
                        + "Button[ref=ref_dm]: 私信 @{1018,160 88x36}\n"
                        + "Text[ref=ref_fans]: 粉丝 128 @{520,220 120x28}"));
        activeObserves.add(observe(
                "https://www.douyin.com/im/conversation/ly",
                "私信",
                "Text[ref=ref_peer]: Ly @{520,80 80x30}\n"
                        + "Textbox[ref=ref_input]: 输入消息 @{520,520 420x44}\n"
                        + "Button[ref=ref_send]: 发送 @{960,520 72x44}"));
        activeObserves.add(observe(
                "https://www.douyin.com/im/conversation/ly",
                "私信",
                "Text[ref=ref_peer]: Ly @{520,80 80x30}\n"
                        + "Textbox[ref=ref_input]: 你好 @{520,520 420x44}\n"
                        + "Button[ref=ref_send]: 发送 @{960,520 72x44}"));
        when(browser.extension_browser_observe(eq("all"), any()))
                .thenAnswer(ignored -> mainObserves.remove());
        when(browser.extension_browser_observe_active(eq("all"), any()))
                .thenAnswer(ignored -> activeObserves.remove());
        when(browser.extension_browser_scroll_at(eq("down"), eq(650), anyDouble(), anyDouble(), any()))
                .thenReturn(ok());
        when(browser.extension_browser_click_at_active(eq(964.0), eq(178.0), any())).thenReturn(ok());
        when(browser.extension_browser_click_at_active(eq(1062.0), eq(178.0), any())).thenReturn(ok());
        when(browser.extension_browser_type_at_active(eq("你好"), any(), any())).thenReturn(ok());

        String out = tool.lead_browser_douyin_match_comment_follow_open_dm_type_draft(
                "",
                "对于99%的人用豆包就行了。",
                "你好",
                8,
                null);

        JsonNode j = mapper.readTree(out);
        assertThat(j.path("ok").asBoolean()).isTrue();
        assertThat(j.path("matched_author").asText()).contains("Ly");
        assertThat(j.path("matched_comment").asText()).contains("豆包");
        ArgumentCaptor<Double> hoverX = ArgumentCaptor.forClass(Double.class);
        verify(browser, atLeastOnce()).extension_browser_hover_at_linear(hoverX.capture(), anyDouble(), any());
        assertThat(hoverX.getAllValues()).allSatisfy(x -> assertThat(x).isGreaterThanOrEqualTo(760.0));
        ArgumentCaptor<Double> scrollX = ArgumentCaptor.forClass(Double.class);
        verify(browser, atLeastOnce()).extension_browser_scroll_at(
                eq("down"), eq(650), scrollX.capture(), anyDouble(), any());
        assertThat(scrollX.getAllValues()).allSatisfy(x -> assertThat(x).isGreaterThanOrEqualTo(760.0));
        verify(browser).extension_browser_click_at_linear(eq(158.0), eq(257.0), any());
        verify(browser, never()).extension_browser_scroll(eq("down"), eq(650), any());
    }

    @Test
    void douyinMatchCommentFollowOpenDmTypeDraft_reportsIncompleteScanWhenBudgetEndsBeforeEndMarker() throws Exception {
        Queue<String> mainObserves = new ArrayDeque<>();
        mainObserves.add(observe(
                "https://www.douyin.com/jingxuan/search/openclaw?modal_id=7615879690590375183&type=general",
                "发现更多精彩视频 - 抖音搜索",
                "Text[ref=ref_comment_tab]: 评论 @{1480,21 36x24}\n"
                        + "Text[ref=ref_count]: 全部评论(151) @{1252,103 130x24}\n"
                        + "Link[ref=ref_author_1]: 宝宝甜妹 @{1302,244 90x26}\n"
                        + "Text[ref=ref_comment_1]: 九成以上的普通人目前根本没必要用这个东西 @{1302,280 420x42}",
                1760,
                783));

        when(browser.extension_browser_observe(eq("all"), any()))
                .thenAnswer(ignored -> mainObserves.remove());

        String out = tool.lead_browser_douyin_match_comment_follow_open_dm_type_draft(
                "",
                "对于99%的人用豆包就行了。",
                "你好",
                0,
                null);

        JsonNode j = mapper.readTree(out);
        assertThat(j.path("ok").asBoolean()).isFalse();
        assertThat(j.path("status").asText()).isEqualTo("COMMENT_MATCH_NOT_FOUND");
        assertThat(j.path("scan_complete").asBoolean()).isFalse();
        assertThat(j.path("scan_stop_reason").asText()).isEqualTo("MAX_SCROLLS");
        assertThat(j.path("declared_comment_count").asInt()).isEqualTo(151);
        assertThat(j.path("scanned_comment_count").asInt()).isGreaterThan(0);
    }

    @Test
    void douyinMatchCommentFollowOpenDmTypeDraft_doesNotReportFollowWhenStateIsUnconfirmed() throws Exception {
        Queue<String> mainObserves = new ArrayDeque<>();
        mainObserves.add(observe(
                "https://www.douyin.com/jingxuan/search/openclaw?modal_id=7615879690590375183&type=general",
                "发现更多精彩视频 - 抖音搜索",
                "Text[ref=ref_detail_tab]: 详情 @{1252,21 36x24}\n"
                        + "Text[ref=ref_comment_tab]: 评论 @{1480,21 36x24}\n"
                        + "Text[ref=ref_count]: 全部评论(151) @{1252,103 130x24}\n"
                        + "Link[ref=ref_author_1]: Ly @{1302,244 32x26}\n"
                        + "Text[ref=ref_comment_1]: 对于99%的人用豆包就行了。 @{1302,280 320x42}\n"
                        + "Text[ref=ref_end]: 暂时没有更多 @{1302,720 120x24}",
                1760,
                783));
        Queue<String> activeObserves = new ArrayDeque<>();
        activeObserves.add(observe(
                "https://www.douyin.com/user/MS4wLjABLY",
                "Ly的主页",
                "Text[ref=ref_name]: Ly @{520,150 80x30}\n"
                        + "Button[ref=ref_follow]: 关注 @{920,160 88x36}\n"
                        + "Button[ref=ref_dm]: 私信 @{1018,160 88x36}\n"
                        + "Text[ref=ref_fans]: 粉丝 128 @{520,220 120x28}"));
        activeObserves.add(observe(
                "https://www.douyin.com/user/MS4wLjABLY",
                "Ly的主页",
                "Text[ref=ref_name]: Ly @{520,150 80x30}\n"
                        + "Button[ref=ref_follow]: 关注 @{920,160 88x36}\n"
                        + "Button[ref=ref_dm]: 私信 @{1018,160 88x36}\n"
                        + "Text[ref=ref_fans]: 粉丝 128 @{520,220 120x28}"));
        activeObserves.add(observe(
                "https://www.douyin.com/user/MS4wLjABLY",
                "Ly的主页",
                "Text[ref=ref_name]: Ly @{520,150 80x30}\n"
                        + "Button[ref=ref_follow]: 关注 @{920,160 88x36}\n"
                        + "Button[ref=ref_dm]: 私信 @{1018,160 88x36}\n"
                        + "Text[ref=ref_fans]: 粉丝 128 @{520,220 120x28}"));
        activeObserves.add(observe(
                "https://www.douyin.com/user/MS4wLjABLY",
                "Ly的主页",
                "Text[ref=ref_name]: Ly @{520,150 80x30}\n"
                        + "Button[ref=ref_follow]: 关注 @{920,160 88x36}\n"
                        + "Button[ref=ref_dm]: 私信 @{1018,160 88x36}\n"
                        + "Text[ref=ref_fans]: 粉丝 128 @{520,220 120x28}"));
        activeObserves.add(observe(
                "https://www.douyin.com/user/MS4wLjABLY",
                "Ly的主页",
                "Text[ref=ref_name]: Ly @{520,150 80x30}\n"
                        + "Button[ref=ref_follow]: 关注 @{920,160 88x36}\n"
                        + "Button[ref=ref_dm]: 私信 @{1018,160 88x36}\n"
                        + "Text[ref=ref_fans]: 粉丝 128 @{520,220 120x28}"));
        activeObserves.add(observe(
                "https://www.douyin.com/user/MS4wLjABLY",
                "Ly的主页",
                "Text[ref=ref_name]: Ly @{520,150 80x30}\n"
                        + "Button[ref=ref_follow]: 关注 @{920,160 88x36}\n"
                        + "Button[ref=ref_dm]: 私信 @{1018,160 88x36}\n"
                        + "Text[ref=ref_fans]: 粉丝 128 @{520,220 120x28}"));
        activeObserves.add(observe(
                "https://www.douyin.com/im/conversation/ly",
                "私信",
                "Text[ref=ref_peer]: Ly @{520,80 80x30}\n"
                        + "Textbox[ref=ref_input]: 输入消息 @{520,520 420x44}\n"
                        + "Button[ref=ref_send]: 发送 @{960,520 72x44}"));
        activeObserves.add(observe(
                "https://www.douyin.com/im/conversation/ly",
                "私信",
                "Text[ref=ref_peer]: Ly @{520,80 80x30}\n"
                        + "Textbox[ref=ref_input]: 你好 @{520,520 420x44}\n"
                        + "Button[ref=ref_send]: 发送 @{960,520 72x44}"));
        when(browser.extension_browser_observe(eq("all"), any()))
                .thenAnswer(ignored -> mainObserves.remove());
        when(browser.extension_browser_observe_active(eq("all"), any()))
                .thenAnswer(ignored -> activeObserves.remove());
        when(browser.extension_browser_click_at_linear(eq(1318.0), eq(257.0), any())).thenReturn(ok());
        when(browser.extension_browser_click_at_active(eq(964.0), eq(178.0), any())).thenReturn(ok());
        when(browser.extension_browser_click_at_active(eq(1062.0), eq(178.0), any())).thenReturn(ok());
        when(browser.extension_browser_type_at_active(eq("你好"), any(), any())).thenReturn(ok());

        String out = tool.lead_browser_douyin_match_comment_follow_open_dm_type_draft(
                "",
                "对于99%的人用豆包就行了。",
                "你好",
                8,
                null);

        JsonNode j = mapper.readTree(out);
        assertThat(j.path("ok").asBoolean()).isTrue();
        assertThat(j.path("follow_clicked").asBoolean()).isFalse();
        assertThat(j.path("follow_confirmed").asBoolean()).isFalse();
        assertThat(j.path("engagement_results").get(0).path("follow_confirmed").asBoolean()).isFalse();
        assertThat(j.path("dm_opened").asBoolean()).isTrue();
        assertThat(j.path("dm_draft_typed").asBoolean()).isTrue();
    }

    @Test
    void douyinMatchCommentFollowOpenDmTypeDraft_retriesProfileActionsWhenActiveTabClickTimesOut() throws Exception {
        Queue<String> mainObserves = new ArrayDeque<>();
        mainObserves.add(observe(
                "https://www.douyin.com/jingxuan/search/openclaw?modal_id=7615879690590375183&type=general",
                "发现更多精彩视频 - 抖音搜索",
                "Text[ref=ref_detail_tab]: 详情 @{1252,21 36x24}\n"
                        + "Text[ref=ref_works_tab]: TA的作品 @{1346,21 78x24}\n"
                        + "Text[ref=ref_comment_tab]: 评论 @{1480,21 36x24}\n"
                        + "Text[ref=ref_ai_tab]: 问AI @{1574,21 40x24}\n"
                        + "Text[ref=ref_count]: 全部评论(151) @{1252,103 130x24}\n"
                        + "Link[ref=ref_author_2]: Ly @{1302,244 32x26}\n"
                        + "Text[ref=ref_comment_2]: 对于99%的人用豆包就行了。 @{1302,280 320x42}\n"
                        + "Text[ref=ref_end]: 暂时没有更多 @{1302,720 120x24}",
                1760,
                783));
        Queue<String> activeObserves = new ArrayDeque<>();
        activeObserves.add(observe(
                "https://www.douyin.com/user/MS4wLjABLY",
                "Ly的主页",
                "Text[ref=ref_name]: Ly @{520,150 80x30}\n"
                        + "Button[ref=ref_follow]: 关注 @{920,160 88x36}\n"
                        + "Button[ref=ref_dm]: 私信 @{1018,160 88x36}\n"
                        + "Text[ref=ref_fans]: 粉丝 128 @{520,220 120x28}\n"
                        + "Text[ref=ref_likes]: 获赞 2048 @{660,220 110x28}"));
        activeObserves.add(observe(
                "https://www.douyin.com/user/MS4wLjABLY",
                "Ly的主页",
                "Text[ref=ref_name]: Ly @{520,150 80x30}\n"
                        + "Button[ref=ref_follow]: 关注 @{920,160 88x36}\n"
                        + "Button[ref=ref_dm]: 私信 @{1018,160 88x36}\n"
                        + "Text[ref=ref_fans]: 粉丝 128 @{520,220 120x28}\n"
                        + "Text[ref=ref_likes]: 获赞 2048 @{660,220 110x28}"));
        activeObserves.add(observe(
                "https://www.douyin.com/user/MS4wLjABLY",
                "Ly的主页",
                "Text[ref=ref_name]: Ly @{520,150 80x30}\n"
                        + "Button[ref=ref_followed]: 已关注 @{920,160 88x36}\n"
                        + "Button[ref=ref_dm]: 私信 @{1018,160 88x36}\n"
                        + "Text[ref=ref_fans]: 粉丝 128 @{520,220 120x28}\n"
                        + "Text[ref=ref_likes]: 获赞 2048 @{660,220 110x28}"));
        activeObserves.add(observe(
                "https://www.douyin.com/im/conversation/ly",
                "私信",
                "Text[ref=ref_peer]: Ly @{520,80 80x30}\n"
                        + "Textbox[ref=ref_input]: 输入消息 @{520,520 420x44}\n"
                        + "Button[ref=ref_send]: 发送 @{960,520 72x44}"));
        activeObserves.add(observe(
                "https://www.douyin.com/im/conversation/ly",
                "私信",
                "Text[ref=ref_peer]: Ly @{520,80 80x30}\n"
                        + "Textbox[ref=ref_input]: 你好 @{520,520 420x44}\n"
                        + "Button[ref=ref_send]: 发送 @{960,520 72x44}"));
        when(browser.extension_browser_observe(eq("all"), any()))
                .thenAnswer(ignored -> mainObserves.remove());
        when(browser.extension_browser_observe_active(eq("all"), any()))
                .thenAnswer(ignored -> activeObserves.remove());
        when(browser.extension_browser_click_at_linear(eq(1318.0), eq(257.0), any())).thenReturn(ok());
        when(browser.extension_browser_click_at_active(eq(964.0), eq(178.0), any()))
                .thenReturn("{\"ok\":false,\"code\":\"TIMEOUT\",\"message\":\"click timed out\"}")
                .thenReturn(ok());
        when(browser.extension_browser_click_at_active(eq(1062.0), eq(178.0), any())).thenReturn(ok());
        when(browser.extension_browser_type_at_active(eq("你好"), any(), any())).thenReturn(ok());

        String out = tool.lead_browser_douyin_match_comment_follow_open_dm_type_draft(
                "",
                "对于99%的人用豆包就行了。",
                "你好",
                8,
                null);

        JsonNode j = mapper.readTree(out);
        assertThat(j.path("ok").asBoolean()).isTrue();
        assertThat(j.path("status").asText()).isEqualTo("DONE_DM_DRAFT_TYPED");
        assertThat(j.path("follow_clicked").asBoolean()).isTrue();
        verify(browser, times(2)).extension_browser_click_at_active(eq(964.0), eq(178.0), any());
        verify(browser).extension_browser_type_at_active(eq("你好"), any(), any());
    }

    @Test
    void douyinFullSemanticCommentFollowOpenDm_runsFromSearchThroughMatchedPrivateMessage() throws Exception {
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
                "Textbox[ref=ref_1]: openclaw @{700,5 730x72}\n"
                        + "Button[ref=ref_filter]: 筛选 @{930,96 64x32}"));
        observes.add(observe(
                "https://www.douyin.com/search/openclaw?type=general",
                "openclaw - 抖音搜索",
                "Button[ref=ref_filter]: 筛选 @{930,96 64x32}\n"
                        + "Text[ref=ref_sort]: 排序依据 @{926,136 68x24}\n"
                        + "Button[ref=ref_like]: 最多点赞 @{926,204 80x28}"));
        observes.add(observe(
                "https://www.douyin.com/search/openclaw?type=general",
                "openclaw - 抖音搜索",
                "Button[ref=ref_filter]: 筛选 @{930,96 64x32}\n"
                        + "Button[ref=ref_like]: 最多点赞 已选 @{926,204 102x28}\n"
                        + "Text[ref=ref_title_low]: OpenClaw 3.0部署教程 @{220,135 260x28}\n"
                        + "Image[ref=ref_low]: OpenClaw 3.0部署教程 @{220,168 260x190}\n"
                        + "Text[ref=ref_like_low]: 点赞7.4万 @{220,368 100x28}\n"
                        + "Text[ref=ref_title_high]: 央视新闻 OpenClaw @{560,135 260x28}\n"
                        + "Image[ref=ref_high]: 全网都在养的龙虾 @{560,168 260x190}\n"
                        + "Text[ref=ref_like_high]: 55.9w点赞 @{560,368 120x28}"));
        observes.add(observe(
                "https://www.douyin.com/jingxuan/search/openclaw?modal_id=7615879690590375183&type=general",
                "发现更多精彩视频 - 抖音搜索",
                "Text[ref=ref_comment]: 151 @{1229,335 20x18}\n"
                        + "Text[ref=ref_like]: 55.7万 @{1221,276 35x18}\n"
                        + "Text[ref=ref_collect]: 11.3万 @{1221,394 35x18}\n"
                        + "Video[ref=ref_video]: @{0,0 1280x527}",
                1280,
                575));
        observes.add(observe(
                "https://www.douyin.com/jingxuan/search/openclaw?modal_id=7615879690590375183&type=general",
                "发现更多精彩视频 - 抖音搜索",
                "Text[ref=ref_detail_tab]: 详情 @{660,84 32x24}\n"
                        + "Text[ref=ref_works_tab]: TA的作品 @{735,84 72x24}\n"
                        + "Text[ref=ref_comment_tab]: 评论 @{842,84 32x24}\n"
                        + "Text[ref=ref_ai_tab]: 问AI @{918,84 36x24}\n"
                        + "Link[ref=ref_author_1]: 川流不息（有关注必回） @{690,150 170x26}\n"
                        + "Text[ref=ref_comment_1]: 这个工具对新手来说真的有点复杂，像不会用智能手机 @{690,186 380x42}\n"
                        + "Link[ref=ref_author_2]: Ly @{690,276 32x26}\n"
                        + "Text[ref=ref_comment_2]: 对于99%的人用豆包就行了。 @{690,312 320x42}\n"
                        + "Text[ref=ref_end]: 暂时没有更多 @{690,530 120x24}",
                1280,
                575));
        Queue<String> activeObserves = new ArrayDeque<>();
        activeObserves.add(observe(
                "https://www.douyin.com/user/MS4wLjABLY",
                "Ly的主页",
                "Text[ref=ref_name]: Ly @{520,150 80x30}\n"
                        + "Button[ref=ref_follow]: 关注 @{920,160 88x36}\n"
                        + "Button[ref=ref_dm]: 私信 @{1018,160 88x36}\n"
                        + "Text[ref=ref_fans]: 粉丝 128 @{520,220 120x28}\n"
                        + "Text[ref=ref_works]: 作品 12 @{660,220 110x28}"));
        activeObserves.add(observe(
                "https://www.douyin.com/user/MS4wLjABLY",
                "Ly的主页",
                "Text[ref=ref_name]: Ly @{520,150 80x30}\n"
                        + "Button[ref=ref_followed]: 已关注 @{920,160 88x36}\n"
                        + "Button[ref=ref_dm]: 私信 @{1018,160 88x36}\n"
                        + "Text[ref=ref_fans]: 粉丝 128 @{520,220 120x28}\n"
                        + "Text[ref=ref_works]: 作品 12 @{660,220 110x28}"));
        activeObserves.add(observe(
                "https://www.douyin.com/im/conversation/ly",
                "私信",
                "Text[ref=ref_peer]: Ly @{520,80 80x30}\n"
                        + "Textbox[ref=ref_input]: 输入消息 @{520,520 420x44}\n"
                        + "Button[ref=ref_send]: 发送 @{960,520 72x44}"));
        activeObserves.add(observe(
                "https://www.douyin.com/im/conversation/ly",
                "私信",
                "Text[ref=ref_peer]: Ly @{520,80 80x30}\n"
                        + "Textbox[ref=ref_input]: 你好 @{520,520 420x44}\n"
                        + "Button[ref=ref_send]: 发送 @{960,520 72x44}"));
        when(browser.extension_browser_observe(eq("all"), any()))
                .thenAnswer(ignored -> observes.remove());
        when(browser.extension_browser_observe_active(eq("all"), any()))
                .thenAnswer(ignored -> activeObserves.remove());
        when(browser.extension_browser_click_at(eq(1065.0), eq(41.0), any())).thenReturn(ok());
        when(browser.extension_browser_type_at(eq("openclaw\n"), any(), any())).thenReturn(ok());
        when(browser.extension_browser_hover_at(eq(962.0), eq(112.0), any())).thenReturn(ok());
        when(browser.extension_browser_click_at(eq(966.0), eq(218.0), any())).thenReturn(ok());
        when(browser.extension_browser_hover_at(eq(690.0), eq(263.0), any())).thenReturn(ok());
        when(browser.extension_browser_click_at(eq(690.0), eq(263.0), any())).thenReturn(ok());
        when(browser.extension_browser_click_at(eq(420.0), eq(420.0), any())).thenReturn(ok());
        when(browser.extension_browser_press_key(eq("x"), any())).thenReturn(ok());
        when(browser.extension_browser_click_at_linear(eq(706.0), eq(289.0), any())).thenReturn(ok());
        when(browser.extension_browser_click_at_active(eq(964.0), eq(178.0), any())).thenReturn(ok());
        when(browser.extension_browser_click_at_active(eq(1062.0), eq(178.0), any())).thenReturn(ok());
        when(browser.extension_browser_type_at_active(eq("你好"), any(), any())).thenReturn(ok());

        String out = tool.lead_browser_douyin_search_sort_first_video_match_comment_follow_open_dm_type_draft(
                "openclaw",
                "对于99%的人用豆包就行了。",
                "",
                "你好",
                12,
                null);

        JsonNode j = mapper.readTree(out);
        assertThat(j.path("ok").asBoolean()).isTrue();
        assertThat(j.path("status").asText()).isEqualTo("DONE_DM_DRAFT_TYPED");
        assertThat(j.path("query").asText()).isEqualTo("openclaw");
        assertThat(j.path("matched_author").asText()).contains("Ly");
        assertThat(j.path("matched_comment").asText()).contains("豆包");
        assertThat(j.path("candidate_comments").toString()).contains("author=Ly");
        assertThat(j.path("candidate_comments").toString()).contains("豆包");
        assertThat(j.path("follow_clicked").asBoolean()).isTrue();
        assertThat(j.path("dm_opened").asBoolean()).isTrue();
        assertThat(j.path("dm_draft_typed").asBoolean()).isTrue();
        assertThat(j.path("candidate_comments")).isNotEmpty();
        verify(browser).extension_browser_press_key(eq("x"), any());
        ArgumentCaptor<Double> parkX = ArgumentCaptor.forClass(Double.class);
        verify(browser, atLeastOnce()).extension_browser_hover_at_linear(parkX.capture(), anyDouble(), any());
        assertThat(parkX.getAllValues()).allSatisfy(x -> assertThat(x).isGreaterThanOrEqualTo(760.0));
        verify(browser).extension_browser_click_at_linear(eq(706.0), eq(289.0), any());
        verify(browser, never()).extension_browser_click_at(eq(706.0), eq(289.0), any());
        verify(browser).extension_browser_click_at_active(eq(964.0), eq(178.0), any());
        verify(browser).extension_browser_click_at_active(eq(1062.0), eq(178.0), any());
        verify(browser, never()).extension_browser_type(any(), any());
        verify(browser).extension_browser_type_at_active(eq("你好"), any(), any());
    }

    @Test
    void douyinCollectCommentsAcrossVideos_reportsPartialWhenCommentScrollFails() throws Exception {
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
                "Textbox[ref=ref_1]: openclaw @{700,5 730x72}\n"
                        + "Button[ref=ref_filter]: 筛选 @{930,96 64x32}"));
        observes.add(observe(
                "https://www.douyin.com/search/openclaw?type=general",
                "openclaw - 抖音搜索",
                "Button[ref=ref_filter]: 筛选 @{930,96 64x32}\n"
                        + "Text[ref=ref_sort]: 排序依据 @{926,136 68x24}\n"
                        + "Button[ref=ref_like]: 最多点赞 @{926,204 80x28}"));
        observes.add(observe(
                "https://www.douyin.com/search/openclaw?type=general",
                "openclaw - 抖音搜索",
                "Button[ref=ref_filter]: 筛选 @{930,96 64x32}\n"
                        + "Button[ref=ref_like]: 最多点赞 已选 @{926,204 102x28}\n"
                        + "Text[ref=ref_title_high]: 央视新闻 OpenClaw @{560,135 260x28}\n"
                        + "Image[ref=ref_high]: 全网都在养的龙虾 @{560,168 260x190}\n"
                        + "Text[ref=ref_like_high]: 55.9w点赞 @{560,368 120x28}"));
        observes.add(observe(
                "https://www.douyin.com/jingxuan/search/openclaw?modal_id=7615879690590375183&type=general",
                "发现更多精彩视频 - 抖音搜索",
                "Text[ref=ref_comment]: 151 @{1229,335 20x18}\n"
                        + "Text[ref=ref_like]: 55.7万 @{1221,276 35x18}\n"
                        + "Text[ref=ref_collect]: 11.3万 @{1221,394 35x18}\n"
                        + "Video[ref=ref_video]: @{0,0 1280x527}",
                1280,
                575));
        observes.add(observe(
                "https://www.douyin.com/jingxuan/search/openclaw?modal_id=7615879690590375183&type=general",
                "发现更多精彩视频 - 抖音搜索",
                "Text[ref=ref_detail_tab]: 详情 @{660,84 32x24}\n"
                        + "Text[ref=ref_works_tab]: TA的作品 @{735,84 72x24}\n"
                        + "Text[ref=ref_comment_tab]: 评论 @{842,84 32x24}\n"
                        + "Text[ref=ref_ai_tab]: 问AI @{918,84 36x24}\n"
                        + "Text[ref=ref_count]: 全部评论 151 @{690,118 120x24}\n"
                        + "Link[ref=ref_author_1]: 川流不息（有关注必回） @{690,150 170x26}\n"
                        + "Text[ref=ref_comment_1]: 这个工具对新手来说真的有点复杂，像不会用智能手机 @{690,186 380x42}",
                1280,
                575));
        when(browser.extension_browser_observe(eq("all"), any()))
                .thenAnswer(ignored -> observes.remove());
        when(browser.extension_browser_click_at(eq(1065.0), eq(41.0), any())).thenReturn(ok());
        when(browser.extension_browser_type_at(eq("openclaw\n"), any(), any())).thenReturn(ok());
        when(browser.extension_browser_hover_at(eq(962.0), eq(112.0), any())).thenReturn(ok());
        when(browser.extension_browser_click_at(eq(966.0), eq(218.0), any())).thenReturn(ok());
        when(browser.extension_browser_hover_at(eq(690.0), eq(263.0), any())).thenReturn(ok());
        when(browser.extension_browser_click_at(eq(690.0), eq(263.0), any())).thenReturn(ok());
        when(browser.extension_browser_click_at(eq(420.0), eq(420.0), any())).thenReturn(ok());
        when(browser.extension_browser_press_key(eq("x"), any())).thenReturn(ok());
        when(browser.extension_browser_scroll_at(eq("down"), eq(650), anyDouble(), anyDouble(), any()))
                .thenReturn("{\"ok\":false,\"code\":\"SESSION_DETACHED\",\"message\":\"debugger session detached\"}");

        String out = tool.lead_browser_douyin_collect_comments_across_videos("openclaw", 1, null);

        JsonNode j = mapper.readTree(out);
        assertThat(j.path("ok").asBoolean()).isFalse();
        assertThat(j.path("status").asText()).isEqualTo("PARTIAL_COLLECTED");
        assertThat(j.path("collection_complete").asBoolean()).isFalse();
        assertThat(j.path("videos").get(0).path("stop_reason").asText()).isEqualTo("SCROLL_FAILED");
        assertThat(j.path("videos").get(0).path("complete").asBoolean()).isFalse();
        assertThat(j.path("videos").get(0).path("declared_comment_count").asInt()).isEqualTo(151);
        assertThat(j.path("videos").get(0).path("comments").get(0).path("candidate_id").asText()).isEqualTo("v1-c1");
        assertThat(j.path("videos").get(0).path("comments").get(0).path("video_index").asInt()).isEqualTo(1);
        assertThat(j.path("videos").get(0).path("comments").get(0).path("comment_index").asInt()).isEqualTo(1);
        assertThat(j.path("lead_candidates").get(0).path("candidate_id").asText()).isEqualTo("v1-c1");
        assertThat(j.path("lead_candidates").get(0).path("platform").asText()).isEqualTo("douyin");
        assertThat(j.path("lead_candidates").get(0).path("author").asText()).contains("川流不息");
        assertThat(j.path("lead_candidates").get(0).path("comment").asText()).contains("新手");
        assertThat(j.path("lead_candidates").get(0).path("profile_pending").asBoolean()).isTrue();
        assertThat(j.path("attempts").toString()).doesNotContain("focus_panel");
        ArgumentCaptor<Double> scrollX = ArgumentCaptor.forClass(Double.class);
        verify(browser, atLeastOnce()).extension_browser_scroll_at(
                eq("down"), eq(650), scrollX.capture(), anyDouble(), any());
        assertThat(scrollX.getAllValues()).allSatisfy(x -> assertThat(x).isLessThan(1060.0));
    }

    @Test
    void douyinCollectFirstVideoComments_doesNotTreatEarlyEndAsDoneWhenActionBarShowsMoreComments() throws Exception {
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
                "Textbox[ref=ref_1]: openclaw @{700,5 730x72}\n"
                        + "Button[ref=ref_filter]: 筛选 @{930,96 64x32}"));
        observes.add(observe(
                "https://www.douyin.com/search/openclaw?type=general",
                "openclaw - 抖音搜索",
                "Button[ref=ref_filter]: 筛选 @{930,96 64x32}\n"
                        + "Text[ref=ref_sort]: 排序依据 @{926,136 68x24}\n"
                        + "Button[ref=ref_like]: 最多点赞 @{926,204 80x28}"));
        observes.add(observe(
                "https://www.douyin.com/search/openclaw?type=general",
                "openclaw - 抖音搜索",
                "Button[ref=ref_filter]: 筛选 @{930,96 64x32}\n"
                        + "Button[ref=ref_like]: 最多点赞 已选 @{926,204 102x28}\n"
                        + "Text[ref=ref_title_high]: 央视新闻 OpenClaw @{560,135 260x28}\n"
                        + "Image[ref=ref_high]: 全网都在养的龙虾 @{560,168 260x190}\n"
                        + "Text[ref=ref_like_high]: 55.9w点赞 @{560,368 120x28}"));
        observes.add(observe(
                "https://www.douyin.com/jingxuan/search/openclaw?modal_id=7615879690590375183&type=general",
                "发现更多精彩视频 - 抖音搜索",
                "Text[ref=ref_like]: 55.7万 @{1221,276 35x18}\n"
                        + "Text[ref=ref_comment]: 151 @{1229,335 20x18}\n"
                        + "Text[ref=ref_collect]: 11.3万 @{1221,394 35x18}\n"
                        + "Video[ref=ref_video]: @{0,0 1280x527}",
                1280,
                575));
        observes.add(observe(
                "https://www.douyin.com/jingxuan/search/openclaw?modal_id=7615879690590375183&type=general",
                "发现更多精彩视频 - 抖音搜索",
                "Text[ref=ref_detail_tab]: 详情 @{660,84 32x24}\n"
                        + "Text[ref=ref_works_tab]: TA的作品 @{735,84 72x24}\n"
                        + "Text[ref=ref_comment_tab]: 评论 @{842,84 32x24}\n"
                        + "Text[ref=ref_ai_tab]: 问AI @{918,84 36x24}\n"
                        + "Link[ref=ref_author_1]: 川流不息（有关注必回） @{690,150 170x26}\n"
                        + "Text[ref=ref_comment_1]: 不懂就问，龙虾是什么意思？ @{690,186 280x42}\n"
                        + "Text[ref=ref_end]: 暂时没有更多 @{690,510 120x24}",
                1280,
                575));
        when(browser.extension_browser_observe(eq("all"), any()))
                .thenAnswer(ignored -> observes.remove());
        when(browser.extension_browser_click_at(eq(1065.0), eq(41.0), any())).thenReturn(ok());
        when(browser.extension_browser_type_at(eq("openclaw\n"), any(), any())).thenReturn(ok());
        when(browser.extension_browser_hover_at(eq(962.0), eq(112.0), any())).thenReturn(ok());
        when(browser.extension_browser_click_at(eq(966.0), eq(218.0), any())).thenReturn(ok());
        when(browser.extension_browser_hover_at(eq(690.0), eq(263.0), any())).thenReturn(ok());
        when(browser.extension_browser_click_at(eq(690.0), eq(263.0), any())).thenReturn(ok());
        when(browser.extension_browser_click_at(eq(420.0), eq(420.0), any())).thenReturn(ok());
        when(browser.extension_browser_press_key(eq("x"), any())).thenReturn(ok());

        String out = tool.lead_browser_douyin_collect_first_video_comments("openclaw", null);

        JsonNode j = mapper.readTree(out);
        assertThat(j.path("ok").asBoolean()).isFalse();
        assertThat(j.path("status").asText()).isEqualTo("PARTIAL_COLLECTED");
        assertThat(j.path("collection_complete").asBoolean()).isFalse();
        assertThat(j.path("declared_comment_count").asInt()).isEqualTo(151);
        assertThat(j.path("comment_count").asInt()).isEqualTo(1);
        assertThat(j.path("stop_reason").asText()).isEqualTo("END_BEFORE_DECLARED_COUNT");
    }

    @Test
    void douyinCollectFirstVideoComments_keepsDeclaredCountScopedAndExpandsReplies() throws Exception {
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
                "Textbox[ref=ref_1]: openclaw @{700,5 730x72}\n"
                        + "Button[ref=ref_filter]: 筛选 @{930,96 64x32}"));
        observes.add(observe(
                "https://www.douyin.com/search/openclaw?type=general",
                "openclaw - 抖音搜索",
                "Button[ref=ref_filter]: 筛选 @{930,96 64x32}\n"
                        + "Text[ref=ref_sort]: 排序依据 @{926,136 68x24}\n"
                        + "Button[ref=ref_like]: 最多点赞 @{926,204 80x28}"));
        observes.add(observe(
                "https://www.douyin.com/search/openclaw?type=general",
                "openclaw - 抖音搜索",
                "Button[ref=ref_filter]: 筛选 @{930,96 64x32}\n"
                        + "Button[ref=ref_like]: 最多点赞 已选 @{926,204 102x28}\n"
                        + "Text[ref=ref_title_high]: 央视新闻 OpenClaw @{560,135 260x28}\n"
                        + "Image[ref=ref_high]: 全网都在养的龙虾 @{560,168 260x190}\n"
                        + "Text[ref=ref_like_high]: 55.9w点赞 @{560,368 120x28}"));
        observes.add(observe(
                "https://www.douyin.com/jingxuan/search/openclaw?modal_id=7615879690590375183&type=general",
                "发现更多精彩视频 - 抖音搜索",
                "Text[ref=ref_like]: 55.7万 @{1221,276 35x18}\n"
                        + "Text[ref=ref_comment]: 151 @{1229,335 20x18}\n"
                        + "Text[ref=ref_collect]: 11.3万 @{1221,394 35x18}\n"
                        + "Text[ref=ref_share]: 21.7万 @{1221,453 35x18}\n"
                        + "Video[ref=ref_video]: @{0,0 1280x527}",
                1280,
                575));
        observes.add(observe(
                "https://www.douyin.com/jingxuan/search/openclaw?modal_id=7615879690590375183&type=general",
                "发现更多精彩视频 - 抖音搜索",
                "Text[ref=ref_detail_tab]: 详情 @{660,84 32x24}\n"
                        + "Text[ref=ref_works_tab]: TA的作品 @{735,84 72x24}\n"
                        + "Text[ref=ref_comment_tab]: 评论 @{842,84 32x24}\n"
                        + "Text[ref=ref_ai_tab]: 问AI @{918,84 36x24}\n"
                        + "Text[ref=ref_count]: 全部评论 151 @{690,118 120x24}\n"
                        + "Text[ref=ref_polluted]: 评论 266 @{260,160 80x24}\n"
                        + "Link[ref=ref_author_1]: 川流不息（有关注必回） @{690,150 170x26}\n"
                        + "Text[ref=ref_comment_1]: 不懂就问，龙虾是什么意思？ @{690,186 280x42}\n"
                        + "Button[ref=ref_reply_expand]: 展开1条回复 @{720,245 110x26}",
                1280,
                575));
        observes.add(observe(
                "https://www.douyin.com/jingxuan/search/openclaw?modal_id=7615879690590375183&type=general",
                "发现更多精彩视频 - 抖音搜索",
                "Text[ref=ref_detail_tab]: 详情 @{660,84 32x24}\n"
                        + "Text[ref=ref_comment_tab]: 评论 @{842,84 32x24}\n"
                        + "Text[ref=ref_count]: 全部评论 151 @{690,118 120x24}\n"
                        + "Text[ref=ref_polluted]: 评论 266 @{260,160 80x24}\n"
                        + "Link[ref=ref_author_1]: 川流不息（有关注必回） @{690,150 170x26}\n"
                        + "Text[ref=ref_comment_1]: 不懂就问，龙虾是什么意思？ @{690,186 280x42}\n"
                        + "Link[ref=ref_author_child]: 回复用户 @{730,244 70x24}\n"
                        + "Text[ref=ref_comment_child]: 我也想知道这个龙虾到底能不能帮普通人做事 @{730,276 360x42}",
                1280,
                575));
        observes.add(observe(
                "https://www.douyin.com/jingxuan/search/openclaw?modal_id=7615879690590375183&type=general",
                "发现更多精彩视频 - 抖音搜索",
                "Text[ref=ref_detail_tab]: 详情 @{660,84 32x24}\n"
                        + "Text[ref=ref_comment_tab]: 评论 @{842,84 32x24}\n"
                        + "Text[ref=ref_count]: 全部评论 151 @{690,118 120x24}\n"
                        + "Link[ref=ref_author_1]: 川流不息（有关注必回） @{690,150 170x26}\n"
                        + "Text[ref=ref_comment_1]: 不懂就问，龙虾是什么意思？ @{690,186 280x42}\n"
                        + "Link[ref=ref_author_child]: 回复用户 @{730,244 70x24}\n"
                        + "Text[ref=ref_comment_child]: 我也想知道这个龙虾到底能不能帮普通人做事 @{730,276 360x42}\n"
                        + "Text[ref=ref_end]: 暂时没有更多 @{690,510 120x24}",
                1280,
                575));
        when(browser.extension_browser_observe(eq("all"), any()))
                .thenAnswer(ignored -> observes.remove());
        when(browser.extension_browser_click_at(eq(1065.0), eq(41.0), any())).thenReturn(ok());
        when(browser.extension_browser_type_at(eq("openclaw\n"), any(), any())).thenReturn(ok());
        when(browser.extension_browser_hover_at(eq(962.0), eq(112.0), any())).thenReturn(ok());
        when(browser.extension_browser_click_at(eq(966.0), eq(218.0), any())).thenReturn(ok());
        when(browser.extension_browser_hover_at(eq(690.0), eq(263.0), any())).thenReturn(ok());
        when(browser.extension_browser_click_at(eq(690.0), eq(263.0), any())).thenReturn(ok());
        when(browser.extension_browser_click_at(eq(420.0), eq(420.0), any())).thenReturn(ok());
        when(browser.extension_browser_press_key(eq("x"), any())).thenReturn(ok());
        when(browser.extension_browser_click_at(eq(775.0), eq(258.0), any())).thenReturn(ok());
        when(browser.extension_browser_hover_at(anyDouble(), anyDouble(), any())).thenReturn(ok());
        when(browser.extension_browser_scroll_at(eq("down"), eq(650), anyDouble(), anyDouble(), any()))
                .thenReturn(ok());

        String out = tool.lead_browser_douyin_collect_first_video_comments("openclaw", null);

        JsonNode j = mapper.readTree(out);
        assertThat(j.path("ok").asBoolean()).isFalse();
        assertThat(j.path("status").asText()).isEqualTo("PARTIAL_COLLECTED");
        assertThat(j.path("declared_comment_count").asInt()).isEqualTo(151);
        assertThat(j.path("comment_count").asInt()).isEqualTo(2);
        assertThat(j.path("comments").toString()).contains("我也想知道这个龙虾");
        assertThat(j.path("comments").toString()).doesNotContain("展开1条回复");
        assertThat(j.path("stop_reason").asText()).isEqualTo("END_BEFORE_DECLARED_COUNT");
        verify(browser).extension_browser_click_at_linear(eq(775.0), eq(258.0), any());
        verify(browser, never()).extension_browser_click_at(eq(775.0), eq(258.0), any());
    }

    @Test
    void douyinCollectFirstVideoComments_locksMouseToWideRightCommentPanel() throws Exception {
        when(browser.extension_browser_navigate(eq("https://www.douyin.com/"), eq("load"), any()))
                .thenReturn(ok());
        Queue<String> observes = new ArrayDeque<>();
        observes.add(observe(
                "https://www.douyin.com/",
                "抖音",
                "Textbox[ref=ref_1]: 搜索你感兴趣的内容 @{0,48 280x40}",
                1760,
                783));
        observes.add(observe(
                "https://www.douyin.com/",
                "抖音",
                "Textbox[ref=ref_2]: 搜索你感兴趣的内容 @{0,48 280x40}",
                1760,
                783));
        observes.add(observe(
                "https://www.douyin.com/search/openclaw?type=general",
                "openclaw - 抖音搜索",
                "Textbox[ref=ref_1]: openclaw @{0,48 280x40}\n"
                        + "Button[ref=ref_filter]: 筛选 @{930,96 64x32}",
                1760,
                783));
        observes.add(observe(
                "https://www.douyin.com/search/openclaw?type=general",
                "openclaw - 抖音搜索",
                "Button[ref=ref_filter]: 筛选 @{930,96 64x32}\n"
                        + "Text[ref=ref_sort]: 排序依据 @{926,136 68x24}\n"
                        + "Button[ref=ref_like]: 最多点赞 @{926,204 80x28}",
                1760,
                783));
        observes.add(observe(
                "https://www.douyin.com/search/openclaw?type=general",
                "openclaw - 抖音搜索",
                "Button[ref=ref_filter]: 筛选 @{930,96 64x32}\n"
                        + "Button[ref=ref_like]: 最多点赞 已选 @{926,204 102x28}\n"
                        + "Text[ref=ref_title_high]: 央视新闻 OpenClaw @{560,135 260x28}\n"
                        + "Image[ref=ref_high]: 全网都在养的龙虾 @{560,168 260x190}\n"
                        + "Text[ref=ref_like_high]: 55.9w点赞 @{560,368 120x28}",
                1760,
                783));
        observes.add(observe(
                "https://www.douyin.com/jingxuan/search/openclaw?modal_id=7615879690590375183&type=general",
                "发现更多精彩视频 - 抖音搜索",
                "Text[ref=ref_comment]: 151 @{1190,555 36x24}\n"
                        + "Text[ref=ref_like]: 55.7万 @{1190,482 58x24}\n"
                        + "Text[ref=ref_collect]: 11.3万 @{1190,629 58x24}\n"
                        + "Video[ref=ref_video]: @{294,0 942x783}",
                1760,
                783));
        observes.add(observe(
                "https://www.douyin.com/jingxuan/search/openclaw?modal_id=7615879690590375183&type=general",
                "发现更多精彩视频 - 抖音搜索",
                "Text[ref=ref_detail_tab]: 详情 @{1252,21 36x24}\n"
                        + "Text[ref=ref_works_tab]: TA的作品 @{1346,21 78x24}\n"
                        + "Text[ref=ref_comment_tab]: 评论 @{1480,21 36x24}\n"
                        + "Text[ref=ref_ai_tab]: 问AI @{1574,21 40x24}\n"
                        + "Text[ref=ref_count]: 全部评论(151) @{1252,103 130x24}\n"
                        + "Link[ref=ref_author_1]: 川流不息（有关注必回） @{1302,133 170x26}\n"
                        + "Text[ref=ref_comment_1]: 不懂就问，龙虾是什么意思？ @{1302,169 280x42}\n"
                        + "Button[ref=ref_reply_expand]: 展开1条回复 @{1302,245 110x26}",
                1760,
                783));
        observes.add(observe(
                "https://www.douyin.com/jingxuan/search/openclaw?modal_id=7615879690590375183&type=general",
                "发现更多精彩视频 - 抖音搜索",
                "Text[ref=ref_detail_tab]: 详情 @{1252,21 36x24}\n"
                        + "Text[ref=ref_comment_tab]: 评论 @{1480,21 36x24}\n"
                        + "Text[ref=ref_count]: 全部评论(151) @{1252,103 130x24}\n"
                        + "Link[ref=ref_author_1]: 川流不息（有关注必回） @{1302,133 170x26}\n"
                        + "Text[ref=ref_comment_1]: 不懂就问，龙虾是什么意思？ @{1302,169 280x42}\n"
                        + "Link[ref=ref_author_child]: 回复用户 @{1330,244 70x24}\n"
                        + "Text[ref=ref_comment_child]: 我也想知道这个龙虾到底能不能帮普通人做事 @{1330,276 360x42}",
                1760,
                783));
        observes.add(observe(
                "https://www.douyin.com/jingxuan/search/openclaw?modal_id=7615879690590375183&type=general",
                "发现更多精彩视频 - 抖音搜索",
                "Text[ref=ref_detail_tab]: 详情 @{1252,21 36x24}\n"
                        + "Text[ref=ref_comment_tab]: 评论 @{1480,21 36x24}\n"
                        + "Text[ref=ref_count]: 全部评论(151) @{1252,103 130x24}\n"
                        + "Link[ref=ref_author_1]: 川流不息（有关注必回） @{1302,133 170x26}\n"
                        + "Text[ref=ref_comment_1]: 不懂就问，龙虾是什么意思？ @{1302,169 280x42}\n"
                        + "Text[ref=ref_end]: 暂时没有更多 @{1302,720 120x24}",
                1760,
                783));
        when(browser.extension_browser_observe(eq("all"), any()))
                .thenAnswer(ignored -> observes.remove());
        when(browser.extension_browser_click_at(eq(140.0), eq(68.0), any())).thenReturn(ok());
        when(browser.extension_browser_type_at(eq("openclaw\n"), any(), any())).thenReturn(ok());
        when(browser.extension_browser_hover_at(eq(962.0), eq(112.0), any())).thenReturn(ok());
        when(browser.extension_browser_click_at(eq(966.0), eq(218.0), any())).thenReturn(ok());
        when(browser.extension_browser_hover_at(eq(690.0), eq(263.0), any())).thenReturn(ok());
        when(browser.extension_browser_click_at(eq(690.0), eq(263.0), any())).thenReturn(ok());
        when(browser.extension_browser_click_at(eq(420.0), eq(420.0), any())).thenReturn(ok());
        when(browser.extension_browser_press_key(eq("x"), any())).thenReturn(ok());
        when(browser.extension_browser_click_at_linear(eq(1357.0), eq(258.0), any())).thenReturn(ok());
        when(browser.extension_browser_scroll_at(eq("down"), eq(650), anyDouble(), anyDouble(), any()))
                .thenReturn(ok());

        String out = tool.lead_browser_douyin_collect_first_video_comments("openclaw", null);

        JsonNode j = mapper.readTree(out);
        assertThat(j.path("status").asText()).isEqualTo("PARTIAL_COLLECTED");
        ArgumentCaptor<Double> hoverX = ArgumentCaptor.forClass(Double.class);
        verify(browser, atLeastOnce()).extension_browser_hover_at_linear(hoverX.capture(), anyDouble(), any());
        assertThat(hoverX.getAllValues()).allSatisfy(x -> assertThat(x).isGreaterThanOrEqualTo(1250.0));
        ArgumentCaptor<Double> scrollX = ArgumentCaptor.forClass(Double.class);
        verify(browser, atLeastOnce()).extension_browser_scroll_at(
                eq("down"), eq(650), scrollX.capture(), anyDouble(), any());
        assertThat(scrollX.getAllValues()).allSatisfy(x -> assertThat(x).isGreaterThanOrEqualTo(1250.0));
    }

    private String ok() {
        return "{\"ok\":true}";
    }

    private String miss(String message) {
        return "{\"ok\":false,\"code\":\"GROUNDING_MISS\",\"message\":\"" + message + "\"}";
    }

    private String observe(String url, String title, String tree) throws Exception {
        return observe(url, title, tree, 1280, 800);
    }

    private String observe(String url, String title, String tree, int viewportW, int viewportH) throws Exception {
        return mapper.writeValueAsString(java.util.Map.of(
                "ok", true,
                "snapshot_id", "snap-1",
                "url", url,
                "title", title,
                "viewport", java.util.Map.of("w", viewportW, "h", viewportH),
                "tree", tree));
    }
}
