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
    void douyinOpenFirstVideoComments_clicksCommentButtonEvenWhenCommentDataVisible() throws Exception {
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
        verify(browser).extension_browser_click_at(eq(1156.0), eq(444.0), any());
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
        verify(browser).extension_browser_click_at(eq(801.0), eq(193.0), any());
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
        when(browser.extension_browser_scroll(eq("down"), eq(650), any())).thenReturn(ok());

        String out = tool.lead_browser_douyin_open_first_video_match_comment_user(
                "有一种和“老年人玩不懂智能手机”一样的无力感", 1, null);

        JsonNode j = mapper.readTree(out);
        assertThat(j.path("ok").asBoolean()).isFalse();
        assertThat(j.path("status").asText()).isEqualTo("COMMENT_MATCH_NOT_FOUND");
        assertThat(j.path("candidate_comments")).isNotEmpty();
        verify(browser, never()).extension_browser_click(eq("关注"), eq("button"), any(), any());
    }

    @Test
    void douyinDebugFirstCommentFollowOpenDm_readsTabbedCommentsAndClicksFollowThenDm() throws Exception {
        Queue<String> observes = new ArrayDeque<>();
        observes.add(observe(
                "https://www.douyin.com/jingxuan/search/openclaw?modal_id=7615879690590375183&type=general",
                "发现更多精彩视频 - 抖音搜索",
                "Text[ref=ref_detail_tab]: 详情 @{660,84 32x24}\n"
                        + "Text[ref=ref_works_tab]: TA的作品 @{735,84 72x24}\n"
                        + "Text[ref=ref_comment_tab]: 评论 @{842,84 32x24}\n"
                        + "Text[ref=ref_ai_tab]: 问AI @{918,84 36x24}\n"
                        + "Link[ref=ref_author_1]: 川流不息（有关注必回） @{690,150 170x26}\n"
                        + "Text[ref=ref_comment_1]: 这个工具对新手来说真的有点复杂，像不会用智能手机 @{690,186 380x42}\n"
                        + "Link[ref=ref_author_2]: 向暖 @{690,260 48x26}\n"
                        + "Text[ref=ref_comment_2]: 已经跟着教程跑通了 @{690,296 200x28}",
                1280,
                575));
        observes.add(observe(
                "https://www.douyin.com/user/MS4wLjABAAAA",
                "川流不息的主页",
                "Text[ref=ref_name]: 川流不息（有关注必回） @{520,150 180x30}\n"
                        + "Button[ref=ref_follow]: 关注 @{920,160 88x36}\n"
                        + "Button[ref=ref_dm]: 私信 @{1018,160 88x36}\n"
                        + "Text[ref=ref_fans]: 粉丝 128 @{520,220 120x28}\n"
                        + "Text[ref=ref_works]: 作品 12 @{660,220 110x28}"));
        observes.add(observe(
                "https://www.douyin.com/user/MS4wLjABAAAA",
                "川流不息的主页",
                "Text[ref=ref_name]: 川流不息（有关注必回） @{520,150 180x30}\n"
                        + "Button[ref=ref_followed]: 已关注 @{920,160 88x36}\n"
                        + "Button[ref=ref_dm]: 私信 @{1018,160 88x36}\n"
                        + "Text[ref=ref_fans]: 粉丝 128 @{520,220 120x28}\n"
                        + "Text[ref=ref_works]: 作品 12 @{660,220 110x28}"));
        observes.add(observe(
                "https://www.douyin.com/im/conversation/123",
                "私信",
                "Text[ref=ref_peer]: 川流不息（有关注必回） @{520,80 180x30}\n"
                        + "Textbox[ref=ref_input]: 输入消息 @{520,520 420x44}\n"
                        + "Button[ref=ref_send]: 发送 @{960,520 72x44}"));
        when(browser.extension_browser_observe(eq("all"), any()))
                .thenAnswer(ignored -> observes.remove());
        when(browser.extension_browser_click_at(eq(775.0), eq(163.0), any())).thenReturn(ok());
        when(browser.extension_browser_click_at(eq(964.0), eq(178.0), any())).thenReturn(ok());
        when(browser.extension_browser_click_at(eq(1062.0), eq(178.0), any())).thenReturn(ok());

        String out = tool.lead_browser_douyin_debug_first_comment_follow_open_dm(null);

        JsonNode j = mapper.readTree(out);
        assertThat(j.path("ok").asBoolean()).isTrue();
        assertThat(j.path("status").asText()).isEqualTo("DONE_DM_OPENED");
        assertThat(j.path("first_author").asText()).contains("川流不息");
        assertThat(j.path("first_comment").asText()).contains("新手");
        assertThat(j.path("follow_clicked").asBoolean()).isTrue();
        assertThat(j.path("dm_opened").asBoolean()).isTrue();
        assertThat(j.path("candidate_comments")).isNotEmpty();
        verify(browser).extension_browser_click_at(eq(775.0), eq(163.0), any());
        verify(browser).extension_browser_click_at(eq(964.0), eq(178.0), any());
        verify(browser).extension_browser_click_at(eq(1062.0), eq(178.0), any());
        verify(browser, never()).extension_browser_type(any(), any());
        verify(browser, never()).extension_browser_type_at(any(), any(), any());
    }

    @Test
    void douyinDebugFirstCommentFollowOpenDm_followsProfileOpenedInActiveTab() throws Exception {
        Queue<String> mainObserves = new ArrayDeque<>();
        mainObserves.add(observe(
                "https://www.douyin.com/jingxuan/search/openclaw?modal_id=7615879690590375183&type=general",
                "发现更多精彩视频 - 抖音搜索",
                "Text[ref=ref_detail_tab]: 详情 @{660,84 32x24}\n"
                        + "Text[ref=ref_works_tab]: TA的作品 @{735,84 72x24}\n"
                        + "Text[ref=ref_comment_tab]: 评论 @{842,84 32x24}\n"
                        + "Text[ref=ref_ai_tab]: 问AI @{918,84 36x24}\n"
                        + "Link[ref=ref_avatar_1]: 川流不息（有关注必回）头像 @{690,120 170x26}\n"
                        + "Link[ref=ref_author_1]: 川流不息（有关注必回） @{690,150 170x26}\n"
                        + "Text[ref=ref_comment_1]: 这个工具对新手来说真的有点复杂，像不会用智能手机 @{690,186 380x42}",
                1280,
                575));
        Queue<String> activeObserves = new ArrayDeque<>();
        activeObserves.add(observe(
                "https://www.douyin.com/user/MS4wLjABAAAA",
                "川流不息的主页",
                "Text[ref=ref_name]: 川流不息（有关注必回） @{520,150 180x30}\n"
                        + "Button[ref=ref_follow]: 关注 @{920,160 88x36}\n"
                        + "Button[ref=ref_dm]: 私信 @{1018,160 88x36}\n"
                        + "Text[ref=ref_fans]: 粉丝 128 @{520,220 120x28}\n"
                        + "Text[ref=ref_likes]: 获赞 2048 @{660,220 110x28}"));
        activeObserves.add(observe(
                "https://www.douyin.com/user/MS4wLjABAAAA",
                "川流不息的主页",
                "Text[ref=ref_name]: 川流不息（有关注必回） @{520,150 180x30}\n"
                        + "Button[ref=ref_followed]: 已关注 @{920,160 88x36}\n"
                        + "Button[ref=ref_dm]: 私信 @{1018,160 88x36}\n"
                        + "Text[ref=ref_fans]: 粉丝 128 @{520,220 120x28}\n"
                        + "Text[ref=ref_likes]: 获赞 2048 @{660,220 110x28}"));
        activeObserves.add(observe(
                "https://www.douyin.com/im/conversation/123",
                "私信",
                "Text[ref=ref_peer]: 川流不息（有关注必回） @{520,80 180x30}\n"
                        + "Textbox[ref=ref_input]: 输入消息 @{520,520 420x44}\n"
                        + "Button[ref=ref_send]: 发送 @{960,520 72x44}"));
        when(browser.extension_browser_observe(eq("all"), any()))
                .thenAnswer(ignored -> mainObserves.remove());
        when(browser.extension_browser_observe_active(eq("all"), any()))
                .thenAnswer(ignored -> activeObserves.remove());
        when(browser.extension_browser_click_at(eq(775.0), eq(163.0), any())).thenReturn(ok());
        when(browser.extension_browser_click_at_active(eq(964.0), eq(178.0), any())).thenReturn(ok());
        when(browser.extension_browser_click_at_active(eq(1062.0), eq(178.0), any())).thenReturn(ok());

        String out = tool.lead_browser_douyin_debug_first_comment_follow_open_dm(null);

        JsonNode j = mapper.readTree(out);
        assertThat(j.path("ok").asBoolean()).isTrue();
        assertThat(j.path("status").asText()).isEqualTo("DONE_DM_OPENED");
        assertThat(j.path("first_comment").asText()).contains("新手");
        assertThat(j.path("first_comment").asText()).doesNotContain("头像");
        assertThat(j.path("candidate_comments").toString()).doesNotContain("头像");
        assertThat(j.path("profile_url").asText()).contains("/im/conversation/");
        assertThat(j.path("follow_clicked").asBoolean()).isTrue();
        assertThat(j.path("dm_opened").asBoolean()).isTrue();
        verify(browser).extension_browser_click_at(eq(775.0), eq(163.0), any());
        verify(browser).extension_browser_click_at_active(eq(964.0), eq(178.0), any());
        verify(browser).extension_browser_click_at_active(eq(1062.0), eq(178.0), any());
        verify(browser, never()).extension_browser_click_at(eq(964.0), eq(178.0), any());
        verify(browser, never()).extension_browser_type(any(), any());
        verify(browser, never()).extension_browser_type_at(any(), any(), any());
    }

    @Test
    void douyinDebugFullFirstCommentFollowOpenDm_runsFromSearchThroughPrivateMessage() throws Exception {
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
                        + "Text[ref=ref_comment_1]: 这个工具对新手来说真的有点复杂，像不会用智能手机 @{690,186 380x42}",
                1280,
                575));
        observes.add(observe(
                "https://www.douyin.com/user/MS4wLjABAAAA",
                "川流不息的主页",
                "Text[ref=ref_name]: 川流不息（有关注必回） @{520,150 180x30}\n"
                        + "Button[ref=ref_follow]: 关注 @{920,160 88x36}\n"
                        + "Button[ref=ref_dm]: 私信 @{1018,160 88x36}\n"
                        + "Text[ref=ref_fans]: 粉丝 128 @{520,220 120x28}\n"
                        + "Text[ref=ref_works]: 作品 12 @{660,220 110x28}"));
        observes.add(observe(
                "https://www.douyin.com/user/MS4wLjABAAAA",
                "川流不息的主页",
                "Text[ref=ref_name]: 川流不息（有关注必回） @{520,150 180x30}\n"
                        + "Button[ref=ref_followed]: 已关注 @{920,160 88x36}\n"
                        + "Button[ref=ref_dm]: 私信 @{1018,160 88x36}\n"
                        + "Text[ref=ref_fans]: 粉丝 128 @{520,220 120x28}\n"
                        + "Text[ref=ref_works]: 作品 12 @{660,220 110x28}"));
        observes.add(observe(
                "https://www.douyin.com/im/conversation/123",
                "私信",
                "Text[ref=ref_peer]: 川流不息（有关注必回） @{520,80 180x30}\n"
                        + "Textbox[ref=ref_input]: 输入消息 @{520,520 420x44}\n"
                        + "Button[ref=ref_send]: 发送 @{960,520 72x44}"));
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
        when(browser.extension_browser_click_at(eq(775.0), eq(163.0), any())).thenReturn(ok());
        when(browser.extension_browser_click_at(eq(964.0), eq(178.0), any())).thenReturn(ok());
        when(browser.extension_browser_click_at(eq(1062.0), eq(178.0), any())).thenReturn(ok());

        String out = tool.lead_browser_douyin_debug_full_first_comment_follow_open_dm("openclaw", null);

        JsonNode j = mapper.readTree(out);
        assertThat(j.path("ok").asBoolean()).isTrue();
        assertThat(j.path("status").asText()).isEqualTo("DONE_DM_OPENED");
        assertThat(j.path("query").asText()).isEqualTo("openclaw");
        assertThat(j.path("first_author").asText()).contains("川流不息");
        assertThat(j.path("follow_clicked").asBoolean()).isTrue();
        assertThat(j.path("dm_opened").asBoolean()).isTrue();
        assertThat(j.path("candidate_comments")).isNotEmpty();
        verify(browser).extension_browser_press_key(eq("x"), any());
        verify(browser).extension_browser_click_at(eq(775.0), eq(163.0), any());
        verify(browser).extension_browser_click_at(eq(964.0), eq(178.0), any());
        verify(browser).extension_browser_click_at(eq(1062.0), eq(178.0), any());
        verify(browser, never()).extension_browser_type(any(), any());
        verify(browser, never()).extension_browser_type_at(argThat(text -> !"openclaw\n".equals(text)), any(), any());
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
