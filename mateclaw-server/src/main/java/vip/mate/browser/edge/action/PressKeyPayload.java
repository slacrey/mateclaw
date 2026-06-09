package vip.mate.browser.edge.action;

import com.fasterxml.jackson.annotation.JsonTypeInfo;

@JsonTypeInfo(use = JsonTypeInfo.Id.NONE)
public record PressKeyPayload(
        String key
) implements ActionPayload {

    public PressKeyPayload {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("key is required");
        }
    }
}
