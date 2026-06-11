package vip.mate.lead.douyin.model;

import java.util.List;
import java.util.Map;

public record DouyinLeadRunSummary(
        int requestedVideoLimit,
        int processedVideos,
        int succeededVideos,
        int failedVideos,
        int commentsCollected,
        int declaredCommentCount,
        int remainingDeclaredComments,
        double collectionCoverage,
        int matchedComments,
        int engagementsCreated,
        List<Map<String, Object>> videoResults
) {
    public DouyinLeadRunSummary {
        requestedVideoLimit = Math.max(0, requestedVideoLimit);
        processedVideos = Math.max(0, processedVideos);
        succeededVideos = Math.max(0, succeededVideos);
        failedVideos = Math.max(0, failedVideos);
        commentsCollected = Math.max(0, commentsCollected);
        declaredCommentCount = Math.max(0, declaredCommentCount);
        remainingDeclaredComments = Math.max(0, remainingDeclaredComments);
        collectionCoverage = declaredCommentCount > 0
                ? Math.min(1.0d, Math.max(0.0d, collectionCoverage))
                : 0.0d;
        matchedComments = Math.max(0, matchedComments);
        engagementsCreated = Math.max(0, engagementsCreated);
        videoResults = videoResults == null ? List.of() : List.copyOf(videoResults);
    }

    public static DouyinLeadRunSummary empty(int requestedVideoLimit) {
        return new DouyinLeadRunSummary(
                requestedVideoLimit,
                0,
                0,
                0,
                0,
                0,
                0,
                0.0d,
                0,
                0,
                List.of());
    }
}
