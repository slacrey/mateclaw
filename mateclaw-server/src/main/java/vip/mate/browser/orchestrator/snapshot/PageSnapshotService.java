package vip.mate.browser.orchestrator.snapshot;

import reactor.core.publisher.Mono;
import vip.mate.browser.edge.action.ActionKind;
import vip.mate.browser.edge.action.TabRef;
import vip.mate.browser.edge.session.BrowserSession;
import vip.mate.browser.orchestrator.domain.PageEvent;
import vip.mate.browser.orchestrator.domain.PageSnapshot;
import vip.mate.browser.orchestrator.domain.SnapshotState;

/**
 * Control-Plane companion to {@code B11 SnapshotRequestHandler}: owns the
 * per-{@code (sessionId, resolvedTabId)} a11y snapshot cache, decides
 * {@link SnapshotState#FRESH FRESH} / {@link SnapshotState#SUSPECT SUSPECT} /
 * {@link SnapshotState#STALE STALE}, and re-fetches via
 * {@link SnapshotEdgeClient} when needed.
 *
 * <p>The canonical lifecycle is defined in
 * {@code docs/specs/edge-protocol.md} §"A11y snapshot lifecycle":
 *
 * <table border="1">
 *   <tr><th>Trigger</th><th>New state</th></tr>
 *   <tr><td>Fresh {@code a11y.snapshot.response} arrives</td><td>FRESH</td></tr>
 *   <tr><td>{@link ActionKind#NAVIGATE} succeeds</td><td>STALE</td></tr>
 *   <tr><td>{@link ActionKind#CLICK}/{@link ActionKind#TYPE}/{@link ActionKind#SCROLL} succeeds</td><td>SUSPECT (one retry budget)</td></tr>
 *   <tr><td>Snapshot age &gt; 30 s</td><td>STALE</td></tr>
 *   <tr><td>{@link PageEvent#NAVIGATED} arrives</td><td>STALE</td></tr>
 *   <tr><td>{@link PageEvent#TAB_CLOSED} arrives</td><td>(entry removed)</td></tr>
 * </table>
 *
 * <p>Closes the Codex Phase 2 P0-3 finding (snapshot refresh).
 */
public interface PageSnapshotService {

    /**
     * Return a usable {@link PageSnapshot} for {@code (session, tabRef)}.
     *
     * <ul>
     *   <li>Cached + {@code FRESH} (and within 30 s TTL) → cache hit.</li>
     *   <li>Cached + {@code SUSPECT} (and within 30 s TTL) → cache hit
     *       (one retry budget; the orchestrator calls {@link #invalidate}
     *       after a ground miss to force the next refetch).</li>
     *   <li>Cached + {@code STALE} or age &gt; 30 s → refetch via
     *       {@link SnapshotEdgeClient#request}.</li>
     *   <li>Absent → fetch.</li>
     * </ul>
     *
     * <p>The returned {@link Mono} caches its terminal signal: subscribing
     * twice yields the same snapshot reference without a second wire call.
     *
     * @param session the active edge session
     * @param tabRef  wire-form selector; resolved by the Extension
     * @param filter  one of {@code "interactive" | "all" | "default"}
     */
    Mono<PageSnapshot> request(BrowserSession session, TabRef tabRef, String filter);

    /**
     * Called by the orchestrator (or an {@code ActionResult} listener) on a
     * successful {@code action.result}. Transitions the cached entry for
     * {@code (sessionId, resolvedTabId)} per the lifecycle table:
     * <ul>
     *   <li>{@link ActionKind#NAVIGATE} → {@link SnapshotState#STALE}</li>
     *   <li>{@link ActionKind#CLICK}/{@link ActionKind#TYPE}/{@link ActionKind#SCROLL}
     *       → {@link SnapshotState#SUSPECT}</li>
     *   <li>{@link ActionKind#MOVE_MOUSE}/{@link ActionKind#WAIT}
     *       → no transition (page DOM not mutated)</li>
     * </ul>
     * <p>Missing entries are silently ignored.
     */
    void onActionSuccess(String sessionId, long resolvedTabId, ActionKind kind);

    /**
     * Called when the Extension reports an unsolicited page lifecycle event.
     * <ul>
     *   <li>{@link PageEvent#NAVIGATED} → cached entry becomes
     *       {@link SnapshotState#STALE}</li>
     *   <li>{@link PageEvent#TAB_CLOSED} → cached entry is removed</li>
     * </ul>
     * <p>Missing entries are silently ignored.
     */
    void onPageEvent(String sessionId, long resolvedTabId, PageEvent event);

    /**
     * Force the next {@link #request} for {@code (sessionId, resolvedTabId)}
     * to refetch. Used by {@code F2 GroundingDispatcher} after a SUSPECT
     * snapshot leads to a ground miss (the one-retry budget is exhausted).
     * <p>Missing entries are silently ignored.
     */
    void invalidate(String sessionId, long resolvedTabId);
}
