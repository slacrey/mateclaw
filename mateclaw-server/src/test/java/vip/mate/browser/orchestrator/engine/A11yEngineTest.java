package vip.mate.browser.orchestrator.engine;

import org.junit.jupiter.api.Test;
import vip.mate.browser.edge.action.TabRef;
import vip.mate.browser.edge.session.BrowserSession;
import vip.mate.browser.orchestrator.domain.BBox;
import vip.mate.browser.orchestrator.domain.GroundedTarget;
import vip.mate.browser.orchestrator.domain.GroundingHint;
import vip.mate.browser.orchestrator.domain.GroundingResult;
import vip.mate.browser.orchestrator.domain.PageSnapshot;
import vip.mate.browser.orchestrator.domain.Viewport;

import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

class A11yEngineTest {

    // A11yEngine ignores session/tabRef (they're part of the contract for
    // VisionEngine's auxiliary edge calls); pass sentinels.
    private static final BrowserSession SESSION = null;
    private static final TabRef TAB_REF = new TabRef.Main();

    private final A11yEngine engine = new A11yEngine();

    @Test
    void nullNearLabel_returnsMiss() {
        var result = engine.ground(SESSION, TAB_REF, snapshot("""
                Article[ref=ref_1]: Comments @{0,0 600x400}
                  Button[ref=ref_2]: Like @{100,200 80x32}
                """), new GroundingHint.A11yMatch("button", Pattern.compile("Like")));

        assertThat(result).isInstanceOfSatisfying(GroundingResult.Miss.class,
                miss -> assertThat(miss.reason()).contains("nearLabel"));
    }

