package vip.mate.lead.douyin.api;

import vip.mate.lead.douyin.model.DouyinLeadAcquisitionInput;
import vip.mate.lead.douyin.model.CommentMatchRule;
import vip.mate.exception.MateClawException;

import java.util.List;

public record DouyinLeadAcquisitionStartRequest(
        String keyword,
        String sort,
        Integer videoLimit,
        List<CommentMatchRule> matchRules,
        String dmDraft,
        Boolean sendDm,
        Boolean engage
) {
    public DouyinLeadAcquisitionStartRequest(
            String keyword,
            String sort,
            Integer videoLimit,
            List<CommentMatchRule> matchRules,
            String dmDraft,
            Boolean sendDm
    ) {
        this(keyword, sort, videoLimit, matchRules, dmDraft, sendDm, null);
    }

    public DouyinLeadAcquisitionInput normalized() {
        if (keyword == null || keyword.isBlank()) {
            throw new MateClawException("err.lead.douyin.keyword_required", "Douyin keyword is required");
        }
        if (videoLimit != null
                && (videoLimit < DouyinLeadAcquisitionInput.MIN_VIDEO_LIMIT
                || videoLimit > DouyinLeadAcquisitionInput.MAX_VIDEO_LIMIT)) {
            throw new MateClawException("err.lead.douyin.video_limit_invalid",
                    "Douyin videoLimit must be between 1 and 50");
        }
        return new DouyinLeadAcquisitionInput(
                keyword,
                sort,
                videoLimit == null ? DouyinLeadAcquisitionInput.DEFAULT_VIDEO_LIMIT : videoLimit,
                matchRules,
                dmDraft,
                Boolean.TRUE.equals(sendDm),
                engage == null || Boolean.TRUE.equals(engage));
    }
}
