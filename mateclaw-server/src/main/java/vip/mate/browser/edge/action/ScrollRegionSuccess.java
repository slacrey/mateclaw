package vip.mate.browser.edge.action;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

@JsonTypeInfo(use = JsonTypeInfo.Id.NONE)
public record ScrollRegionSuccess(
        @JsonProperty("regionKey") String regionKey,
        @JsonProperty("stopWhen") ScrollRegionPayload.StopWhen stopWhen,
        String mode,
        Boolean moved,
        String reason,
        Boolean forwardProgress,
        Integer visibleItemCount,
        Integer newVisibleItemCount,
        Integer retainedVisibleItemCount,
        String beforeFirstItemSignature,
        String beforeLastItemSignature,
        String afterFirstItemSignature,
        String afterLastItemSignature,
        String beforeWindowSignature,
        String afterWindowSignature,
        @JsonProperty("scrollTopBefore") Double scrollTopBefore,
        @JsonProperty("scrollTopAfter") Double scrollTopAfter,
        @JsonProperty("scrollLeftBefore") Double scrollLeftBefore,
        @JsonProperty("scrollLeftAfter") Double scrollLeftAfter,
        @JsonProperty("scrollHeight") Double scrollHeight,
        @JsonProperty("clientHeight") Double clientHeight,
        String containerTag,
        String containerClass
) implements ActionSuccessPayload {
}
