package vip.mate.lead.douyin.api;

public record DouyinLeadRunListItem(
        String runId,
        String taskId,
        String keyword,
        String sort,
        String status,
        int requestedVideoLimit,
        int processedVideos,
        int failedVideos,
        int commentsCollected,
        int matchedComments,
        int engagementsCreated,
        String failureCode,
        String failureMessage,
        String createTime,
        String updateTime
) {
}
