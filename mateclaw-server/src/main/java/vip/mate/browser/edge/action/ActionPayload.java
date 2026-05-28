package vip.mate.browser.edge.action;

import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.annotation.JsonSubTypes;

/**
 * Sealed polymorphic root for atomic browser-action parameter payloads.
 *
 * <p>Jackson polymorphism uses {@code NAME} dispatch on a {@code "kind"}
 * property — DEDUCTION is unusable here because {@link ClickPayload} and
 * {@link MoveMousePayload} share the {@code (x, y)} prefix, and the empty
 * success records are even more indistinguishable. Concrete subtypes
 * carry {@code @JsonTypeInfo(use=NONE)} so direct {@code readValue(json, Concrete.class)}
 * still works without requiring the discriminator.
 *
 * <p>Wire names mirror {@link ActionKind} lowercase: {@code navigate,
 * click, type, scroll, move_mouse, wait}.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "kind")
@JsonSubTypes({
        @JsonSubTypes.Type(value = NavigatePayload.class,  name = "navigate"),
        @JsonSubTypes.Type(value = ClickPayload.class,     name = "click"),
        @JsonSubTypes.Type(value = TypePayload.class,      name = "type"),
        @JsonSubTypes.Type(value = ScrollPayload.class,    name = "scroll"),
        @JsonSubTypes.Type(value = MoveMousePayload.class, name = "move_mouse"),
        @JsonSubTypes.Type(value = WaitPayload.class,      name = "wait")
})
public sealed interface ActionPayload
        permits NavigatePayload, ClickPayload, TypePayload,
                ScrollPayload, MoveMousePayload, WaitPayload {
}
