package vip.mate.browser.edge.action;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

import java.util.Set;

@JsonTypeInfo(use = JsonTypeInfo.Id.NONE)
public record ScrollPayload(
        String direction,
        @JsonProperty("distance_px") int distancePx,
        Integer segments
) implements ActionPayload {

    private static final Set<String> DIRECTION_VALUES = Set.of("down", "up", "left", "right");

    public ScrollPayload {
        if (direction == null) {
            throw new IllegalArgumentException("direction is required");
        }
        if (!DIRECTION_VALUES.contains(direction)) {
            throw new IllegalArgumentException("direction must be one of " + DIRECTION_VALUES);
        }
        if (segments == null) {
            segments = 5;
        }
    }
}
