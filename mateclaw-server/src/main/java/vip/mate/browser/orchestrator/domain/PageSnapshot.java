package vip.mate.browser.orchestrator.domain;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * One accessibility-tree snapshot of a page at a moment in time.
 *
 * <p>Mirrors the wire shape of an {@code a11y.snapshot.response} envelope
 * (see {@code docs/specs/edge-protocol.md} §"a11y.snapshot.response"):
 * <pre>
 * {
 *   "snapshot_id": "snap-uuid",
 *   "captured_at_ms": 1730000000123,
 *   "tab_ref": 42,
 *   "tree": "Button[ref=ref_1, frame=0]: Submit @{100,200 80x32}\n...",
 *   "viewport": { "w": 1280, "h": 800 }
 * }
 * </pre>
 *
 * <p>The {@code tree} is a plain-text serialization the {@link GroundingEngine}
 * implementations parse into {@link Line}s. We expose the parse via
 * {@link #lines()} so each engine doesn't repeat the work.
 */
public record PageSnapshot(
        String snapshotId,
        long capturedAtMs,
        long resolvedTabId,
        String tree,
        Viewport viewport
) {

    public PageSnapshot {
        if (snapshotId == null || snapshotId.isBlank())
            throw new IllegalArgumentException("snapshotId is required");
        if (capturedAtMs <= 0)
            throw new IllegalArgumentException("capturedAtMs must be positive");
        if (tree == null)
            throw new IllegalArgumentException("tree is required (empty string OK)");
        if (viewport == null)
            throw new IllegalArgumentException("viewport is required");
    }

    /**
     * Parse {@link #tree} into one {@link Line} per non-blank text row.
     *
     * <p>Recognized text format (matches the C1 content script's output):
     * <pre>
     *   Button[ref=ref_1, frame=0]: Submit @{100,200 80x32}
     *   Link[ref=ref_2, frame=1]: Read more — href=/docs @{200,400 120x18}
     *   Heading[ref=ref_3, frame=0]: Welcome @{50,100 700x40}
     * </pre>
     *
     * <p>Lines that don't match are skipped (forward-compat for new
     * formatting variants the content script may emit). Empty trees
     * return an empty list.
     */
    public List<Line> lines() {
        var out = new ArrayList<Line>();
        for (String raw : tree.split("\\R")) {
            String line = raw.trim();
            if (line.isEmpty()) continue;
            Matcher m = LINE_PATTERN.matcher(line);
            if (m.matches()) {
                String role = m.group(1).trim();
                String refId = m.group(2);
                String frameId = m.group(3);
                String name = m.group(4);
                int x = Integer.parseInt(m.group(5));
                int y = Integer.parseInt(m.group(6));
                int w = Integer.parseInt(m.group(7));
                int h = Integer.parseInt(m.group(8));
                out.add(new Line(role, refId, name == null ? "" : name.trim(),
                        new BBox(x, y, w, h), frameId == null ? 0 : Integer.parseInt(frameId)));
            }
        }
        return out;
    }

    /**
     * Build a snapshot from a literal a11y-tree text (used heavily in tests).
     * Generates a stable test snapshotId; capturedAtMs defaults to 1.
     */
    public static PageSnapshot fromA11yText(String tree, Viewport viewport) {
        return new PageSnapshot(
                "test-snap-" + Integer.toHexString(tree.hashCode()),
                1L,
                -1L,
                tree,
                viewport);
    }

    /** One parsed accessibility-tree row. */
    public record Line(String role, String refId, String name, BBox bbox, int frameId) {}

    /**
     * Matches the canonical line format. Tolerant on whitespace; the
     * accessible name (capture 4) is greedy up to the {@code @{} marker.
     * <p>Format anatomy (all groups required except name):
     * <pre>
     *   Role  [ref=ref_N, frame=N] : optional accessible name  @{x,y wxh}
     *   ^^^^  ^^^^^^^^^^^^^^^^^^^   ^^^^^^^^^^^^^^^^^^^^^^^^^   ^^^^^^^^^
     *    1         2        3                    4              5,6,7,8
     * </pre>
     */
    private static final Pattern LINE_PATTERN = Pattern.compile(
            "^([A-Za-z][\\w-]*)\\s*\\[ref=([\\w-]+)(?:\\s*,\\s*frame=(\\d+))?\\]\\s*(?::\\s*(.+?))?\\s*"
                    + "@\\{(\\d+),(\\d+)\\s+(\\d+)x(\\d+)\\}\\s*$");
}
