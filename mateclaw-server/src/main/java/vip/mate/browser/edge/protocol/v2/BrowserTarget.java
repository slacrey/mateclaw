package vip.mate.browser.edge.protocol.v2;

import com.fasterxml.jackson.annotation.JsonProperty;

public record BrowserTarget(
        @JsonProperty("tabId") String tabId,
        String role
) {
    public BrowserTarget {
        if (tabId == null || tabId.isBlank()) {
            throw new IllegalArgumentException("tabId is required");
        }
        if (role == null || role.isBlank()) {
            role = "controlled";
        }
    }
}
