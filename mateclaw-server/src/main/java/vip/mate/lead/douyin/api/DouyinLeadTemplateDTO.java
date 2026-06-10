package vip.mate.lead.douyin.api;

import vip.mate.os.run.model.LeadTemplateEntity;

public record DouyinLeadTemplateDTO(
        String id,
        String name,
        String keyword,
        String sort,
        int videoLimit,
        String commentMatchRule,
        String dmDraft,
        boolean engage,
        boolean sendDm,
        String createTime,
        String updateTime
) {
    static DouyinLeadTemplateDTO from(LeadTemplateEntity row) {
        return new DouyinLeadTemplateDTO(
                DouyinLeadAcquisitionRunResponse.id(row.getId()),
                row.getName(),
                row.getKeyword(),
                row.getSortMode(),
                row.getVideoLimit() == null ? 50 : row.getVideoLimit(),
                row.getCommentMatchRule(),
                row.getDmDraft(),
                !Boolean.FALSE.equals(row.getEngage()),
                Boolean.TRUE.equals(row.getSendDm()),
                row.getCreateTime() == null ? null : row.getCreateTime().toString(),
                row.getUpdateTime() == null ? null : row.getUpdateTime().toString());
    }
}
