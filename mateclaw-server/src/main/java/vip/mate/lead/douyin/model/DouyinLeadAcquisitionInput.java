package vip.mate.lead.douyin.model;

public record DouyinLeadAcquisitionInput(
        String keyword,
        String sort,
        int videoLimit,
        String commentMatchRule,
        String dmDraft,
        boolean sendDm,
        boolean engage
) {
    public static final String DEFAULT_KEYWORD = "openclaw";
    public static final String DEFAULT_SORT = "most_liked";
    public static final int DEFAULT_VIDEO_LIMIT = 50;
    public static final int MIN_VIDEO_LIMIT = 1;
    public static final int MAX_VIDEO_LIMIT = 50;
    public static final String DEFAULT_COMMENT_MATCH_RULE = "";
    public static final String DEFAULT_DM_DRAFT = "你好";

    public DouyinLeadAcquisitionInput(
            String keyword,
            String sort,
            int videoLimit,
            String commentMatchRule,
            String dmDraft,
            boolean sendDm
    ) {
        this(keyword, sort, videoLimit, commentMatchRule, dmDraft, sendDm, true);
    }

    public DouyinLeadAcquisitionInput {
        keyword = defaulted(keyword, DEFAULT_KEYWORD);
        sort = defaulted(sort, DEFAULT_SORT);
        videoLimit = videoLimit <= 0 ? DEFAULT_VIDEO_LIMIT : Math.min(Math.max(videoLimit, MIN_VIDEO_LIMIT), MAX_VIDEO_LIMIT);
        commentMatchRule = optional(commentMatchRule);
        dmDraft = defaulted(dmDraft, DEFAULT_DM_DRAFT);
    }

    public static DouyinLeadAcquisitionInput defaults() {
        return new DouyinLeadAcquisitionInput(
                DEFAULT_KEYWORD,
                DEFAULT_SORT,
                DEFAULT_VIDEO_LIMIT,
                DEFAULT_COMMENT_MATCH_RULE,
                DEFAULT_DM_DRAFT,
                false,
                true);
    }

    private static String defaulted(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private static String optional(String value) {
        return value == null ? "" : value.trim();
    }
}
