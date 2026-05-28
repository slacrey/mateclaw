package vip.mate.browser.edge.protocol;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

public enum EdgeMessageKind {
    // -----------------------------------------------------------------
    // Protocol v1.0 — handshake + liveness (Phase 1)
    // -----------------------------------------------------------------
    HELLO("hello"),
    HELLO_ACK("hello.ack"),
    HEARTBEAT("heartbeat"),
    HEARTBEAT_ACK("heartbeat.ack"),
    PING("ping"),
    PONG("pong"),
    ERROR("error"),

    // -----------------------------------------------------------------
    // Protocol v1.1 — atomic browser actions (Phase 2 P-stream)
    //
    // CP → NH → Ext: action.execute / action.cancel
    // Ext → NH → CP: action.result
    //
    // Every envelope addressed at a tab carries a `tab_ref` field
    // ("main" | "active" | <int>); see docs/specs/edge-protocol.md.
    // -----------------------------------------------------------------
    ACTION_EXECUTE("action.execute"),
    ACTION_RESULT("action.result"),
    ACTION_CANCEL("action.cancel"),

    // -----------------------------------------------------------------
    // Protocol v1.1 — visual indicators (cursor / glow / stop button)
    //
    // tool_use_hide / tool_use_show flank screenshot capture so the
    // overlay never appears in the model's screenshot.
    // -----------------------------------------------------------------
    INDICATOR_SHOW("indicator.show"),
    INDICATOR_HIDE("indicator.hide"),
    INDICATOR_CURSOR("indicator.cursor"),
    INDICATOR_TOOL_USE_HIDE("indicator.tool_use_hide"),
    INDICATOR_TOOL_USE_SHOW("indicator.tool_use_show"),
    /** User clicked the in-page stop button — flows Ext → NH → CP. */
    INDICATOR_STOP_CLICKED("indicator.stop_clicked"),

    // -----------------------------------------------------------------
    // Protocol v1.1 — accessibility tree snapshot (grounding source)
    // -----------------------------------------------------------------
    A11Y_SNAPSHOT_REQUEST("a11y.snapshot.request"),
    A11Y_SNAPSHOT_RESPONSE("a11y.snapshot.response"),

    // -----------------------------------------------------------------
    // Protocol v1.1 — unsolicited page-lifecycle events from Ext → CP
    // -----------------------------------------------------------------
    EVENT_PAGE_NAVIGATED("event.page.navigated"),
    EVENT_TAB_CLOSED("event.tab.closed"),

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
