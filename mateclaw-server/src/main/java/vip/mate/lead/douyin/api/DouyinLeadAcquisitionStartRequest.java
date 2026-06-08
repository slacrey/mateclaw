package vip.mate.lead.douyin.api;

import vip.mate.lead.douyin.model.DouyinLeadAcquisitionInput;

public record DouyinLeadAcquisitionStartRequest(
        String keyword,
        String sort,
        Integer videoLimit,
        String commentMatchRule,
        String dmDraft,
        Boolean sendDm,
        Boolean engage
) {
    public DouyinLeadAcquisitionStartRequest(
            String keyword,
            String sort,
            Integer videoLimit,
            String commentMatchRule,
            String dmDraft,
            Boolean sendDm
    ) {
        this(keyword, sort, videoLimit, commentMatchRule, dmDraft, sendDm, null);
    }

    public DouyinLeadAcquisitionInput normalized() {
        return new DouyinLeadAcquisitionInput(
                keyword,
                sort,
                videoLimit == null ? DouyinLeadAcquisitionInput.DEFAULT_VIDEO_LIMIT : videoLimit,
                commentMatchRule,
                dmDraft,
                Boolean.TRUE.equals(sendDm),
                engage == null || Boolean.TRUE.equals(engage));
    }
}
