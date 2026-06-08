package vip.mate.lead.douyin.model;

import java.util.List;
import java.util.Map;

public record CommentCollectionResult(
        List<DouyinCommentItem> comments,
        int declaredCommentCount,
        boolean complete,
        String stopReason,
        int scrollAttempts,
        Map<String, Object> metadata
) {
    public CommentCollectionResult(
            List<DouyinCommentItem> comments,
            int declaredCommentCount,
            boolean complete,
            String stopReason,
            int scrollAttempts
    ) {
        this(comments, declaredCommentCount, complete, stopReason, scrollAttempts, Map.of());
    }

    public CommentCollectionResult {
        comments = comments == null ? List.of() : List.copyOf(comments);
        stopReason = stopReason == null || stopReason.isBlank() ? "unknown" : stopReason;
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }
}
