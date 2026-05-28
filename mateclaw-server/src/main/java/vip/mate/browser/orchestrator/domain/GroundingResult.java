package vip.mate.browser.orchestrator.domain;

import java.util.List;

/**
 * Outcome of a single grounding attempt (one engine on one snapshot).
 *
 * <p><b>Why sealed (Codex P1-9):</b> the old API returned {@code Optional<GroundedTarget>}
 * which conflated "no match" with "ambiguous". A click executed against the
 * wrong one of two candidates is silently wrong. The {@code Ambiguous} variant
 * forces the planner to escalate (or the dispatcher to try the next engine)
 * instead of guessing.
 */
public sealed interface GroundingResult
        permits GroundingResult.Hit, GroundingResult.Ambiguous, GroundingResult.Miss {

    /** Exactly one match. {@code evidence} is a short human-readable trace
     *  for logs ("data-e2e=submit", "ref_42", etc.). */
    record Hit(GroundedTarget target, String evidence) implements GroundingResult {
        public Hit {
            if (target == null) throw new IllegalArgumentException("target required");
            if (evidence == null) evidence = "";
        }
    }

    /** Two or more matches. Dispatcher tries the next engine; if all engines
     *  agree on the same candidates, the planner throws GroundingAmbiguousException. */
    record Ambiguous(List<GroundedTarget> candidates, String evidence) implements GroundingResult {
        public Ambiguous {
            if (candidates == null || candidates.size() < 2) {
                throw new IllegalArgumentException(
                        "Ambiguous requires >=2 candidates; got "
                                + (candidates == null ? "null" : candidates.size()));
            }
            if (evidence == null) evidence = "";
            candidates = List.copyOf(candidates);
        }
    }

    /** No match at all. Dispatcher tries the next engine. */
    record Miss(String reason) implements GroundingResult {
        public Miss {
            if (reason == null) reason = "no match";
        }
    }
}
