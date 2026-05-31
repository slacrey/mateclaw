package vip.mate.browser.orchestrator.engine;

import org.springframework.stereotype.Component;
import vip.mate.browser.edge.action.TabRef;
import vip.mate.browser.edge.session.BrowserSession;
import vip.mate.browser.orchestrator.domain.BBox;
import vip.mate.browser.orchestrator.domain.GroundedTarget;
import vip.mate.browser.orchestrator.domain.GroundingEngine;
import vip.mate.browser.orchestrator.domain.GroundingHint;
import vip.mate.browser.orchestrator.domain.GroundingResult;
import vip.mate.browser.orchestrator.domain.PageSnapshot;
import vip.mate.browser.orchestrator.domain.Viewport;

import java.util.Comparator;
import java.util.List;

@Component
public class DomEngine implements GroundingEngine {

    @Override
    public String name() {
        return "dom";
    }

    /**
     * DOM-based grounding ignores {@code session} and {@code tabRef}: the
     * input snapshot is the only state it consults. The two params are part
     * of the {@link GroundingEngine} contract so {@code VisionEngine} can
     * issue auxiliary edge calls without a separate dispatch path.
     */
    @Override
    public GroundingResult ground(BrowserSession session,
                                  TabRef tabRef,
                                  PageSnapshot snapshot,
                                  GroundingHint hint) {
        return switch (hint) {
            case GroundingHint.A11yMatch m -> groundByA11yMatch(snapshot, m);
            case GroundingHint.ByRefId r -> groundByRefId(snapshot, r);
        };
    }

    private GroundingResult groundByA11yMatch(PageSnapshot snapshot, GroundingHint.A11yMatch hint) {
        var matches = snapshot.lines().stream()
                .filter(line -> line.role().equalsIgnoreCase(hint.role()))
                .filter(line -> hint.namePattern().matcher(line.name()).matches())
                .toList();

        if (matches.isEmpty()) {
            return new GroundingResult.Miss(
                    "no role+name match for role=" + hint.role()
                            + " name=/" + hint.namePattern().pattern() + "/");
        }

        if (matches.size() == 1) {
            var line = matches.getFirst();
            return new GroundingResult.Hit(
                    new GroundedTarget(line.bbox(), line.refId()),
                    "role+name match: role=" + line.role()
                            + " name=\"" + line.name() + "\" ref=" + line.refId());
        }

        // More than one element shares this role+name.
        boolean hasNearLabel = hint.nearLabel() != null && !hint.nearLabel().isBlank();
        if (hasNearLabel) {
            // The caller asked to disambiguate by enclosing section. Defer to the
            // A11y engine (next in the cascade) by reporting Ambiguous — it honors
            // nearLabel; this engine does not. If A11y also can't narrow, the
            // dispatcher surfaces this Ambiguous to the tool.
            var candidates = matches.stream()
                    .map(line -> new GroundedTarget(line.bbox(), line.refId()))
                    .toList();
            return new GroundingResult.Ambiguous(
                    candidates,
                    matches.size() + " elements with role=" + hint.role()
                            + " matching /" + hint.namePattern().pattern() + "/");
        }

        // No disambiguator supplied and every candidate carries the SAME
        // role+name — an ambiguity the agent can't break by refining its text.
        // Rather than dead-end the run with GROUNDING_AMBIGUOUS, pick the single
        // most-plausible target deterministically: the visible one nearest the
        // viewport top-left. This mirrors how locator libraries resolve a
        // legitimately repeated role+name (Playwright's .first()); clicking any
        // same-named control is the user's intent. The choice is logged in the
        // evidence string for debuggability.
        var primary = pickPrimary(matches, snapshot.viewport());
        return new GroundingResult.Hit(
                new GroundedTarget(primary.bbox(), primary.refId()),
                "auto-resolved " + matches.size() + " same-name candidates (role="
                        + hint.role() + " name=/" + hint.namePattern().pattern()
                        + "/) → ref=" + primary.refId() + " [visible, top-left]");
    }

    /**
     * Deterministic primary among same-name candidates: prefer one whose
     * top-left corner is inside the viewport, then the topmost, then the
     * leftmost. Total order, so the same snapshot always resolves identically.
     */
    private PageSnapshot.Line pickPrimary(List<PageSnapshot.Line> matches, Viewport viewport) {
        return matches.stream()
                .min(Comparator
                        .comparingInt((PageSnapshot.Line l) -> inViewport(l.bbox(), viewport) ? 0 : 1)
                        .thenComparingInt(l -> l.bbox().y())
                        .thenComparingInt(l -> l.bbox().x()))
                .orElseGet(matches::getFirst);
    }

    /** True when the element's top-left corner sits within the viewport box. */
    private boolean inViewport(BBox b, Viewport viewport) {
        return b.x() >= 0 && b.x() < viewport.w()
                && b.y() >= 0 && b.y() < viewport.h();
    }

    private GroundingResult groundByRefId(PageSnapshot snapshot, GroundingHint.ByRefId hint) {
        return snapshot.lines().stream()
                .filter(line -> line.refId().equals(hint.refId()))
                .findFirst()
                .<GroundingResult>map(line -> new GroundingResult.Hit(
                        new GroundedTarget(line.bbox(), line.refId()),
                        "ref " + hint.refId()))
                .orElseGet(() -> new GroundingResult.Miss("ref " + hint.refId() + " not in snapshot"));
    }
}