    @Test
    void singleCandidateMatchingNearLabelAncestor_returnsHit() {
        var result = engine.ground(SESSION, TAB_REF, snapshot("""
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
        var result = engine.ground(SESSION, TAB_REF, snapshot("""
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
        var result = engine.ground(SESSION, TAB_REF, snapshot("""
                Article[ref=ref_1]: Comments @{0,0 600x400}
                  Button[ref=ref_2]: Like @{100,200 80x32}
                """), hint("Like", "comments"));

        assertThat(result).isInstanceOfSatisfying(GroundingResult.Hit.class,
                hit -> assertThat(hit.target().refId()).isEqualTo("ref_2"));
    }

    @Test
    void nearestAncestorWins_p0Invariant() {
        var result = engine.ground(SESSION, TAB_REF, snapshot("""
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
        var result = engine.ground(SESSION, TAB_REF, snapshot("""
                Button[ref=ref_1]: Submit @{0,0 80x32}
                  Button[ref=ref_2]: Like @{100,200 80x32}
                """), hint("Like", "Submit"));

        assertThat(result).isInstanceOfSatisfying(GroundingResult.Miss.class,
                miss -> assertThat(miss.reason()).contains("nearLabel"));
    }

    @Test
    void byRefIdHint_returnsMiss() {
        var result = engine.ground(SESSION, TAB_REF, snapshot("""
                Button[ref=ref_1]: Submit @{100,200 80x32}
                """), new GroundingHint.ByRefId("ref_1"));

        assertThat(result).isEqualTo(new GroundingResult.Miss(
                "a11y engine does not handle ByRefId hints (DOM engine owns those)"));
    }

    @Test
    void evidenceFieldOnHit_describesNearLabelMatch() {
        var result = engine.ground(SESSION, TAB_REF, snapshot("""
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
        var result = engine.ground(SESSION, TAB_REF, snapshot("""
                Article[ref=ref_1]: Comments @{0,0 600x400}
                  Link[ref=ref_2]: Like @{100,200 80x32}
                  Button[ref=ref_3]: Reply @{200,200 80x32}
                  Button[ref=ref_4]: Like @{300,200 80x32}
                """), hint("Like", "Comments"));

        assertThat(result).isInstanceOfSatisfying(GroundingResult.Hit.class,
                hit -> assertThat(hit.target().refId()).isEqualTo("ref_4"));
    }

    // ------------------------------------------------------------------
    // Phase 3.1 loosening: SPA search boxes whose accessible name is a
    // synthesized placeholder/hint rather than the exact label the LLM
    // guessed. Covers role-flex (textbox≈searchbox≈combobox), substring +
    // case/Unicode-insensitive matching, and bidirectional containment.
    // ------------------------------------------------------------------

    /** (a) role-flex + substring: textbox hint + name~="搜索" grounds a Searchbox. */
    @Test
    void textboxHint_groundsSearchboxByNameSubstring() {
        var result = engine.ground(SESSION, TAB_REF, snapshot("""
                Search[ref=ref_1]: 搜索栏 @{600,0 500x80}
                  Searchbox[ref=ref_2]: 搜索视频 @{632,16 380x40}
                """), textEntryHint("textbox", "搜索", "搜索栏"));

        assertThat(result).isInstanceOfSatisfying(GroundingResult.Hit.class,
                hit -> assertThat(hit.target())
                        .isEqualTo(new GroundedTarget(new BBox(632, 16, 380, 40), "ref_2")));
    }

    /** (b) case-insensitive: lowercase hint "search" grounds "Search input". */
    @Test
    void lowercaseHint_groundsTextboxCaseInsensitive() {
        var result = engine.ground(SESSION, TAB_REF, snapshot("""
                Search[ref=ref_1]: Site search @{600,0 500x80}
                  Textbox[ref=ref_2]: Search input @{632,16 380x40}
                """), textEntryHint("searchbox", "search", "Site search"));

        assertThat(result).isInstanceOfSatisfying(GroundingResult.Hit.class,
                hit -> assertThat(hit.target().refId()).isEqualTo("ref_2"));
    }

    /**
     * (c) bidirectional containment: an over-specified hint whose literal text
     * contains the (shorter) candidate name still grounds it. Exercised for an
     * English candidate and, in a sibling assertion, a Chinese one — both via
     * the candidate-name-substring-of-hint direction.
     */
    @Test
    void overSpecifiedHint_groundsViaBidirectionalContainment() {
        var english = engine.ground(SESSION, TAB_REF, snapshot("""
                Search[ref=ref_1]: Search bar @{600,0 500x80}
                  Searchbox[ref=ref_2]: search @{632,16 380x40}
                """), textEntryHint("textbox", "the search videos box", "Search bar"));

        assertThat(english).isInstanceOfSatisfying(GroundingResult.Hit.class, hit -> {
            assertThat(hit.target().refId()).isEqualTo("ref_2");
            assertThat(hit.evidence()).contains("name contained in hint");
        });

        var chinese = engine.ground(SESSION, TAB_REF, snapshot("""
                Search[ref=ref_1]: 搜索栏 @{600,0 500x80}
                  Searchbox[ref=ref_2]: 搜索 @{632,16 380x40}
                """), textEntryHint("textbox", "搜索视频框", "搜索栏"));

        assertThat(chinese).isInstanceOfSatisfying(GroundingResult.Hit.class,
                hit -> assertThat(hit.target().refId()).isEqualTo("ref_2"));
    }

    /**
     * (d) regression: loosening must not create false positives. Two distinct
     * buttons "Save"/"Cancel"; hint "Save" still grounds exactly Save. (Cancel
     * is neither a forward .find() of "Save" nor a substring of the literal
     * "Save", so it never enters the candidate set.)
     */
    @Test
    void distinctButtons_hintStillGroundsExactlyOne_noFalsePositive() {
        var result = engine.ground(SESSION, TAB_REF, snapshot("""
                Form[ref=ref_1]: Settings @{0,0 600x200}
                  Button[ref=ref_2]: Save @{100,100 80x32}
                  Button[ref=ref_3]: Cancel @{200,100 80x32}
                """), hint("Save", "Settings"));

        assertThat(result).isInstanceOfSatisfying(GroundingResult.Hit.class, hit -> {
            assertThat(hit.target().refId()).isEqualTo("ref_2");
            // Direct hit, not reverse containment.
            assertThat(hit.evidence()).doesNotContain("name contained in hint");
        });
    }

    /**
     * (e) ambiguity is preserved under loosening: two searchboxes both matching
     * "搜索" under the same ancestor stay Ambiguous rather than collapsing to a
     * wrong Hit.
     */
    @Test
    void twoMatchingSearchboxes_returnsAmbiguous_notWrongHit() {
        var result = engine.ground(SESSION, TAB_REF, snapshot("""
                Search[ref=ref_1]: 搜索栏 @{0,0 1000x120}
                  Searchbox[ref=ref_2]: 搜索视频 @{100,16 380x40}
                  Searchbox[ref=ref_3]: 搜索用户 @{600,16 380x40}
                """), textEntryHint("textbox", "搜索", "搜索栏"));

        assertThat(result).isInstanceOfSatisfying(GroundingResult.Ambiguous.class, ambiguous -> {
            assertThat(ambiguous.candidates()).containsExactly(
                    new GroundedTarget(new BBox(100, 16, 380, 40), "ref_2"),
                    new GroundedTarget(new BBox(600, 16, 380, 40), "ref_3"));
            assertThat(ambiguous.evidence()).contains("2 candidates");
        });
    }

    private GroundingHint.A11yMatch hint(String namePattern, String nearLabel) {
        return new GroundingHint.A11yMatch(
                "button",
                Pattern.compile(namePattern),
                "interactive",
                nearLabel);
    }

    /**
     * Mirrors how {@code ExtensionBrowserTool} builds the hint: the name is
     * Pattern.quote-d and CASE_INSENSITIVE. Role is caller-chosen so tests can
     * deliberately mismatch within the text-entry equivalence set.
     */
    private GroundingHint.A11yMatch textEntryHint(String role, String nameText, String nearLabel) {
        return new GroundingHint.A11yMatch(
                role,
                Pattern.compile(Pattern.quote(nameText), Pattern.CASE_INSENSITIVE),
                "interactive",
                nearLabel);
    }

    private PageSnapshot snapshot(String tree) {
        return PageSnapshot.fromA11yText(tree, new Viewport(1280, 800));
    }
}
