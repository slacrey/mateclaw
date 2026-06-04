package vip.mate.browser.edge.action;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

@JsonTypeInfo(use = JsonTypeInfo.Id.NONE)
public record ScrollRegionSuccess(
        @JsonProperty("regionKey") String regionKey,
        @JsonProperty("stopWhen") ScrollRegionPayload.StopWhen stopWhen
) implements ActionSuccessPayload {
}
