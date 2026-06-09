package vip.mate.browser.orchestrator.domain;

/**
 * Thrown by the {@code ActionPlanner} when the grounding dispatcher reports
 * {@link GroundingResult.Ambiguous} — i.e. multiple candidates and no engine
 * could narrow them. Callers should escalate to a clarification dialog or
 * abort the workflow rather than guess.
 *
 * <p>Codex P1-9 fix: the planner used to swallow ambiguity and click the
 * first candidate. This exception forces the caller to decide.
 */
public class GroundingAmbiguousException extends RuntimeException {

    private final GroundingResult.Ambiguous ambiguous;

    public GroundingAmbiguousException(GroundingResult.Ambiguous ambiguous) {
        super(formatMessage(ambiguous));
        this.ambiguous = ambiguous;
    }

    public GroundingResult.Ambiguous ambiguous() {
        return ambiguous;
    }

    private static String formatMessage(GroundingResult.Ambiguous a) {
        return "grounding ambiguous: " + a.candidates().size() + " candidates ("
                + a.evidence() + ")";
    }
}
