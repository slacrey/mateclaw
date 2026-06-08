package vip.mate.browser.edge.action;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

@JsonTypeInfo(use = JsonTypeInfo.Id.NONE)
public record DetectRegionSuccess(
        @JsonProperty("regionKey") String regionKey,
        RegisterRegionPayload.Rect rect,
        @JsonProperty("safePoint") SafePoint safePoint,
        String source
) implements ActionSuccessPayload {

    public DetectRegionSuccess {
        if (regionKey == null || regionKey.isBlank()) {
            throw new IllegalArgumentException("regionKey is required");
        }
        if (rect == null) {
            throw new IllegalArgumentException("rect is required");
        }
        if (source == null || source.isBlank()) {
            source = "dom";
        }
    }

    public record SafePoint(double x, double y) {
    }
}
