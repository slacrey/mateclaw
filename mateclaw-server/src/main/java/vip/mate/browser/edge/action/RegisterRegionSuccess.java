package vip.mate.browser.edge.action;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

@JsonTypeInfo(use = JsonTypeInfo.Id.NONE)
public record RegisterRegionSuccess(
        @JsonProperty("regionKey") String regionKey,
        RegisterRegionPayload.Rect rect
) implements ActionSuccessPayload {
}
