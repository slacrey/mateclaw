package vip.mate.browser.edge.action;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

@JsonTypeInfo(use = JsonTypeInfo.Id.NONE)
public record WaitSuccess(
        @JsonProperty("waited_ms") long waitedMs
) implements ActionSuccessPayload {

    public WaitSuccess {
        if (waitedMs < 0) {
            throw new IllegalArgumentException("waitedMs must be >= 0");
        }
    }
}
