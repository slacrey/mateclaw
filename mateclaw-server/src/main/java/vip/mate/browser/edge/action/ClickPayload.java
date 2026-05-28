package vip.mate.browser.edge.action;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

import java.util.Set;

@JsonTypeInfo(use = JsonTypeInfo.Id.NONE)
public record ClickPayload(
        double x,
        double y,
        String button,
        @JsonProperty("click_count") int clickCount
) implements ActionPayload {

    private static final Set<String> BUTTON_VALUES = Set.of("left", "right", "middle");

    @JsonCreator
    public ClickPayload(
            @JsonProperty("x") double x,
            @JsonProperty("y") double y,
            @JsonProperty("button") String button,
            @JsonProperty("click_count") Integer clickCount
    ) {
        this(x, y, button == null ? "left" : button, clickCount == null || clickCount < 1 ? 1 : clickCount);
    }

    public ClickPayload {
        if (button == null) {
            button = "left";
        }
        if (!BUTTON_VALUES.contains(button)) {
            throw new IllegalArgumentException("button must be one of " + BUTTON_VALUES);
        }
        if (clickCount < 1) {
            clickCount = 1;
        }
    }
}
