package vip.mate.tool.builtin;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;
import vip.mate.agent.context.ChatOrigin;
import vip.mate.lead.douyin.DouyinLeadAcquisitionRunService;
import vip.mate.lead.douyin.api.DouyinLeadAcquisitionQueryService;
import vip.mate.lead.douyin.api.DouyinLeadAcquisitionRunResponse;
import vip.mate.lead.douyin.api.RunTimelineEventDTO;
import vip.mate.lead.douyin.model.DouyinLeadAcquisitionInput;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
@RequiredArgsConstructor
public class DouyinLeadAcquisitionTool {

    private final DouyinLeadAcquisitionRunService runService;
    private final DouyinLeadAcquisitionQueryService queryService;
    private final ObjectMapper objectMapper;

    @Tool(name = "douyin_lead_acquisition_run", description = """
            Start the dedicated Douyin lead-acquisition Skill V1 workflow.
            Use this tool for Douyin lead-acquisition tasks such as: search openclaw,
            sort by most liked, open the first video, collect loadable comments, match comment
            text, then open the matched comment author's profile, follow, open DM, and
            type a draft without sending. This tool executes synchronously and
            returns the real terminal result (SUCCEEDED/FAILED/ABORTED) when it finishes.
            The Douyin adapter owns platform-specific browser tactics, including using
            the video home search input and pressing Enter to submit search when the
            visible search button routes to AI search.

            Do not manually perform this workflow with extension_browser_click/type/scroll.
            For comment collection, never claim "full collection" unless the returned
            lead.comments.collected event has complete=true. If complete=false, report it
            as partial collection and include declaredCommentCount, commentsCollected,
            collectionCoverage, and stopReason.
            Never infer anti-bot limits, login limits, platform-side constraints, deletion,
            or hidden comments from NO_NEW_ITEMS_* stop reasons unless the returned
            reportingGuidance explicitly says that evidence was observed. Without explicit
            evidence, say "automatic collection did not complete and did not confirm the
            bottom of the list".
            The workflow matches COMMENT TEXT ONLY. Author names are never used as match
            criteria; after comment text matches, the workflow uses that comment item's
            bound author target for follow and DM draft actions.
            """)
    public String douyinLeadAcquisitionRun(
            @ToolParam(description = "Douyin search keyword. Default: openclaw", required = false)
            String keyword,
            @ToolParam(description = "Sort mode. Use most_liked for 最多点赞. Default: most_liked", required = false)
            String sort,
            @ToolParam(description = "Number of videos to process. V1 default and recommended value is 1.", required = false)
            Integer videoLimit,
            @ToolParam(description = "Optional comment text match rule. Multiple phrases are allowed; matching uses comment text only. When omitted, collection-only runs skip matching.", required = false)
            String commentMatchRule,
            @ToolParam(description = "DM draft to type after opening the matched author's DM. Default: 你好", required = false)
            String dmDraft,
            @ToolParam(description = "Whether to send the DM. Default false; false means type draft only.", required = false)
            Boolean sendDm,
            @ToolParam(description = "Whether to follow/open DM/draft after matching comments. Default true. Set false for collection-only V1 debugging.", required = false)
            Boolean engage,
            @Nullable ToolContext ctx) {

        ChatOrigin origin = ChatOrigin.from(ctx);
        Long workspaceId = origin.workspaceId() == null ? 1L : origin.workspaceId();
        Long createdBy = parseLongOrDefault(origin.requesterId(), 1L);
        DouyinLeadAcquisitionInput input = new DouyinLeadAcquisitionInput(
                keyword,
                sort,
                videoLimit == null ? DouyinLeadAcquisitionInput.DEFAULT_VIDEO_LIMIT : videoLimit,
                commentMatchRule,
                dmDraft,
                Boolean.TRUE.equals(sendDm),
                engage == null || Boolean.TRUE.equals(engage));
        DouyinLeadAcquisitionRunResponse result = runService.runSync(workspaceId, createdBy, input, queryService);
        boolean terminal = isTerminal(result.status());
        boolean succeeded = "succeeded".equalsIgnoreCase(result.status());
        return json(Map.of(
                "ok", succeeded,
                "terminal", terminal,
                "status", normalizeStatus(result.status()),
                "message", messageFor(result, terminal),
                "runId", result.runId(),
                "taskId", result.taskId(),
                "input", inputMap(input),
                "reportingGuidance", reportingGuidance(result),
                "result", result));
    }

