package vip.mate.browser.orchestrator.domain;

import org.junit.jupiter.api.Test;

import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

class GroundingHintTest {

    @Test
    void nearLabel_isOptional_existing2argCtorStillWorks() {
        var hint = new GroundingHint.A11yMatch("button", Pattern.compile("Submit"));

        assertThat(hint.role()).isEqualTo("button");
        assertThat(hint.namePattern().pattern()).isEqualTo("Submit");
        assertThat(hint.filter()).isEqualTo("interactive");
        assertThat(hint.nearLabel()).isNull();
    }

    @Test
    void nearLabel_isOptional_existing3argCtorStillWorks() {
        var hint = new GroundingHint.A11yMatch("button", Pattern.compile("Submit"), "all");

        assertThat(hint.role()).isEqualTo("button");
        assertThat(hint.namePattern().pattern()).isEqualTo("Submit");
        assertThat(hint.filter()).isEqualTo("all");
        assertThat(hint.nearLabel()).isNull();
    }

    @Test
    void nearLabel_can_be_null() {
        var hint = new GroundingHint.A11yMatch("button", Pattern.compile("Submit"), "interactive", null);

        assertThat(hint.nearLabel()).isNull();
    }

    @Test
    void nearLabel_can_be_set() {
        var hint = new GroundingHint.A11yMatch("button", Pattern.compile("Submit"), "interactive", "Comments");

        assertThat(hint.nearLabel()).isEqualTo("Comments");
    }

    @Test
    void a11yMatch_with_nearLabel_preservesAllFields() {
        var pattern = Pattern.compile("^Like$");
        var hint = new GroundingHint.A11yMatch("button", pattern, "default", "Comments");

        assertThat(hint.role()).isEqualTo("button");
        assertThat(hint.namePattern()).isSameAs(pattern);
        assertThat(hint.filter()).isEqualTo("default");
        assertThat(hint.nearLabel()).isEqualTo("Comments");
    }
}
