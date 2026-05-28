package vip.mate.browser.edge.action;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ActionRequestTest {

    private final ObjectMapper mapper = new ObjectMapper();

    // -----------------------------------------------------------------
    // Round-trip — one case per ActionKind. Each asserts the wire shape
    // matches docs/specs/edge-protocol.md §"action.execute": outer
    // kind + nested params (with inner kind discriminator).
    // -----------------------------------------------------------------

    @Test
    void navigate_roundTripsThroughMapper() throws Exception {
        ActionRequest req = new ActionRequest(
                "00000000-0000-0000-0000-000000000001",
                new TabRef.Main(),
                ActionKind.NAVIGATE,
                new NavigatePayload("https://example.com", null, "load"),
                30_000L);

        String json = mapper.writeValueAsString(req);
        ActionRequest back = mapper.readValue(json, ActionRequest.class);

        assertThat(json)
                .contains("\"tab_ref\":\"main\"")
                .contains("\"kind\":\"navigate\"")
                .contains("\"deadline_ms\":30000");
        assertThat(back).isEqualTo(req);
    }

    @Test
    void click_roundTripsWithExplicitTabRef() throws Exception {
        ActionRequest req = new ActionRequest(
                "msg-2",
                new TabRef.Explicit(42L),
                ActionKind.CLICK,
                new ClickPayload(100, 200, "left", 1),
                15_000L);

        String json = mapper.writeValueAsString(req);
        ActionRequest back = mapper.readValue(json, ActionRequest.class);

        // Explicit tab id is bare integer per TabRefSerializer
        assertThat(json).contains("\"tab_ref\":42");
        assertThat(back).isEqualTo(req);
    }

    @Test
    void moveMouse_roundTripsWithActiveTabRef() throws Exception {
        ActionRequest req = new ActionRequest(
                "msg-3",
                new TabRef.Active(),
                ActionKind.MOVE_MOUSE,
                new MoveMousePayload(300, 400, "natural"),
                10_000L);

        String json = mapper.writeValueAsString(req);
        ActionRequest back = mapper.readValue(json, ActionRequest.class);

        assertThat(json).contains("\"tab_ref\":\"active\"");
        assertThat(back).isEqualTo(req);
    }

    // -----------------------------------------------------------------
    // Construction-time validation — the record's compact constructor
    // enforces that the outer ActionKind matches the payload subtype.
    // This catches the most likely caller mistake (forgetting to update
    // one half when changing an action) at the boundary.
    // -----------------------------------------------------------------

    @Test
    void mismatchedKindAndPayload_throwsAtConstruction() {
        // kind=NAVIGATE but payload is ClickPayload — caught by the
        // compact constructor's invariant check.
        assertThatThrownBy(() -> new ActionRequest(
                "x",
                new TabRef.Main(),
                ActionKind.NAVIGATE,
                new ClickPayload(1, 2, "left", 1),
                1_000L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("kind");
    }

    @Test
    void nullMsgId_throws() {
        assertThatThrownBy(() -> new ActionRequest(
                null,
                new TabRef.Main(),
                ActionKind.WAIT,
                new WaitPayload("network_idle", null, 500L, null),
                1_000L))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void nullTabRef_throws() {
        assertThatThrownBy(() -> new ActionRequest(
                "x",
                null,
                ActionKind.WAIT,
                new WaitPayload("network_idle", null, 500L, null),
                1_000L))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void nonPositiveDeadline_throws() {
        assertThatThrownBy(() -> new ActionRequest(
                "x",
                new TabRef.Main(),
                ActionKind.CLICK,
                new ClickPayload(1, 2, "left", 1),
                0L))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // -----------------------------------------------------------------
    // Spec-shape verification — every ActionRequest serialises to the
    // wire format documented in docs/specs/edge-protocol.md.
    // -----------------------------------------------------------------

    @Test
    void wireFormat_carriesAllRequiredFields() throws Exception {
        ActionRequest req = new ActionRequest(
                "abc-123",
                new TabRef.Main(),
                ActionKind.TYPE,
                new TypePayload("hello", null),
                5_000L);

        String json = mapper.writeValueAsString(req);

        // All five top-level keys present, in canonical snake_case
        assertThat(json)
                .contains("\"msg_id\":\"abc-123\"")
                .contains("\"tab_ref\":\"main\"")
                .contains("\"kind\":\"type\"")
                .contains("\"params\":")
                .contains("\"deadline_ms\":5000");
    }
}
