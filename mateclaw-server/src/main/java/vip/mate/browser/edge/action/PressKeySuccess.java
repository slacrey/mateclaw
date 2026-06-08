package vip.mate.browser.edge.action;

import com.fasterxml.jackson.annotation.JsonTypeInfo;

@JsonTypeInfo(use = JsonTypeInfo.Id.NONE)
public record PressKeySuccess(
        String key
) implements ActionSuccessPayload {

    public PressKeySuccess {
        key = key == null ? "" : key;
    }
}
