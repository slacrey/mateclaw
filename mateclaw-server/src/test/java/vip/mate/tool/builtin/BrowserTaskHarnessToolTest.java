package vip.mate.tool.builtin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.Queue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BrowserTaskHarnessToolTest {

    private ExtensionBrowserTool browser;
    private ObjectMapper mapper;
    private BrowserTaskHarnessTool tool;

    @BeforeEach
    void setUp() {
        browser = mock(ExtensionBrowserTool.class);
        mapper = new ObjectMapper();
        tool = new BrowserTaskHarnessTool(browser, mapper);
    }

    @Test
    void douyinSearch_directUrlSuccessStopsWithoutFallback() throws Exception {
        when(browser.extension_browser_navigate(any(), eq("load"), any())).thenReturn(ok());
        when(browser.extension_browser_observe(eq("all"), any())).thenReturn(observe(
                "https://www.douyin.com/search/openclaw?type=general",
                "openclaw - 抖音搜索",
                "Textbox[ref=ref_1]: openclaw @{10,10 200x32}\n"
                        + "Tab[ref=ref_2]: 综合 @{10,60 40x20}\n"
                        + "Article[ref=ref_3]: openclaw 视频 @{10,90 200x120}"));

        String out = tool.extension_browser_douyin_search("openclaw", null);

        JsonNode j = mapper.readTree(out);
        assertThat(j.path("ok").asBoolean()).isTrue();
        assertThat(j.path("status").asText()).isEqualTo("DONE");
        assertThat(j.path("via").asText()).isEqualTo("direct_url");
        assertThat(j.path("attempts")).hasSize(2);
        verify(browser, never()).extension_browser_click(any(), any(), any(), any());
        verify(browser, never()).extension_browser_type(any(), any());
    }

    @Test
    void douyinSearch_fallsBackOnceAndThenStopsOnVerifiedSearchState() throws Exception {
        when(browser.extension_browser_navigate(any(), eq("load"), any())).thenReturn(ok());
        Queue<String> observes = new ArrayDeque<>();
        observes.add(observe(
                "https://www.douyin.com/",
                "抖音",
                "Textbox[ref=ref_1]: 搜索 @{10,10 200x32}"));
        observes.add(observe(
                "https://www.douyin.com/",
                "抖音",
                "Textbox[ref=ref_1]: 搜索 @{10,10 200x32}"));
        observes.add(observe(
                "https://www.douyin.com/search/openclaw?type=general",
                "openclaw - 抖音搜索",
                "Textbox[ref=ref_1]: openclaw @{10,10 200x32}\n"
                        + "Tab[ref=ref_2]: 综合 @{10,60 40x20}"));
        when(browser.extension_browser_observe(eq("all"), any()))
                .thenAnswer(ignored -> observes.remove());
        when(browser.extension_browser_wait(any(), any(), any(), any(), any())).thenReturn(ok());
        when(browser.extension_browser_click(eq("搜索"), eq("textbox"), any(), any())).thenReturn(ok());
        when(browser.extension_browser_type(eq("openclaw\n"), any())).thenReturn(ok());

        String out = tool.extension_browser_douyin_search("openclaw", null);

        JsonNode j = mapper.readTree(out);
        assertThat(j.path("ok").asBoolean()).isTrue();
        assertThat(j.path("via").asText()).isEqualTo("ui_fallback");
        verify(browser).extension_browser_click(eq("搜索"), eq("textbox"), any(), any());
        verify(browser).extension_browser_type(eq("openclaw\n"), any());
    }

    @Test
    void douyinSearch_repeatedSameStateStopsInsteadOfLooping() throws Exception {
        when(browser.extension_browser_navigate(any(), eq("load"), any())).thenReturn(ok());
        when(browser.extension_browser_observe(eq("all"), any())).thenReturn(observe(
                "https://www.douyin.com/",
                "抖音",
                "Textbox[ref=ref_1]: 搜索 @{10,10 200x32}"));
        when(browser.extension_browser_wait(any(), any(), any(), any(), any())).thenReturn(ok());
        when(browser.extension_browser_click(eq("搜索"), eq("textbox"), any(), any())).thenReturn(ok());
        when(browser.extension_browser_type(eq("openclaw\n"), any())).thenReturn(ok());

        String out = tool.extension_browser_douyin_search("openclaw", null);

        JsonNode j = mapper.readTree(out);
        assertThat(j.path("ok").asBoolean()).isFalse();
        assertThat(j.path("status").asText()).isEqualTo("BLOCKED_LOOP");
        verify(browser).extension_browser_click(eq("搜索"), eq("textbox"), any(), any());
        verify(browser).extension_browser_type(eq("openclaw\n"), any());
    }

    @Test
    void douyinSearch_propagatesNoSessionFromNavigate() throws Exception {
        when(browser.extension_browser_navigate(any(), eq("load"), any()))
                .thenReturn(error("NO_SESSION", "no browser is connected"));

        String out = tool.extension_browser_douyin_search("openclaw", null);

        JsonNode j = mapper.readTree(out);
        assertThat(j.path("ok").asBoolean()).isFalse();
        assertThat(j.path("status").asText()).isEqualTo("NO_SESSION");
        assertThat(j.path("failed_step").asText()).isEqualTo("navigate_direct");
    }

    private String ok() {
        return "{\"ok\":true}";
    }

    private String error(String code, String message) {
        return "{\"ok\":false,\"code\":\"" + code + "\",\"message\":\"" + message + "\"}";
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
