package vip.mate.browser.edge.action;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

import java.util.Set;

@JsonTypeInfo(use = JsonTypeInfo.Id.NONE)
public record NavigatePayload(
        String url,
        String referer,
        @JsonProperty("wait_for") String waitFor
) implements ActionPayload {

    private static final Set<String> WAIT_FOR_VALUES = Set.of("load", "domcontentloaded", "network_idle", "none");

    public NavigatePayload {
        if (url == null) {
            throw new IllegalArgumentException("url is required");
        }
        if (waitFor == null) {
            waitFor = "load";
        }
        if (!WAIT_FOR_VALUES.contains(waitFor)) {
            throw new IllegalArgumentException("waitFor must be one of " + WAIT_FOR_VALUES);
        }
    }
}
