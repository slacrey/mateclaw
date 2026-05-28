package vip.mate.browser.edge.action;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

@JsonTypeInfo(use = JsonTypeInfo.Id.NONE)
public record TypePayload(
        String text,
        @JsonProperty("focus_target") FocusTarget focusTarget
) implements ActionPayload {

    public TypePayload {
        if (text == null) {
            throw new IllegalArgumentException("text is required");
        }
    }

    public record FocusTarget(double x, double y) {
    }
}
