package vip.mate.browser.orchestrator.engine;

import org.junit.jupiter.api.Test;
import vip.mate.browser.orchestrator.domain.BBox;
import vip.mate.browser.orchestrator.domain.GroundedTarget;
import vip.mate.browser.orchestrator.domain.GroundingHint;
import vip.mate.browser.orchestrator.domain.GroundingResult;
import vip.mate.browser.orchestrator.domain.PageSnapshot;
import vip.mate.browser.orchestrator.domain.Viewport;

import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

class DomEngineTest {

    private final DomEngine engine = new DomEngine();

    @Test
    void hitWhenExactlyOneA11yLineMatchesRoleAndName() {
        var result = engine.ground(snapshot("""
                Button[ref=ref_1]: Submit @{100,200 80x32}
                Link[ref=ref_2]: Help @{300,400 60x18}
                """), new GroundingHint.A11yMatch("button", Pattern.compile("Submit")));

        assertThat(result).isInstanceOfSatisfying(GroundingResult.Hit.class, hit -> {
            assertThat(hit.target()).isEqualTo(new GroundedTarget(new BBox(100, 200, 80, 32), "ref_1"));
            assertThat(hit.evidence()).contains("role+name match");
        });
    }

    @Test
    void ambiguousWhenTwoLinesMatchSameRoleAndName() {
        var result = engine.ground(snapshot("""
                Button[ref=ref_1]: Submit @{100,200 80x32}
                Link[ref=ref_2]: Submit @{300,400 60x18}
                Button[ref=ref_3]: Submit @{500,600 90x40}
                """), new GroundingHint.A11yMatch("button", Pattern.compile("Submit")));

        assertThat(result).isInstanceOfSatisfying(GroundingResult.Ambiguous.class, ambiguous -> {
            assertThat(ambiguous.candidates()).containsExactly(
                    new GroundedTarget(new BBox(100, 200, 80, 32), "ref_1"),
                    new GroundedTarget(new BBox(500, 600, 90, 40), "ref_3"));
            assertThat(ambiguous.evidence()).contains("2 elements").contains("role=button");
        });
    }

    @Test
    void missWhenPatternDoesNotMatch() {
        var result = engine.ground(snapshot("""
                Button[ref=ref_1]: Cancel @{100,200 80x32}
                """), new GroundingHint.A11yMatch("button", Pattern.compile("Submit")));

        assertThat(result).isEqualTo(new GroundingResult.Miss(
                "no role+name match for role=button name=/Submit/"));
    }

    @Test
    void missWhenRoleDoesNotMatch_evenIfNameDoes() {
        var result = engine.ground(snapshot("""
                Link[ref=ref_1]: Submit @{100,200 80x32}
                """), new GroundingHint.A11yMatch("button", Pattern.compile("Submit")));

        assertThat(result).isInstanceOfSatisfying(GroundingResult.Miss.class,
                miss -> assertThat(miss.reason()).contains("role=button").contains("name=/Submit/"));
    }

    @Test
    void refIdHit_whenRefIdHintProvidedAndPresent() {
        var result = engine.ground(snapshot("""
                Button[ref=ref_1]: Submit @{100,200 80x32}
                """), new GroundingHint.ByRefId("ref_1"));

        assertThat(result).isEqualTo(new GroundingResult.Hit(
                new GroundedTarget(new BBox(100, 200, 80, 32), "ref_1"),
                "ref ref_1"));
    }

    @Test
    void refIdMiss_whenRefIdHintProvidedButAbsent() {
        var result = engine.ground(snapshot("""
                Button[ref=ref_1]: Submit @{100,200 80x32}
                """), new GroundingHint.ByRefId("ref_2"));

        assertThat(result).isEqualTo(new GroundingResult.Miss("ref ref_2 not in snapshot"));
    }

    @Test
    void roleMatchIsCaseInsensitive() {
        var result = engine.ground(snapshot("""
                button[ref=ref_1]: Submit @{100,200 80x32}
                """), new GroundingHint.A11yMatch("Button", Pattern.compile("Submit")));

        assertThat(result).isInstanceOf(GroundingResult.Hit.class);
    }

    @Test
    void namePatternUsesRegex_notSubstring() {
        var result = engine.ground(snapshot("""
                Button[ref=ref_1]: Submit @{100,200 80x32}
                Button[ref=ref_2]: Submit form @{300,400 120x32}
                """), new GroundingHint.A11yMatch("button", Pattern.compile("^Submit$")));

        assertThat(result).isInstanceOfSatisfying(GroundingResult.Hit.class,
                hit -> assertThat(hit.target().refId()).isEqualTo("ref_1"));
    }

    @Test
    void evidenceFieldOnHit_isInformative() {
        var result = engine.ground(snapshot("""
                Button[ref=ref_1]: Submit @{100,200 80x32}
                """), new GroundingHint.A11yMatch("button", Pattern.compile("Submit")));

        assertThat(result).isInstanceOfSatisfying(GroundingResult.Hit.class,
                hit -> assertThat(hit.evidence())
                        .contains("role+name match")
                        .contains("Button")
                        .contains("Submit")
                        .contains("ref_1"));
    }

    private PageSnapshot snapshot(String tree) {
        return PageSnapshot.fromA11yText(tree, new Viewport(1280, 800));
    }
}
