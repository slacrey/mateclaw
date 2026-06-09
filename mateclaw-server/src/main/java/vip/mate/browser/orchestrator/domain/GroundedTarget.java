package vip.mate.browser.orchestrator.domain;

/**
 * Concrete element on the page identified by its bounding box. The
 * {@code refId} is optional — present when the target came from a ref-tagged
 * A11y snapshot, absent for pure DOM/Vision grounding.
 */
public record GroundedTarget(BBox bbox, String refId) {

    public GroundedTarget {
        if (bbox == null) {
            throw new IllegalArgumentException("bbox is required");
        }
        // refId may be null
    }

    /** Convenience constructor for grounding paths that don't carry a refId. */
    public GroundedTarget(BBox bbox) {
        this(bbox, null);
    }
}
