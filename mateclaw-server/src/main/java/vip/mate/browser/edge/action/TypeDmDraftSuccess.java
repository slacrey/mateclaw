package vip.mate.browser.edge.action;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

@JsonTypeInfo(use = JsonTypeInfo.Id.NONE)
public record TypeDmDraftSuccess(
        @JsonProperty("draftTyped") boolean draftTyped,
        String text,
        String target,
        @JsonProperty("sent") boolean sent,
        @JsonProperty("sendTarget") String sendTarget
) implements ActionSuccessPayload {
}
