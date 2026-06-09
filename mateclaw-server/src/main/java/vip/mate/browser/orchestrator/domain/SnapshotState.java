package vip.mate.browser.orchestrator.domain;

/**
 * Lifecycle state for a cached {@link PageSnapshot}. The
 * {@code PageSnapshotService} (task F5) owns the transitions; consumers read
 * only.
 *
 * <p>Transition rules (canonical — copied from docs/specs/edge-protocol.md
 * §"A11y snapshot lifecycle"):
 *
 * <table border="1">
 *   <tr><th>Trigger</th><th>New state</th></tr>
 *   <tr><td>Fresh a11y.snapshot.response arrives</td><td>FRESH</td></tr>
 *   <tr><td>action.result for NAVIGATE succeeds</td><td>STALE</td></tr>
 *   <tr><td>action.result for CLICK/TYPE/SCROLL succeeds</td><td>SUSPECT (one retry budget)</td></tr>
 *   <tr><td>Snapshot age &gt; 30 s</td><td>STALE (regardless of last action)</td></tr>
 *   <tr><td>event.page.navigated arrives</td><td>STALE</td></tr>
 *   <tr><td>event.tab.closed arrives</td><td>(entry removed from cache)</td></tr>
 * </table>
 */
public enum SnapshotState {

    /** Just-arrived. Usable as-is. */
    FRESH,

    /**
     * The page might have changed (a click/type/scroll happened), but the
     * cached snapshot is still usable. If the next ground attempt MISSES,
     * the orchestrator invalidates → STALE → forces a refetch.
     */
    SUSPECT,

    /** Definitely changed. Next {@code request()} forces a refetch. */
    STALE
}
