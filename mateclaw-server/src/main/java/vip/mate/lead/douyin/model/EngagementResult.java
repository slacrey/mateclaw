package vip.mate.lead.douyin.model;

public record EngagementResult(
        DouyinCommentItem comment,
        String author,
        String profileUrl,
        boolean profileOpened,
        boolean followConfirmed,
        boolean dmOpened,
        boolean draftTyped,
        boolean sent,
        String status,
        String failureCode,
        String failureMessage
) {
    public static EngagementResult failed(DouyinCommentItem comment, String code, String message) {
        return new EngagementResult(
                comment,
                comment == null ? "" : comment.authorName(),
                comment == null ? null : comment.authorProfileUrl(),
                false,
                false,
                false,
                false,
                false,
                "failed",
                code,
                message);
    }
}
