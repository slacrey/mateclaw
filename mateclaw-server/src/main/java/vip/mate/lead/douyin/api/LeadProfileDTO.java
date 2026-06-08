package vip.mate.lead.douyin.api;

import vip.mate.os.run.model.LeadProfileEntity;

public record LeadProfileDTO(
        String id,
        String platform,
        String profileUrl,
        String displayName,
        String handle,
        String avatarUrl,
        String bio
) {
    public static LeadProfileDTO from(LeadProfileEntity row) {
        return new LeadProfileDTO(
                DouyinLeadAcquisitionRunResponse.id(row.getId()),
                row.getPlatform(),
                row.getProfileUrl(),
                row.getDisplayName(),
                row.getHandle(),
                row.getAvatarUrl(),
                row.getBio());
    }
}
