package vip.mate.browser.edge.action;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

@JsonTypeInfo(use = JsonTypeInfo.Id.NONE)
public record DetectRegionPayload(
        @JsonProperty("regionKey") String regionKey,
        String strategy
) implements ActionPayload {

    public DetectRegionPayload {
        if (regionKey == null || regionKey.isBlank()) {
            throw new IllegalArgumentException("regionKey is required");
        }
        if (strategy == null || strategy.isBlank()) {
            strategy = "auto";
        }
    }
}
