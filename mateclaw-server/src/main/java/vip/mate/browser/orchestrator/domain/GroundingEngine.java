package vip.mate.browser.orchestrator.domain;

import vip.mate.browser.edge.action.TabRef;
import vip.mate.browser.edge.session.BrowserSession;

/**
 * One of the three grounding strategies (DOM, A11y, Vision). The
 * {@code GroundingDispatcher} chains them in priority order:
 * <ol>
 *     <li>Cheap, deterministic, high-precision attempts first (DOM)</li>
 *     <li>Mid-cost structural fallback (A11y tree pattern match)</li>
 *     <li>Expensive LLM-grounded vision attempt last</li>
 * </ol>
 *
 * <p>Each engine is given the current {@link PageSnapshot}, the originating
 * {@link BrowserSession} + {@link TabRef}, and a {@link GroundingHint}; it
 * returns one of the three {@link GroundingResult} variants. Engines are
 * stateless — all per-page state lives in the snapshot.
 *
 * <p><strong>Why the session + tabRef are part of the signature (Wave 3-A2):</strong>
 * the {@link vip.mate.browser.orchestrator.engine.VisionEngine} needs to
 * issue a {@code screenshot.capture.request} via
 * {@link vip.mate.browser.orchestrator.screenshot.ScreenshotEdgeClient}, and
 * that call needs the live edge session for transport plus the tab selector.
 * DOM and A11y engines ignore the two params today (their input is the
 * already-fetched snapshot) but the uniform contract keeps the dispatcher
 * loop free of {@code instanceof} branches and lets new engines (e.g. a
 * future "structured-elements re-fetch" engine) issue their own edge calls
 * symmetrically.
 *
 * <p>The alternative — storing session/tab in a {@code ThreadLocal} set by
 * the dispatcher — was rejected: it leaks state across threads under reactive
 * boundaries and hides the dependency from anyone reading the signature.
 */
public interface GroundingEngine {

    /** Stable identifier for logs / metrics. */
    String name();

    /**
     * Try to ground the hint against the snapshot. Must not return null.
     *
     * @param session the edge session for any auxiliary edge-call needs
     *                (vision: screenshot capture). Engines that don't need
     *                a wire call should not touch it.
     * @param tabRef  the tab selector originally driving this grounding pass;
     *                vision uses it as the {@code tab_ref} on
     *                {@code screenshot.capture.request}.
     * @param snapshot the already-fetched a11y snapshot to ground against.
     * @param hint    structured description of what to find.
     */
    GroundingResult ground(BrowserSession session,
                           TabRef tabRef,
                           PageSnapshot snapshot,
                           GroundingHint hint);
}
