package vip.mate.browser.orchestrator.domain;

/**
 * One of the three grounding strategies (DOM, A11y, Vision). The
 * {@code GroundingDispatcher} chains them in priority order:
 * <ol>
 *     <li>Cheap, deterministic, high-precision attempts first (DOM)</li>
 *     <li>Mid-cost structural fallback (A11y tree pattern match)</li>
 *     <li>Expensive LLM-grounded vision attempt last</li>
 * </ol>
 *
 * <p>Each engine is given the current {@link PageSnapshot} and a
 * {@link GroundingHint}; it returns one of the three {@link GroundingResult}
 * variants. Engines are stateless — all state lives in the snapshot.
 */
public interface GroundingEngine {

    /** Stable identifier for logs / metrics. */
    String name();

    /** Try to ground the hint against the snapshot. Must not return null. */
    GroundingResult ground(PageSnapshot snapshot, GroundingHint hint);
}
