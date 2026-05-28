package vip.mate.browser.edge.protocol;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

public enum EdgeMessageKind {
    HELLO("hello"),
    HELLO_ACK("hello.ack"),
    HEARTBEAT("heartbeat"),
    HEARTBEAT_ACK("heartbeat.ack"),
    PING("ping"),
    PONG("pong"),
    ERROR("error"),
    /** Unknown wire kind. Forward-compat: receivers warn-and-drop, do not close. */
    UNKNOWN("__unknown__");

    private final String wire;

    EdgeMessageKind(String wire) { this.wire = wire; }

    @JsonValue
    public String wire() { return wire; }

    @JsonCreator
    public static EdgeMessageKind fromWire(String s) {
        if (s == null) return UNKNOWN;
        for (EdgeMessageKind k : values()) {
            if (k.wire.equals(s)) return k;
        }
        return UNKNOWN;
    }
}
