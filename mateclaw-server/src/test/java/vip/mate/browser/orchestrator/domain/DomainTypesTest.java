package vip.mate.browser.orchestrator.domain;

import org.junit.jupiter.api.Test;
import vip.mate.browser.edge.action.TabRef;

import java.util.List;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Coverage for the shared orchestrator-domain types (Wave 4-0).
 *
 * <p>One file rather than 12 because each record is small and the focus is
 * on behavioural correctness (centre math, line parsing, sealed-type
 * validation) rather than per-record getters. The downstream F1-F5 tasks
 * exercise these types through their own unit tests; this file ensures
 * the shared contract is correct before they fork.
 */
class DomainTypesTest {

    // -----------------------------------------------------------------
    // BBox
    // -----------------------------------------------------------------

    @Test
    void bbox_centreReturnsArithmeticMid() {
        var bbox = new BBox(100, 200, 80, 40);
        assertThat(bbox.center()).isEqualTo(new BBox.Point(140, 220));
    }

    @Test
    void bbox_zeroSizeAllowed_centreEqualsOrigin() {
        var bbox = new BBox(50, 60, 0, 0);
        assertThat(bbox.center()).isEqualTo(new BBox.Point(50, 60));
    }

    @Test
    void bbox_negativeOriginAllowed_offscreenElement() {
        // Off-screen scrolled element — coords can be negative.
        var bbox = new BBox(-100, -50, 200, 100);
        assertThat(bbox.center()).isEqualTo(new BBox.Point(0, 0));
    }

