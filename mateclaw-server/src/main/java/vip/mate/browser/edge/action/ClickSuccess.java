package vip.mate.browser.edge.action;

import com.fasterxml.jackson.annotation.JsonTypeInfo;

@JsonTypeInfo(use = JsonTypeInfo.Id.NONE)
public record ClickSuccess() implements ActionSuccessPayload {
}
