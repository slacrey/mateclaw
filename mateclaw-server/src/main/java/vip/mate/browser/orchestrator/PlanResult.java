package vip.mate.browser.orchestrator;

import vip.mate.browser.edge.action.ActionResult;

import java.util.List;

public sealed interface PlanResult permits PlanResult.Success, PlanResult.Partial {

    /** Every step succeeded. {@code completed} is in execution order. */
    record Success(List<ActionResult.Success> completed) implements PlanResult {
        public Success {
            if (completed == null) {
                throw new IllegalArgumentException("completed required");
            }
            completed = List.copyOf(completed);
        }
    }

    /**
     * Plan stopped on the first Failure.
     * {@code completed} is the successful prefix before the failure.
     */
    record Partial(List<ActionResult.Success> completed, ActionResult.Failure failed) implements PlanResult {
        public Partial {
            if (completed == null) {
                throw new IllegalArgumentException("completed required");
            }
            if (failed == null) {
                throw new IllegalArgumentException("failed required");
            }
            completed = List.copyOf(completed);
        }
    }

    static PlanResult success(List<ActionResult.Success> successes) {
        return new Success(successes);
    }

    static PlanResult partial(List<ActionResult.Success> successes, ActionResult.Failure failure) {
        return new Partial(successes, failure);
    }
}
