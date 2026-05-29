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

class A11yEngineTest {

    private final A11yEngine engine = new A11yEngine();

    @Test
    void nullNearLabel_returnsMiss() {
        var result = engine.ground(snapshot("""
                Article[ref=ref_1]: Comments @{0,0 600x400}
                  Button[ref=ref_2]: Like @{100,200 80x32}
                """), new GroundingHint.A11yMatch("button", Pattern.compile("Like")));

        assertThat(result).isInstanceOfSatisfying(GroundingResult.Miss.class,
                miss -> assertThat(miss.reason()).contains("nearLabel"));
    }

    @Test
    void singleCandidateMatchingNearLabelAncestor_returnsHit() {
        var result = engine.ground(snapshot("""
                Article[ref=ref_1]: Comments @{0,0 600x400}
                  Button[ref=ref_2]: Like @{100,200 80x32}
                Article[ref=ref_3]: Posts @{0,500 600x400}
                  Button[ref=ref_4]: Share @{100,700 80x32}
                """), hint("Like", "Comments"));

        assertThat(result).isInstanceOfSatisfying(GroundingResult.Hit.class, hit -> {
            assertThat(hit.target()).isEqualTo(new GroundedTarget(new BBox(100, 200, 80, 32), "ref_2"));
            assertThat(hit.evidence()).contains("narrowed by ancestor").contains("Comments");
        });
    }

    @Test
    void multipleCandidatesAllUnderSameAncestor_returnsAmbiguous() {
        var result = engine.ground(snapshot("""
                Article[ref=ref_1]: Comments @{0,0 600x400}
                  Button[ref=ref_2]: Like @{100,200 80x32}
                  Region[ref=ref_3]: Replies @{200,100 300x200}
                    Button[ref=ref_4]: Like @{300,200 80x32}
                """), hint("Like", "Comments"));

        assertThat(result).isInstanceOfSatisfying(GroundingResult.Ambiguous.class, ambiguous -> {
            assertThat(ambiguous.candidates()).containsExactly(
                    new GroundedTarget(new BBox(100, 200, 80, 32), "ref_2"),
                    new GroundedTarget(new BBox(300, 200, 80, 32), "ref_4"));
            assertThat(ambiguous.evidence()).contains("2 candidates").contains("Comments");
        });
    }

    @Test
    void nearLabelMatchesCaseInsensitive() {
        var result = engine.ground(snapshot("""
                Article[ref=ref_1]: Comments @{0,0 600x400}
                  Button[ref=ref_2]: Like @{100,200 80x32}
                """), hint("Like", "comments"));

        assertThat(result).isInstanceOfSatisfying(GroundingResult.Hit.class,
                hit -> assertThat(hit.target().refId()).isEqualTo("ref_2"));
    }

    @Test
    void nearestAncestorWins_p0Invariant() {
        var result = engine.ground(snapshot("""
                Main[ref=ref_1] @{0,0 900x900}
                  Article[ref=ref_2] @{10,10 800x300}
                    Heading[ref=ref_3]: Posts @{20,20 300x40}
                    Button[ref=ref_4]: Like @{100,200 80x32}
                  Article[ref=ref_5] @{10,400 800x300}
                    Heading[ref=ref_6]: Comments @{20,420 300x40}
                    Button[ref=ref_7]: Like @{100,600 80x32}
                """), hint("Like", "Comments"));

        assertThat(result).isInstanceOfSatisfying(GroundingResult.Hit.class,
                hit -> assertThat(hit.target())
                        .isEqualTo(new GroundedTarget(new BBox(100, 600, 80, 32), "ref_7")));
    }

    @Test
    void nearLabelOnlyMatchesHeadinglikeRoles() {
        var result = engine.ground(snapshot("""
                Button[ref=ref_1]: Submit @{0,0 80x32}
                  Button[ref=ref_2]: Like @{100,200 80x32}
                """), hint("Like", "Submit"));

        assertThat(result).isInstanceOfSatisfying(GroundingResult.Miss.class,
                miss -> assertThat(miss.reason()).contains("nearLabel"));
    }

    @Test
    void byRefIdHint_returnsMiss() {
        var result = engine.ground(snapshot("""
                Button[ref=ref_1]: Submit @{100,200 80x32}
                """), new GroundingHint.ByRefId("ref_1"));

        assertThat(result).isEqualTo(new GroundingResult.Miss(
                "a11y engine does not handle ByRefId hints (DOM engine owns those)"));
    }

    @Test
    void evidenceFieldOnHit_describesNearLabelMatch() {
        var result = engine.ground(snapshot("""
                Article[ref=ref_1]: Comments @{0,0 600x400}
                  Button[ref=ref_2]: Like @{100,200 80x32}
                """), hint("Like", "Comments"));

        assertThat(result).isInstanceOfSatisfying(GroundingResult.Hit.class,
                hit -> assertThat(hit.evidence())
                        .contains("narrowed by ancestor")
                        .contains("Comments")
                        .contains("ref_2"));
    }

    @Test
    void roleAndNameStillFilterCandidateSetBeforeNearLabel() {
        var result = engine.ground(snapshot("""
                Article[ref=ref_1]: Comments @{0,0 600x400}
                  Link[ref=ref_2]: Like @{100,200 80x32}
                  Button[ref=ref_3]: Reply @{200,200 80x32}
                  Button[ref=ref_4]: Like @{300,200 80x32}
                """), hint("Like", "Comments"));

        assertThat(result).isInstanceOfSatisfying(GroundingResult.Hit.class,
                hit -> assertThat(hit.target().refId()).isEqualTo("ref_4"));
    }

    private GroundingHint.A11yMatch hint(String namePattern, String nearLabel) {
        return new GroundingHint.A11yMatch(
                "button",
                Pattern.compile(namePattern),
                "interactive",
                nearLabel);
    }

    private PageSnapshot snapshot(String tree) {
        return PageSnapshot.fromA11yText(tree, new Viewport(1280, 800));
    }
}
