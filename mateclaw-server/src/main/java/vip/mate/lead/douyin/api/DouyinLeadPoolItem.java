package vip.mate.lead.douyin.api;

public record DouyinLeadPoolItem(
        String runId,
        String taskId,
        String keyword,
        String sort,
        String runStatus,
        String commentId,
        String commentKey,
        String videoKey,
        String authorName,
        String authorProfileUrl,
        String text,
        Double matchScore,
        String matchReason,
        String engagementId,
        String profileId,
        String profileUrl,
        String displayName,
        String actionType,
        String engagementStatus,
        boolean sent,
        String draftText,
        String failureCode,
        String failureMessage,
        String evidenceRef,
        String createTime,
        String updateTime
) {
}
