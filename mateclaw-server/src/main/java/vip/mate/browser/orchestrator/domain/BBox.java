package vip.mate.browser.orchestrator.domain;

/**
 * Bounding rectangle in viewport coordinates (top-left + size).
 *
 * <p>Integer pixels. Negative coords are legal (off-screen elements can have
 * negative x/y after scroll); the orchestrator filters those at the planner
 * layer, not here.
 */
public record BBox(int x, int y, int w, int h) {

    public BBox {
        if (w < 0 || h < 0) {
            throw new IllegalArgumentException(
                    "BBox dimensions must be non-negative: w=" + w + " h=" + h);
        }
    }

    /**
     * Geometric centre. Used by {@code ActionPlanner} to convert a
     * grounded target rectangle into a click coordinate.
     */
    public Point center() {
        return new Point(x + w / 2, y + h / 2);
    }

    public record Point(int x, int y) {}
}
