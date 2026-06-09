package vip.mate.lead.douyin.api;

import vip.mate.os.run.model.LeadCommentEntity;

public record LeadCommentDTO(
        String id,
        String commentKey,
        String videoKey,
        String authorName,
        String authorProfileUrl,
        String text,
        boolean matched,
        Double matchScore,
        String matchReason
) {
    public static LeadCommentDTO from(LeadCommentEntity row) {
        return new LeadCommentDTO(
                DouyinLeadAcquisitionRunResponse.id(row.getId()),
                row.getCommentKey(),
                row.getVideoKey(),
                row.getAuthorName(),
                row.getAuthorProfileUrl(),
                row.getCommentText(),
                Boolean.TRUE.equals(row.getMatched()),
                row.getMatchScore(),
                row.getMatchReason());
    }
}
