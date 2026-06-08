package vip.mate.browser.edge.action;

import com.fasterxml.jackson.annotation.JsonTypeInfo;

@JsonTypeInfo(use = JsonTypeInfo.Id.NONE)
public record TypeDmDraftPayload(
        String text
) implements ActionPayload {

    public TypeDmDraftPayload {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("text is required");
        }
    }
}
