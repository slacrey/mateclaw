package vip.mate.browser.edge.action;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

import java.util.Set;

@JsonTypeInfo(use = JsonTypeInfo.Id.NONE)
public record ScrollRegionPayload(
        @JsonProperty("regionKey") String regionKey,
        String direction,
        double amount,
        @JsonProperty("stopWhen") StopWhen stopWhen,
        Integer segments
) implements ActionPayload {

    private static final Set<String> DIRECTION_VALUES = Set.of("down", "up", "left", "right");

    public ScrollRegionPayload {
        if (regionKey == null || regionKey.isBlank()) {
            throw new IllegalArgumentException("regionKey is required");
        }
        if (direction == null || !DIRECTION_VALUES.contains(direction)) {
            throw new IllegalArgumentException("direction must be one of " + DIRECTION_VALUES);
        }
        if (!Double.isFinite(amount) || amount <= 0) {
            throw new IllegalArgumentException("amount must be a positive finite number");
        }
        if (segments == null) {
            segments = 5;
        }
        if (segments < 1) {
            throw new IllegalArgumentException("segments must be >= 1");
        }
    }

    public record StopWhen(
            String type,
            String selector,
            String text
    ) {
        private static final Set<String> TYPE_VALUES =
                Set.of("edge", "selector_visible", "text_visible");

        public StopWhen {
            if (type == null || !TYPE_VALUES.contains(type)) {
                throw new IllegalArgumentException("stopWhen.type must be one of " + TYPE_VALUES);
            }
            if ("selector_visible".equals(type) && (selector == null || selector.isBlank())) {
                throw new IllegalArgumentException("stopWhen.selector is required");
            }
            if ("text_visible".equals(type) && (text == null || text.isBlank())) {
                throw new IllegalArgumentException("stopWhen.text is required");
            }
        }
    }
}
