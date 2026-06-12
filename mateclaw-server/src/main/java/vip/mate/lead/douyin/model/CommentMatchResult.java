package vip.mate.lead.douyin.model;

public record CommentMatchResult(
        DouyinCommentItem comment,
        boolean matched,
        double score,
        String reason
) {
    public boolean exact() {
        return matched && score >= 1.0d && reason != null && reason.startsWith("keyword_exact");
    }
}
