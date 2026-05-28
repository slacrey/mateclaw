package vip.mate.browser.orchestrator.domain;

import vip.mate.browser.edge.action.TabRef;

/**
 * A high-level intent the orchestrator has not yet expanded into atomic
 * {@code ActionRequest}s. Sealed so the {@code ActionPlanner} compiler-checks
 * exhaustiveness.
 *
 * <p>Every step carries the {@link TabRef} it targets and a
 * {@link GroundingResult} (the dispatcher's output for the step's hint).
 * The planner pattern-matches on the result variant and either expands into
 * the atomic action sequence or throws {@code GroundingAmbiguousException} /
 * {@code GroundingMissException}.
 *
 * <p>Phase 2 ships {@link ClickStep} and {@link TypeStep}. Phase 3 SOP work
 * will add Scroll, Hover, Wait-for-element, and the catch-all SemanticStep.
 */
public sealed interface Step permits Step.ClickStep, Step.TypeStep {

    TabRef tabRef();
    GroundingResult grounding();

    /** Click the centre of the grounded target. */
    record ClickStep(TabRef tabRef, GroundingResult grounding) implements Step {
        public ClickStep {
            if (tabRef == null) throw new IllegalArgumentException("tabRef required");
            if (grounding == null) throw new IllegalArgumentException("grounding required");
        }
    }

    /** Click to focus, then type the given text. */
    record TypeStep(TabRef tabRef, GroundingResult grounding, String text) implements Step {
        public TypeStep {
            if (tabRef == null) throw new IllegalArgumentException("tabRef required");
            if (grounding == null) throw new IllegalArgumentException("grounding required");
            if (text == null) throw new IllegalArgumentException("text required");
        }
    }
}
