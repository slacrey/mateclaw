package vip.mate.browser.orchestrator.domain;

/** Viewport dimensions (width, height) in pixels. */
public record Viewport(int w, int h) {

    public Viewport {
        if (w <= 0 || h <= 0) {
            throw new IllegalArgumentException(
                    "Viewport dimensions must be positive: w=" + w + " h=" + h);
        }
    }
}
