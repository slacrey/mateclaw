package vip.mate.tool.builtin;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import vip.mate.lead.douyin.DouyinLeadAcquisitionRunService;
import vip.mate.lead.douyin.api.DouyinLeadAcquisitionQueryService;
import vip.mate.lead.douyin.api.DouyinLeadAcquisitionRunResponse;
import vip.mate.lead.douyin.api.RunTimelineEventDTO;
import vip.mate.lead.douyin.model.DouyinLeadAcquisitionInput;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DouyinLeadAcquisitionToolTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void partialCollectionGuidanceForbidsUnsupportedPlatformLimitClaims() throws Exception {
        DouyinLeadAcquisitionRunService runService = mock(DouyinLeadAcquisitionRunService.class);
        DouyinLeadAcquisitionQueryService queryService = mock(DouyinLeadAcquisitionQueryService.class);
        DouyinLeadAcquisitionTool tool = new DouyinLeadAcquisitionTool(runService, queryService, mapper);
        var response = new DouyinLeadAcquisitionRunResponse(
                "1",
                "2",
                "succeeded",
                60,
                0,
                List.of(),
                List.of(),
                List.of(),
                List.of(new RunTimelineEventDTO(
                        "3",
                        null,
                        "lead.comments.collected",
                        "info",
                        """
                        {
                          "commentsCollected": 60,
                          "declaredCommentCount": 7166,
                          "complete": false,
                          "scrollAttempts": 123,
                          "stopReason": "NO_NEW_ITEMS_STABLE_WITHOUT_SCROLL_EVIDENCE",
                          "collectionCoverage": 0.0084,
                          "effectiveScrolls": 106,
                          "advancedWindows": 12,
                          "totalNewItems": 60,
                          "stableNoNewWindows": 18
                        }
                        """)));
        when(runService.runSync(eq(1L), eq(1L), any(DouyinLeadAcquisitionInput.class), eq(queryService)))
                .thenReturn(response);

        String raw = tool.douyinLeadAcquisitionRun(
                "openclaw",
                "most_liked",
                1,
                "openclaw",
                "你好",
                false,
                false,
                null);

        var root = mapper.readTree(raw);
        var guidance = root.path("reportingGuidance");
        assertThat(guidance.path("collectionStatus").asText()).isEqualTo("partial_unverified");
        assertThat(guidance.path("allowedSummary").asText())
                .contains("Automatic collection is incomplete")
                .contains("did not confirm the bottom");
        assertThat(guidance.path("unsupportedConclusions").toString())
                .contains("anti-bot")
                .contains("not logged in")
                .contains("loading limit");
        assertThat(guidance.path("bottomConfirmed").asBoolean()).isFalse();
    }

    @Test
    void topLevelDomCompletionGuidanceMentionsCollapsedReplies() throws Exception {
        DouyinLeadAcquisitionRunService runService = mock(DouyinLeadAcquisitionRunService.class);
        DouyinLeadAcquisitionQueryService queryService = mock(DouyinLeadAcquisitionQueryService.class);
        DouyinLeadAcquisitionTool tool = new DouyinLeadAcquisitionTool(runService, queryService, mapper);
        var response = new DouyinLeadAcquisitionRunResponse(
                "1",
                "2",
                "succeeded",
                65,
                0,
                List.of(),
                List.of(),
                List.of(),
                List.of(new RunTimelineEventDTO(
                        "3",
                        null,
                        "lead.comments.collected",
                        "info",
                        """
                        {
                          "commentsCollected": 65,
                          "declaredCommentCount": 90,
                          "complete": true,
                          "scrollAttempts": 16,
                          "stopReason": "END_OF_LIST_TOP_LEVEL",
                          "collectionCoverage": 0.7222,
                          "effectiveScrolls": 16,
                          "advancedWindows": 7,
                          "totalNewItems": 65,
                          "stableNoNewWindows": 1,
                          "topLevelCollectionComplete": true,
                          "declaredCountMismatch": true,
                          "declaredTotalMayIncludeReplies": true,
                          "replyExpansionMode": "disabled_v1_quality_first"
                        }
                        """)));
        when(runService.runSync(eq(1L), eq(1L), any(DouyinLeadAcquisitionInput.class), eq(queryService)))
                .thenReturn(response);

        String raw = tool.douyinLeadAcquisitionRun(
                "易企秀",
                "most_liked",
                1,
                "",
                "你好",
                false,
                false,
                null);

        var guidance = mapper.readTree(raw).path("reportingGuidance");
        assertThat(guidance.path("collectionStatus").asText()).isEqualTo("complete");
        assertThat(guidance.path("topLevelCollectionComplete").asBoolean()).isTrue();
        assertThat(guidance.path("declaredTotalMayIncludeReplies").asBoolean()).isTrue();
        assertThat(guidance.path("allowedSummary").asText())
                .contains("top-level DOM comment collection")
                .contains("reply expansion is disabled");
    }
}
