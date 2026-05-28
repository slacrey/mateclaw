package vip.mate.browser.orchestrator.domain;

/**
 * Thrown by the {@code ActionPlanner} when all grounding engines miss
 * — no element on the current snapshot matches the hint at all.
 * Callers should either refresh the snapshot or escalate.
 */
public class GroundingMissException extends RuntimeException {

    private final GroundingResult.Miss miss;

    public GroundingMissException(GroundingResult.Miss miss) {
        super("grounding missed: " + miss.reason());
        this.miss = miss;
    }

    public GroundingResult.Miss miss() {
        return miss;
    }
}
