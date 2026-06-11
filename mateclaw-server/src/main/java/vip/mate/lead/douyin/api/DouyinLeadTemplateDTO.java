package vip.mate.lead.douyin.api;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import vip.mate.lead.douyin.model.CommentMatchRule;
import vip.mate.os.run.model.LeadTemplateEntity;

import java.util.List;

public record DouyinLeadTemplateDTO(
        String id,
        String name,
        String keyword,
        String sort,
        int videoLimit,
        List<CommentMatchRule> matchRules,
        String dmDraft,
        boolean engage,
        boolean sendDm,
        String createTime,
        String updateTime
) {
    static DouyinLeadTemplateDTO from(LeadTemplateEntity row, ObjectMapper mapper) {
        return new DouyinLeadTemplateDTO(
                DouyinLeadAcquisitionRunResponse.id(row.getId()),
                row.getName(),
                row.getKeyword(),
                row.getSortMode(),
                row.getVideoLimit() == null ? 50 : row.getVideoLimit(),
                parseMatchRules(row.getMatchRulesJson(), mapper),
                row.getDmDraft(),
                !Boolean.FALSE.equals(row.getEngage()),
                Boolean.TRUE.equals(row.getSendDm()),
                row.getCreateTime() == null ? null : row.getCreateTime().toString(),
                row.getUpdateTime() == null ? null : row.getUpdateTime().toString());
    }

    private static List<CommentMatchRule> parseMatchRules(String value, ObjectMapper mapper) {
        if (value == null || value.isBlank() || mapper == null) {
            return List.of();
        }
        try {
            return CommentMatchRule.normalize(mapper.readValue(value, new TypeReference<List<CommentMatchRule>>() {}));
        } catch (Exception ignored) {
            return List.of();
        }
    }
}
