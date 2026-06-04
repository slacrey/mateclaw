package vip.mate.browser.edge.action;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

public enum ActionKind {
    NAVIGATE("navigate"),
    CLICK("click"),
    TYPE("type"),
    PRESS_KEY("press_key"),
    SCROLL("scroll"),
    SCROLL_REGION("scroll_region"),
    MOVE_MOUSE("move_mouse"),
    WAIT("wait");

    private final String wire;

    ActionKind(String wire) {
        this.wire = wire;
    }

    @JsonValue
    public String wire() {
        return wire;
    }

    @JsonCreator
    public static ActionKind fromWire(String s) {
        for (ActionKind k : values()) {
            if (k.wire.equals(s)) {
                return k;
            }
        }
        throw new IllegalArgumentException("unknown ActionKind: " + s);
    }
}
