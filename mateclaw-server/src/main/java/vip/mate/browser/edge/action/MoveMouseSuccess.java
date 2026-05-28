package vip.mate.browser.edge.action;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

@JsonTypeInfo(use = JsonTypeInfo.Id.NONE)
public record MoveMouseSuccess(
        @JsonProperty("arrived_at_ms") long arrivedAtMs,
        int waypoints
) implements ActionSuccessPayload {

    public MoveMouseSuccess {
        if (arrivedAtMs < 0) {
            throw new IllegalArgumentException("arrivedAtMs must be >= 0");
        }
        if (waypoints < 0) {
            throw new IllegalArgumentException("waypoints must be >= 0");
        }
    }
}
