package vip.mate.browser.orchestrator.screenshot;

import vip.mate.browser.orchestrator.domain.Viewport;

/**
 * One screenshot of a browser tab at a moment in time.
 *
 * <p>Mirrors the wire shape of a {@code screenshot.capture.response} envelope
 * (see {@code docs/specs/edge-protocol.md} §"screenshot.capture.response"):
 * <pre>
 * {
 *   "snapshot_id": "shot-uuid",
 *   "captured_at_ms": 1730000000123,
 *   "tab_ref": 42,
 *   "format": "png",
 *   "data_base64": "iVBORw0KGgo...",
 *   "viewport": { "w": 1280, "h": 800 },
 *   "actual_dimensions": { "w": 1280, "h": 800 }
 * }
 * </pre>
 *
 * <p><strong>P0-3 byte-safety invariant:</strong> {@code pngBytes} is the
 * already-base64-decoded PNG payload, NOT the wire-form string. Decoding
 * happens once, at delivery, inside {@link DefaultScreenshotEdgeClient};
 * downstream consumers (vision engine, debugging dumps) consume raw bytes.
 *
 * @param snapshotId        opaque server-issued id (used for logging / cache)
 * @param capturedAtMs      epoch millis the SW captured the frame
 * @param resolvedTabId     absolute Chrome tab id the SW resolved
 * @param pngBytes          raw PNG bytes (already base64-decoded)
 * @param viewport          CSS-pixel viewport at capture time
 * @param actualDimensions  post {@code scale_factor} pixel dimensions of the
 *                          PNG itself (may differ from {@code viewport} when
 *                          a HiDPI {@code scale_factor > 1} is requested)
 */
public record PageScreenshot(
        String snapshotId,
        long capturedAtMs,
        long resolvedTabId,
        byte[] pngBytes,
        Viewport viewport,
        Viewport actualDimensions) {

    public PageScreenshot {
        if (snapshotId == null || snapshotId.isBlank()) {
            throw new IllegalArgumentException("snapshotId is required");
        }
        if (capturedAtMs <= 0) {
            throw new IllegalArgumentException("capturedAtMs must be positive");
        }
        if (pngBytes == null) {
            throw new IllegalArgumentException("pngBytes is required");
        }
        if (viewport == null) {
            throw new IllegalArgumentException("viewport is required");
        }
        if (actualDimensions == null) {
            throw new IllegalArgumentException("actualDimensions is required");
        }
    }
}
