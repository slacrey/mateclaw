package vip.mate.browser.edge.action;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

@JsonTypeInfo(use = JsonTypeInfo.Id.NONE)
public record RegisterRegionPayload(
        @JsonProperty("regionKey") String regionKey,
        Rect rect,
        String source
) implements ActionPayload {

    public RegisterRegionPayload {
        if (regionKey == null || regionKey.isBlank()) {
            throw new IllegalArgumentException("regionKey is required");
        }
        if (rect == null) {
            throw new IllegalArgumentException("rect is required");
        }
        if (source == null || source.isBlank()) {
            source = "server";
        }
    }

    public record Rect(double x, double y, double width, double height) {
        public Rect {
            if (!Double.isFinite(x) || !Double.isFinite(y)) {
                throw new IllegalArgumentException("rect x/y must be finite");
            }
            if (!Double.isFinite(width) || !Double.isFinite(height) || width <= 0 || height <= 0) {
                throw new IllegalArgumentException("rect width/height must be positive finite numbers");
            }
        }
    }
}
