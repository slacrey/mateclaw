package vip.mate.browser.orchestrator.screenshot;

import reactor.core.publisher.Mono;
import vip.mate.browser.edge.action.TabRef;
import vip.mate.browser.edge.session.BrowserSession;

import java.util.Map;

/**
 * Port (interface only) for issuing a {@code screenshot.capture.request}
 * envelope on a session's Edge WebSocket and awaiting the matching
 * {@code screenshot.capture.response} (see {@code docs/specs/edge-protocol.md}
 * §"screenshot.capture.request" / §"screenshot.capture.response", protocol v1.2).
 *
 * <p>Mirrors the {@code SnapshotEdgeClient} shape from Wave 2.1-A so the
 * {@link DefaultScreenshotEdgeClient} concrete impl can lift the same
 * pending-future pattern. The port lets {@code VisionEngine} (Wave 3-A2)
 * be unit-tested with a Mockito stub instead of standing up a real WS.
 *
 * <p>Implementations <strong>must</strong>:
 * <ul>
 *   <li>Time out within a reasonable deadline (~10&nbsp;s by spec convention).
 *       On timeout, the returned {@link Mono} completes with an error signal —
 *       it must NOT block the caller's thread indefinitely.</li>
 *   <li>Base64-decode the {@code data_base64} payload exactly once at delivery
 *       and store the result as {@code byte[]} on {@link PageScreenshot}
 *       (the <strong>P0-3 byte-safety</strong> invariant).</li>
 *   <li>Echo the resolved tab id back inside
 *       {@link PageScreenshot#resolvedTabId()}.</li>
 * </ul>
 */
public interface ScreenshotEdgeClient {

    /**
     * Issue {@code screenshot.capture.request} on {@code session}'s WS, await
     * the matching {@code screenshot.capture.response}, return the parsed
     * page screenshot with PNG bytes already decoded.
     *
     * @param session     the active edge session (for WS transport)
     * @param tabRef      the wire-form tab selector ({@code "main"} / {@code "active"} /
     *                    explicit integer); the SW resolves it before responding
     * @param scaleFactor device pixel ratio (1 for normal, 2 for HiDPI). The
     *                    extension caps this internally; values &le; 0 are
     *                    coerced to {@code 1}.
     * @return cold {@link Mono} of the captured screenshot. Subscribe once per fetch.
     */
    Mono<PageScreenshot> request(BrowserSession session, TabRef tabRef, int scaleFactor);

    /**
     * Called by {@code EdgeWebSocketHandler} when a
     * {@code screenshot.capture.response} envelope arrives. Implementations
     * resolve the pending request whose {@code msg_id} matches {@code inReplyTo}.
     *
     * <p>Default no-op so test doubles (e.g. {@code @MockBean ScreenshotEdgeClient})
     * and the fail-fast fallback do not need to know about the server-side
     * delivery hook.
     */
    default void deliverScreenshot(String inReplyTo, Map<String, Object> payload) {
        // no-op
    }

    /**
     * Called by {@code EdgeWebSocketHandler.afterConnectionClosed}. Implementations
     * fail any pending request bound to the closed session.
     *
     * <p>Default no-op for the same reason as {@link #deliverScreenshot}.
     */
    default void sessionClosed(String sessionId) {
        // no-op
    }
}
