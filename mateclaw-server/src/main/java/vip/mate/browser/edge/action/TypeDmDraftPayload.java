package vip.mate.browser.edge.action;

import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonTypeInfo(use = JsonTypeInfo.Id.NONE)
public record TypeDmDraftPayload(
        String text,
        @JsonProperty("send") boolean send
) implements ActionPayload {

    public TypeDmDraftPayload(String text) {
        this(text, false);
    }

    public TypeDmDraftPayload {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("text is required");
        }
    }
}
