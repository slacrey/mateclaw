package vip.mate.lead.douyin.api;

import vip.mate.exception.MateClawException;
import vip.mate.lead.douyin.model.DouyinLeadAcquisitionInput;
import vip.mate.lead.douyin.model.CommentMatchRule;

import java.util.List;

public record DouyinLeadTemplateRequest(
        String name,
        String keyword,
        String sort,
        Integer videoLimit,
        List<CommentMatchRule> matchRules,
        String dmDraft,
        Boolean engage,
        Boolean sendDm
) {
    String normalizedName() {
        String value = name == null ? "" : name.trim();
        if (value.isBlank()) {
            throw new MateClawException("err.lead.template.name_required", "Template name is required");
        }
        return value.length() > 128 ? value.substring(0, 128) : value;
    }

    String normalizedKeyword() {
        String value = keyword == null ? "" : keyword.trim();
        return value.length() > 256 ? value.substring(0, 256) : value;
    }

    String normalizedSort() {
        String value = sort == null || sort.isBlank()
                ? DouyinLeadAcquisitionInput.DEFAULT_SORT
                : sort.trim().toLowerCase(java.util.Locale.ROOT);
        return switch (value) {
            case "comprehensive", "comprehensive_sort", "general", "default" -> DouyinLeadAcquisitionInput.DEFAULT_SORT;
            case "latest" -> "latest";
            case "most_liked", "like", "liked", "digg" -> "most_liked";
            default -> DouyinLeadAcquisitionInput.DEFAULT_SORT;
        };
    }

    int normalizedVideoLimit() {
        int value = videoLimit == null ? DouyinLeadAcquisitionInput.DEFAULT_VIDEO_LIMIT : videoLimit;
        return Math.max(1, Math.min(DouyinLeadAcquisitionInput.MAX_VIDEO_LIMIT, value));
    }

    List<CommentMatchRule> normalizedMatchRules() {
        return CommentMatchRule.normalize(matchRules);
    }

    String normalizedDmDraft() {
        String value = trimToLength(dmDraft, 500);
        return value.isBlank() ? "你好" : value;
    }

    boolean normalizedEngage() {
        return engage == null || engage;
    }

    boolean normalizedSendDm() {
        return normalizedEngage() && Boolean.TRUE.equals(sendDm);
    }

    private String trimToLength(String value, int max) {
        String normalized = value == null ? "" : value.trim();
        return normalized.length() > max ? normalized.substring(0, max) : normalized;
    }
}
