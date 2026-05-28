package vip.mate.browser.orchestrator.snapshot;

import reactor.core.publisher.Mono;
import vip.mate.browser.edge.action.TabRef;
import vip.mate.browser.edge.session.BrowserSession;
import vip.mate.browser.orchestrator.domain.PageSnapshot;

/**
 * Port (interface only) for issuing an {@code a11y.snapshot.request} envelope
 * on a session's Edge WebSocket and awaiting the matching
 * {@code a11y.snapshot.response} (see {@code docs/specs/edge-protocol.md}
 * §"a11y.snapshot.request" / §"a11y.snapshot.response").
 *
 * <p><strong>Phase 2 ships only this interface.</strong> A concrete
 * implementation that hooks into {@code EdgeWebSocketHandler} (the same
 * transport {@code ActionExecutionService} uses) is a follow-up commit. The
 * port lets {@code F2 GroundingDispatcher} and {@code F5 PageSnapshotService}
 * be unit-tested with a Mockito stub instead of standing up a real WS.
 *
 * <p>Implementations <strong>must</strong>:
 * <ul>
 *   <li>Time out within a reasonable deadline (~10&nbsp;s by spec convention).
 *       On timeout, the returned {@link Mono} completes with an error signal —
 *       it must NOT block the caller's thread indefinitely.</li>
 *   <li>Echo the resolved tab id back inside
 *       {@link PageSnapshot#resolvedTabId()} so the caller can key its
 *       freshness cache by absolute tab id (the {@code tab_ref} field in the
 *       wire response is the resolved tab id, not the request-side TabRef).</li>
 * </ul>
 */
public interface SnapshotEdgeClient {

    /**
     * Issue {@code a11y.snapshot.request} on {@code session}'s WS, await the
     * matching {@code a11y.snapshot.response}, return the parsed snapshot.
     *
     * @param session the active edge session (for WS transport)
     * @param tabRef  the wire-form tab selector ({@code "main"} / {@code "active"} /
     *                explicit integer); the SW resolves it before responding
     * @param filter  {@code "interactive" | "all" | "default"} — passed through to
     *                the content script's a11y serializer
     * @return cold {@link Mono} of the resolved snapshot. Subscribe once per fetch.
     */
    Mono<PageSnapshot> request(BrowserSession session, TabRef tabRef, String filter);
}