    @Test
    void bbox_negativeDimensions_throw() {
        assertThatThrownBy(() -> new BBox(0, 0, -1, 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new BBox(0, 0, 0, -1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // -----------------------------------------------------------------
    // Viewport
    // -----------------------------------------------------------------

    @Test
    void viewport_zeroOrNegative_throws() {
        assertThatThrownBy(() -> new Viewport(0, 600)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Viewport(800, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Viewport(-1, 1)).isInstanceOf(IllegalArgumentException.class);
    }

    // -----------------------------------------------------------------
    // GroundedTarget
    // -----------------------------------------------------------------

    @Test
    void groundedTarget_nullBbox_throws() {
        assertThatThrownBy(() -> new GroundedTarget(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void groundedTarget_convenienceCtor_setsRefIdNull() {
        var t = new GroundedTarget(new BBox(0, 0, 10, 10));
        assertThat(t.refId()).isNull();
    }

    // -----------------------------------------------------------------
    // GroundingResult sealed hierarchy (Codex P1-9)
    // -----------------------------------------------------------------

    @Test
    void groundingResult_ambiguous_requiresAtLeastTwoCandidates() {
        var t1 = new GroundedTarget(new BBox(0, 0, 10, 10));
        assertThatThrownBy(() -> new GroundingResult.Ambiguous(List.of(t1), "trivial"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(">=2");
    }

    @Test
    void groundingResult_ambiguous_candidatesAreImmutable() {
        var t1 = new GroundedTarget(new BBox(0, 0, 10, 10));
        var t2 = new GroundedTarget(new BBox(20, 20, 10, 10));
        var mutable = new java.util.ArrayList<>(List.of(t1, t2));
        var amb = new GroundingResult.Ambiguous(mutable, "two");
        mutable.clear();   // outside mutation must not affect the record
        assertThat(amb.candidates()).hasSize(2);
    }

    @Test
    void groundingResult_hit_nullTarget_throws() {
        assertThatThrownBy(() -> new GroundingResult.Hit(null, "evidence"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void groundingResult_miss_nullReason_defaultsSensibly() {
        assertThat(new GroundingResult.Miss(null).reason()).isEqualTo("no match");
    }

    // -----------------------------------------------------------------
    // GroundingHint sealed hierarchy
    // -----------------------------------------------------------------

    @Test
    void groundingHint_a11yMatch_convenienceCtor_defaultsFilterToInteractive() {
        var h = new GroundingHint.A11yMatch("button", Pattern.compile("Submit"));
        assertThat(h.filter()).isEqualTo("interactive");
    }

    @Test
    void groundingHint_byRefId_emptyRefId_throws() {
        assertThatThrownBy(() -> new GroundingHint.ByRefId(""))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // -----------------------------------------------------------------
    // PageSnapshot
    // -----------------------------------------------------------------

    @Test
    void pageSnapshot_fromA11yText_parsesCanonicalLineFormat() {
        var snap = PageSnapshot.fromA11yText(
                "Button[ref=ref_1, frame=0]: Submit @{100,200 80x32}\n"
                        + "Link[ref=ref_2, frame=3]: Read more @{200,400 120x18}\n",
                new Viewport(1280, 800));

        var lines = snap.lines();
        assertThat(lines).hasSize(2);
        assertThat(lines.get(0).role()).isEqualTo("Button");
        assertThat(lines.get(0).refId()).isEqualTo("ref_1");
        assertThat(lines.get(0).name()).isEqualTo("Submit");
        assertThat(lines.get(0).bbox()).isEqualTo(new BBox(100, 200, 80, 32));
        assertThat(lines.get(0).frameId()).isZero();
        assertThat(lines.get(1).role()).isEqualTo("Link");
        assertThat(lines.get(1).bbox()).isEqualTo(new BBox(200, 400, 120, 18));
        assertThat(lines.get(1).frameId()).isEqualTo(3);
    }

    @Test
    void pageSnapshot_fromA11yText_parsesFrameTaggedRowsAndDefaultsLegacyRowsToTopFrame() {
        var snap = PageSnapshot.fromA11yText(
                "Button[ref=ref_1, frame=1]: Login @{210,310 60x24}\n"
                        + "Button[ref=ref_2]: Submit @{100,200 80x32}\n",
                new Viewport(1280, 800));

        var lines = snap.lines();
        assertThat(lines).hasSize(2);
        assertThat(lines.get(0).frameId()).isEqualTo(1);
        assertThat(lines.get(0).bbox()).isEqualTo(new BBox(210, 310, 60, 24));
        assertThat(lines.get(1).frameId()).isZero();
    }

    @Test
    void pageSnapshot_lines_skipsBlanksAndMalformed() {
        var snap = PageSnapshot.fromA11yText(
                "\n"
                        + "Button[ref=ref_1]: Submit @{100,200 80x32}\n"
                        + "this line is garbage\n"
                        + "\n"
                        + "Heading[ref=ref_2]: Welcome @{0,0 700x40}\n",
                new Viewport(1280, 800));

        assertThat(snap.lines()).hasSize(2);
        assertThat(snap.lines().get(0).role()).isEqualTo("Button");
        assertThat(snap.lines().get(1).role()).isEqualTo("Heading");
    }

    @Test
    void pageSnapshot_lines_handlesNameWithSpaces() {
        var snap = PageSnapshot.fromA11yText(
                "Link[ref=ref_2]: Read more documentation @{200,400 120x18}",
                new Viewport(1280, 800));
        assertThat(snap.lines().get(0).name()).isEqualTo("Read more documentation");
    }

    @Test
    void pageSnapshot_lines_emptyTree_returnsEmptyList() {
        var snap = PageSnapshot.fromA11yText("", new Viewport(1280, 800));
        assertThat(snap.lines()).isEmpty();
    }

    @Test
    void pageSnapshot_fromA11yText_idIsStableForEqualInputs() {
        var tree = "Button[ref=ref_1]: Submit @{100,200 80x32}";
        var a = PageSnapshot.fromA11yText(tree, new Viewport(1280, 800));
        var b = PageSnapshot.fromA11yText(tree, new Viewport(1280, 800));
        // Same input → same snapshotId (handy for assertion + caching).
        assertThat(a.snapshotId()).isEqualTo(b.snapshotId());
    }

    @Test
    void pageSnapshot_blankSnapshotId_throws() {
        assertThatThrownBy(() -> new PageSnapshot("", 1L, 42L, "", new Viewport(800, 600), "", ""))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void pageSnapshot_nonPositiveTimestamp_throws() {
        assertThatThrownBy(() -> new PageSnapshot("x", 0L, 42L, "", new Viewport(800, 600), "", ""))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void pageSnapshot_nullUrlTitle_normalisedToEmpty() {
        // Wire payloads from older extensions (or future shape drift) may omit
        // url/title; the canonical ctor must default them to "" so callers
        // never see null, and Jackson serialisation stays stable.
        var snap = new PageSnapshot("x", 1L, 42L, "", new Viewport(800, 600), null, null);
        assertThat(snap.url()).isEqualTo("");
        assertThat(snap.title()).isEqualTo("");
    }

    // -----------------------------------------------------------------
    // Step sealed hierarchy
    // -----------------------------------------------------------------

    @Test
    void step_clickStep_requiresAllFields() {
        var ok = new Step.ClickStep(
                new TabRef.Main(),
                new GroundingResult.Hit(new GroundedTarget(new BBox(0, 0, 10, 10)), "test"));
        assertThat(ok.tabRef()).isEqualTo(new TabRef.Main());

        assertThatThrownBy(() -> new Step.ClickStep(null, ok.grounding()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Step.ClickStep(new TabRef.Main(), null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void step_typeStep_nullText_throws() {
        assertThatThrownBy(() -> new Step.TypeStep(
                new TabRef.Main(),
                new GroundingResult.Hit(new GroundedTarget(new BBox(0, 0, 10, 10)), "x"),
                null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // -----------------------------------------------------------------
    // GroundingAmbiguousException / GroundingMissException
    // -----------------------------------------------------------------

    @Test
    void groundingAmbiguousException_carriesAmbiguousAndFormatsCount() {
        var amb = new GroundingResult.Ambiguous(
                List.of(
                        new GroundedTarget(new BBox(0, 0, 10, 10)),
                        new GroundedTarget(new BBox(20, 20, 10, 10))),
                "two Submits");
        var ex = new GroundingAmbiguousException(amb);
        assertThat(ex.ambiguous()).isSameAs(amb);
        assertThat(ex.getMessage()).contains("2 candidates").contains("two Submits");
    }

    @Test
    void groundingMissException_carriesMiss() {
        var miss = new GroundingResult.Miss("none of the engines found Submit");
        var ex = new GroundingMissException(miss);
        assertThat(ex.miss()).isSameAs(miss);
        assertThat(ex.getMessage()).contains("none of the engines");
    }
}
