package vip.mate.tool.builtin;

import org.junit.jupiter.api.Test;
import org.springframework.ai.support.ToolCallbacks;
import vip.mate.lead.douyin.DouyinLeadAcquisitionRunService;
import vip.mate.lead.douyin.api.DouyinLeadAcquisitionQueryService;

import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class OldDouyinHarnessRemovedTest {

    @Test
    void extensionBrowserToolDoesNotExposeOldDouyinHarnessNames() {
        ExtensionBrowserTool tool = mock(ExtensionBrowserTool.class);

        assertThat(Arrays.stream(ToolCallbacks.from(tool))
                .map(callback -> callback.getToolDefinition().name()))
                .doesNotContain(
                        "lead_browser_douyin_search_sort_open_first_video_comments",
                        "lead_browser_douyin_collect_first_video_comments",
                        "lead_browser_douyin_collect_comments_across_videos",
                        "lead_browser_douyin_search_sort_first_video_match_comment_follow_open_dm_type_draft",
                        "extension_browser_douyin_search");
    }

    @Test
    void dedicatedDouyinLeadAcquisitionToolIsExposed() {
        DouyinLeadAcquisitionTool tool = new DouyinLeadAcquisitionTool(
                mock(DouyinLeadAcquisitionRunService.class),
                mock(DouyinLeadAcquisitionQueryService.class),
                new com.fasterxml.jackson.databind.ObjectMapper());

        assertThat(Arrays.stream(ToolCallbacks.from(tool))
                .map(callback -> callback.getToolDefinition().name()))
                .contains("douyin_lead_acquisition_run");
    }
}
