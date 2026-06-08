package vip.mate.lead.douyin.model;

import java.util.Map;

public record DouyinCommentItem(
        String videoKey,
        String commentKey,
        String parentCommentKey,
        String authorName,
        String authorProfileUrl,
        String authorAvatarUrl,
        String text,
        Integer likeCount,
        Integer replyCount,
        ClickTarget authorTarget,
        Map<String, Object> metadata
) {
    public DouyinCommentItem {
        videoKey = nullToBlank(videoKey);
        commentKey = nullToBlank(commentKey);
        parentCommentKey = blankToNull(parentCommentKey);
        authorName = nullToBlank(authorName);
        authorProfileUrl = blankToNull(authorProfileUrl);
        authorAvatarUrl = blankToNull(authorAvatarUrl);
        text = nullToBlank(text);
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
        if (commentKey.isBlank()) {
            commentKey = stableKey(videoKey, authorName, text);
        }
    }

    public boolean hasAuthorOpenTarget() {
        return authorProfileUrl != null || authorTarget != null || !authorName.isBlank();
    }

    private static String stableKey(String videoKey, String authorName, String text) {
        String raw = nullToBlank(videoKey) + "|" + nullToBlank(authorName) + "|" + nullToBlank(text);
        return "comment-" + Integer.toHexString(raw.hashCode());
    }

    private static String nullToBlank(String value) {
        return value == null ? "" : value.trim();
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    public record ClickTarget(Double x, Double y, String ref, Map<String, Object> bbox) {
        public ClickTarget {
            bbox = bbox == null ? Map.of() : Map.copyOf(bbox);
        }

        public boolean hasPoint() {
            return x != null && y != null && Double.isFinite(x) && Double.isFinite(y);
        }
    }
}
