package vip.mate.browser.orchestrator.domain;

/**
 * Tab/page lifecycle events the Extension reports unsolicited via the Edge
 * bridge (wire kinds {@code event.page.navigated} and {@code event.tab.closed}).
 * The {@code PageSnapshotService} subscribes and updates its freshness
 * cache accordingly.
 */
public enum PageEvent {

    /** Top-frame navigation completed (URL changed). Snapshot becomes STALE. */
    NAVIGATED,

    /** The tab was closed. The entry is removed from the snapshot cache. */
    TAB_CLOSED
}
