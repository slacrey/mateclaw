package vip.mate.lead.douyin.model;

import java.util.List;

public record DouyinLeadAcquisitionInput(
        String keyword,
        String sort,
        int videoLimit,
        List<CommentMatchRule> matchRules,
        String dmDraft,
        boolean sendDm,
        boolean engage
) {
    public static final String DEFAULT_KEYWORD = "openclaw";
    public static final String DEFAULT_SORT = "comprehensive";
    public static final int DEFAULT_VIDEO_LIMIT = 50;
    public static final int MIN_VIDEO_LIMIT = 1;
    public static final int MAX_VIDEO_LIMIT = 50;
    public static final String DEFAULT_DM_DRAFT = "你好";

    public DouyinLeadAcquisitionInput(
            String keyword,
            String sort,
            int videoLimit,
            List<CommentMatchRule> matchRules,
            String dmDraft,
            boolean sendDm
    ) {
        this(keyword, sort, videoLimit, matchRules, dmDraft, sendDm, true);
    }

    public DouyinLeadAcquisitionInput(
            String keyword,
            String sort,
            int videoLimit,
            String dmDraft,
            boolean sendDm,
            boolean engage
    ) {
        this(keyword, sort, videoLimit, List.of(), dmDraft, sendDm, engage);
    }

    public DouyinLeadAcquisitionInput {
        keyword = defaulted(keyword, DEFAULT_KEYWORD);
        sort = defaulted(sort, DEFAULT_SORT);
        videoLimit = videoLimit <= 0 ? DEFAULT_VIDEO_LIMIT : Math.min(Math.max(videoLimit, MIN_VIDEO_LIMIT), MAX_VIDEO_LIMIT);
        matchRules = CommentMatchRule.normalize(matchRules);
        dmDraft = defaulted(dmDraft, DEFAULT_DM_DRAFT);
    }

    public static DouyinLeadAcquisitionInput defaults() {
        return new DouyinLeadAcquisitionInput(
                DEFAULT_KEYWORD,
                DEFAULT_SORT,
                DEFAULT_VIDEO_LIMIT,
                List.of(),
                DEFAULT_DM_DRAFT,
                false,
                true);
    }

    public boolean hasMatchRules() {
        return !matchRules.isEmpty();
    }

    private static String defaulted(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }
}
