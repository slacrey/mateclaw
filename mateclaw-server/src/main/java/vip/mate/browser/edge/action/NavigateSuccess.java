package vip.mate.browser.edge.action;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

@JsonTypeInfo(use = JsonTypeInfo.Id.NONE)
public record NavigateSuccess(
        @JsonProperty("final_url") String finalUrl,
        @JsonProperty("http_status") Integer httpStatus,
        @JsonProperty("load_state") String loadState
) implements ActionSuccessPayload {

    public NavigateSuccess {
        if (finalUrl == null) {
            throw new IllegalArgumentException("finalUrl is required");
        }
    }
}