    private Map<String, Object> reportingGuidance(DouyinLeadAcquisitionRunResponse result) {
        JsonNode collected = latestEventPayload(result, "lead.comments.collected");
        Map<String, Object> out = new LinkedHashMap<>();
        if (collected == null || collected.isMissingNode()) {
            out.put("collectionStatus", "not_reported");
            out.put("allowedSummary", "No comment collection event was returned; do not report collection totals.");
            out.put("unsupportedConclusions", unsupportedConclusions());
            return out;
        }
        boolean complete = collected.path("complete").asBoolean(false);
        int declared = collected.path("declaredCommentCount").asInt(0);
        int collectedCount = collected.path("commentsCollected").asInt(0);
        String stopReason = collected.path("stopReason").asText("");
        out.put("collectionStatus", complete ? "complete" : "partial_unverified");
        out.put("complete", complete);
        out.put("declaredCommentCount", declared);
        out.put("commentsCollected", collectedCount);
        out.put("collectionCoverage", collected.path("collectionCoverage").asDouble(0.0d));
        out.put("stopReason", stopReason);
        out.put("effectiveScrolls", collected.path("effectiveScrolls").asInt(0));
        out.put("advancedWindows", collected.path("advancedWindows").asInt(0));
        out.put("totalNewItems", collected.path("totalNewItems").asInt(0));
        out.put("stableNoNewWindows", collected.path("stableNoNewWindows").asInt(0));
        boolean bottomConfirmed = stopReason != null && stopReason.startsWith("END_OF_LIST");
        out.put("bottomConfirmed", bottomConfirmed);
        out.put("allowedSummary", complete
                ? "Comment collection reached a defined completion condition. Report the exact stopReason."
                : bottomConfirmed
                ? "The bottom marker was observed, but declared and collected counts do not match. Report it as incomplete and include declaredCommentCount, commentsCollected, remainingDeclaredComments, and stopReason."
                : "Automatic collection is incomplete. Report only the observed counts and stopReason; say the workflow did not confirm the bottom of the comment list.");
        out.put("unsupportedConclusions", unsupportedConclusions());
        return out;
    }

    private List<String> unsupportedConclusions() {
        return List.of(
                "Do not claim Douyin anti-bot/rate-limit/platform-side limits.",
                "Do not claim the browser was not logged in or login-limited.",
                "Do not claim comments were deleted, hidden, unavailable, or impossible to load.",
                "Do not claim the workflow reached Douyin's loading limit.",
                "Do not call NO_NEW_ITEMS_* a successful full collection.");
    }

    private JsonNode latestEventPayload(DouyinLeadAcquisitionRunResponse result, String type) {
        if (result == null || result.events() == null) {
            return null;
        }
        JsonNode latest = null;
        for (RunTimelineEventDTO event : result.events()) {
            if (event == null || !type.equals(event.type())) {
                continue;
            }
            latest = parsePayload(event.payloadJson());
        }
        return latest;
    }

    private JsonNode parsePayload(String payloadJson) {
        try {
            return objectMapper.readTree(payloadJson == null || payloadJson.isBlank() ? "{}" : payloadJson);
        } catch (Exception ignored) {
            return objectMapper.createObjectNode();
        }
    }

    private boolean isTerminal(String status) {
        return "succeeded".equalsIgnoreCase(status)
                || "failed".equalsIgnoreCase(status)
                || "aborted".equalsIgnoreCase(status);
    }

    private String normalizeStatus(String status) {
        return status == null ? "UNKNOWN" : status.toUpperCase(java.util.Locale.ROOT);
    }

    private String messageFor(DouyinLeadAcquisitionRunResponse result, boolean terminal) {
        if ("succeeded".equalsIgnoreCase(result.status())) {
            return "Douyin lead-acquisition workflow completed.";
        }
        if ("failed".equalsIgnoreCase(result.status()) || "aborted".equalsIgnoreCase(result.status())) {
            return "Douyin lead-acquisition workflow stopped before completion. Inspect result.events for the exact failure.";
        }
        return "Douyin lead-acquisition workflow returned status " + normalizeStatus(result.status()) + ".";
    }

    private Map<String, Object> inputMap(DouyinLeadAcquisitionInput input) {
        return Map.of(
                "keyword", input.keyword(),
                "sort", input.sort(),
                "videoLimit", input.videoLimit(),
                "commentMatchRule", input.commentMatchRule(),
                "dmDraft", input.dmDraft(),
                "sendDm", input.sendDm(),
                "engage", input.engage());
    }

    private Long parseLongOrDefault(String value, Long fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            return "{\"ok\":false,\"status\":\"JSON_ERROR\"}";
        }
    }
}
