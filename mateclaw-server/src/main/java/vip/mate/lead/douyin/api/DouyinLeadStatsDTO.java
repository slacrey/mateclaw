package vip.mate.lead.douyin.api;

import java.util.List;

public record DouyinLeadStatsDTO(
        int taskCount,
        int runningTasks,
        int succeededTasks,
        int failedTasks,
        int requestedVideos,
        int processedVideos,
        int succeededVideos,
        int failedVideos,
        int commentsCollected,
        int matchedComments,
        int engagementsCreated,
        int sentMessages,
        double matchRate,
        double engagementRate,
        double sendSuccessRate,
        List<FailureReason> failureReasons
) {
    public DouyinLeadStatsDTO {
        taskCount = Math.max(0, taskCount);
        runningTasks = Math.max(0, runningTasks);
        succeededTasks = Math.max(0, succeededTasks);
        failedTasks = Math.max(0, failedTasks);
        requestedVideos = Math.max(0, requestedVideos);
        processedVideos = Math.max(0, processedVideos);
        succeededVideos = Math.max(0, succeededVideos);
        failedVideos = Math.max(0, failedVideos);
        commentsCollected = Math.max(0, commentsCollected);
        matchedComments = Math.max(0, matchedComments);
        engagementsCreated = Math.max(0, engagementsCreated);
        sentMessages = Math.max(0, sentMessages);
        matchRate = clampRate(matchRate);
        engagementRate = clampRate(engagementRate);
        sendSuccessRate = clampRate(sendSuccessRate);
        failureReasons = failureReasons == null ? List.of() : List.copyOf(failureReasons);
    }

    public static DouyinLeadStatsDTO empty() {
        return new DouyinLeadStatsDTO(
                0, 0, 0, 0,
                0, 0, 0, 0,
                0, 0, 0, 0,
                0.0d, 0.0d, 0.0d,
                List.of());
    }

    private static double clampRate(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            return 0.0d;
        }
        return Math.max(0.0d, Math.min(1.0d, value));
    }

    public record FailureReason(String reason, int count) {
        public FailureReason {
            reason = reason == null || reason.isBlank() ? "UNKNOWN_FAILURE" : reason.trim();
            count = Math.max(0, count);
        }
    }
}
