package vip.mate.browser.edge.action;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

@JsonTypeInfo(use = JsonTypeInfo.Id.NONE)
public record TypeSuccess(
        @JsonProperty("chars_typed") int charsTyped
) implements ActionSuccessPayload {

    public TypeSuccess {
        if (charsTyped < 0) {
            throw new IllegalArgumentException("charsTyped must be >= 0");
        }
    }
}
