package vip.mate.browser.edge.action;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

import java.util.Set;

@JsonTypeInfo(use = JsonTypeInfo.Id.NONE)
public record WaitPayload(
        String strategy,
        @JsonProperty("duration_ms") Long durationMs,
        @JsonProperty("idle_threshold_ms") Long idleThresholdMs,
        @JsonProperty("load_state") String loadState
) implements ActionPayload {

    private static final Set<String> STRATEGY_VALUES = Set.of("time", "network_idle", "load_state");
    private static final Set<String> LOAD_STATE_VALUES = Set.of("load", "domcontentloaded", "network_idle");

    public WaitPayload {
        if (strategy == null) {
            throw new IllegalArgumentException("strategy is required");
        }
        if (!STRATEGY_VALUES.contains(strategy)) {
            throw new IllegalArgumentException("strategy must be one of " + STRATEGY_VALUES);
        }
        if (durationMs != null && durationMs < 0) {
            throw new IllegalArgumentException("durationMs must be >= 0");
        }
        if (idleThresholdMs != null && idleThresholdMs < 0) {
            throw new IllegalArgumentException("idleThresholdMs must be >= 0");
        }
        if (loadState != null && !LOAD_STATE_VALUES.contains(loadState)) {
            throw new IllegalArgumentException("loadState must be one of " + LOAD_STATE_VALUES);
        }
    }
}
