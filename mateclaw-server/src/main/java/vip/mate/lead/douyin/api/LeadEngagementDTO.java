package vip.mate.lead.douyin.api;

import vip.mate.os.run.model.LeadEngagementEntity;

public record LeadEngagementDTO(
        String id,
        String profileId,
        String commentId,
        String actionType,
        String status,
        String draftText,
        String failureCode,
        String failureMessage,
        String evidenceRef,
        boolean sent
) {
    public static LeadEngagementDTO from(LeadEngagementEntity row) {
        return new LeadEngagementDTO(
                DouyinLeadAcquisitionRunResponse.id(row.getId()),
                DouyinLeadAcquisitionRunResponse.id(row.getProfileId()),
                DouyinLeadAcquisitionRunResponse.id(row.getCommentId()),
                row.getEngagementType(),
                row.getStatus(),
                row.getDraftText(),
                row.getFailureCode(),
                row.getFailureMessage(),
                row.getEvidenceRef(),
                ("send_dm".equalsIgnoreCase(row.getEngagementType()) || "dm_draft".equalsIgnoreCase(row.getEngagementType()))
                        && "succeeded".equalsIgnoreCase(row.getStatus()));
    }
}
