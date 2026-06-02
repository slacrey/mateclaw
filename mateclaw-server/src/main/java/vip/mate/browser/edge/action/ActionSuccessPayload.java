package vip.mate.browser.edge.action;

import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.annotation.JsonSubTypes;

/**
 * Sealed polymorphic root for action.result success payloads.
 *
 * <p>Uses {@code NAME} dispatch on a {@code "kind"} property because
 * {@link ClickSuccess} and {@link ScrollSuccess} are both empty records
 * and cannot be distinguished by DEDUCTION. Concrete subtypes carry
 * {@code @JsonTypeInfo(use=NONE)} so direct {@code readValue(json, Concrete.class)}
 * still works without the discriminator.
 *
 * <p>Wire names mirror {@link ActionKind} lowercase: {@code navigate,
 * click, type, press_key, scroll, move_mouse, wait}.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "kind")
@JsonSubTypes({
        @JsonSubTypes.Type(value = NavigateSuccess.class,  name = "navigate"),
        @JsonSubTypes.Type(value = ClickSuccess.class,     name = "click"),
        @JsonSubTypes.Type(value = TypeSuccess.class,      name = "type"),
        @JsonSubTypes.Type(value = PressKeySuccess.class,  name = "press_key"),
        @JsonSubTypes.Type(value = ScrollSuccess.class,    name = "scroll"),
        @JsonSubTypes.Type(value = MoveMouseSuccess.class, name = "move_mouse"),
        @JsonSubTypes.Type(value = WaitSuccess.class,      name = "wait")
})
public sealed interface ActionSuccessPayload
        permits NavigateSuccess, ClickSuccess, TypeSuccess,
                PressKeySuccess, ScrollSuccess, MoveMouseSuccess, WaitSuccess {
}
