package vip.mate.lead.douyin.api;

import org.junit.jupiter.api.Test;
import vip.mate.exception.MateClawException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DouyinLeadAcquisitionStartRequestTest {

    @Test
    void normalizesOptionalV2Defaults() {
        var input = new DouyinLeadAcquisitionStartRequest(
                "易企秀",
                null,
                null,
                "",
                null,
                null,
                null).normalized();

        assertThat(input.keyword()).isEqualTo("易企秀");
        assertThat(input.sort()).isEqualTo("most_liked");
        assertThat(input.videoLimit()).isEqualTo(50);
        assertThat(input.commentMatchRule()).isBlank();
        assertThat(input.dmDraft()).isEqualTo("你好");
        assertThat(input.engage()).isTrue();
        assertThat(input.sendDm()).isFalse();
    }

    @Test
    void rejectsBlankKeyword() {
        assertThatThrownBy(() -> new DouyinLeadAcquisitionStartRequest(
                " ",
                null,
                null,
                null,
                null,
                null,
                null).normalized())
                .isInstanceOf(MateClawException.class)
                .hasMessageContaining("Douyin keyword is required");
    }

    @Test
    void rejectsVideoLimitOutsideAllowedRange() {
        assertThatThrownBy(() -> new DouyinLeadAcquisitionStartRequest(
                "易企秀",
                "most_liked",
                51,
                null,
                null,
                null,
                null).normalized())
                .isInstanceOf(MateClawException.class)
                .hasMessageContaining("between 1 and 50");
    }
}
