package vip.mate.lead.douyin.api;

import java.util.List;

public record DouyinLeadAcquisitionRunResponse(
        String runId,
        String taskId,
        String status,
        int commentsCollected,
        int matchedComments,
        List<LeadCommentDTO> comments,
        List<LeadCommentDTO> matches,
        List<LeadEngagementDTO> engagements,
        List<RunTimelineEventDTO> events
) {
    public DouyinLeadAcquisitionRunResponse {
        comments = comments == null ? List.of() : List.copyOf(comments);
        matches = matches == null ? List.of() : List.copyOf(matches);
        engagements = engagements == null ? List.of() : List.copyOf(engagements);
        events = events == null ? List.of() : List.copyOf(events);
    }

    public static DouyinLeadAcquisitionRunResponse started(Long runId, Long taskId, String status) {
        return new DouyinLeadAcquisitionRunResponse(
                id(runId), id(taskId), status, 0, 0, List.of(), List.of(), List.of(), List.of());
    }

    static String id(Long value) {
        return value == null ? null : String.valueOf(value);
    }
}
