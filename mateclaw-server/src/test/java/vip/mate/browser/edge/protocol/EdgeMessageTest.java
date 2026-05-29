package vip.mate.browser.edge.protocol;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class EdgeMessageTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void serialise_roundtrip_preservesAllFields() throws Exception {
        EdgeMessage msg = EdgeMessage.builder()
                .v(1)
                .msgId("00000000-0000-0000-0000-000000000001")
                .kind(EdgeMessageKind.PING)
                .ts(1730000000123L)
                .traceId("00000000-0000-0000-0000-000000000002")
                .sessionId("sess-abc")
                .payload(Map.of("echo", "hello"))
                .build();

        String json = mapper.writeValueAsString(msg);
        EdgeMessage back = mapper.readValue(json, EdgeMessage.class);

        assertThat(back.getV()).isEqualTo(1);
        assertThat(back.getMsgId()).isEqualTo("00000000-0000-0000-0000-000000000001");
        assertThat(back.getKind()).isEqualTo(EdgeMessageKind.PING);
        assertThat(back.getTs()).isEqualTo(1730000000123L);
        assertThat(back.getTraceId()).isEqualTo("00000000-0000-0000-0000-000000000002");
        assertThat(back.getSessionId()).isEqualTo("sess-abc");
        assertThat(back.getPayload()).isEqualTo(Map.of("echo", "hello"));
    }

    @Test
    void kind_serialisesAsLowercaseDotted() throws Exception {
        EdgeMessage msg = EdgeMessage.builder()
                .v(1).msgId("x").kind(EdgeMessageKind.HELLO_ACK).ts(0L).traceId("x").sessionId("").build();
        String json = mapper.writeValueAsString(msg);
        assertThat(json).contains("\"kind\":\"hello.ack\"");
    }

    @Test
    void kind_deserialiseUnknown_yieldsUnknown() throws Exception {
        String json = """
            {"v":1,"msg_id":"x","kind":"future.thing","ts":0,"trace_id":"x","session_id":""}
            """;
        EdgeMessage msg = mapper.readValue(json, EdgeMessage.class);
        assertThat(msg.getKind()).isEqualTo(EdgeMessageKind.UNKNOWN);
    }

    // -----------------------------------------------------------------
    // Protocol v1.1 — action.* / indicator.* / a11y.* / event.* kinds
    // (Phase 2 Task P1)
    //
    // 13 new wire kinds split across four families. Each kind must
    // round-trip through Jackson to its exact wire string. Unknown
    // payloads continue to fall through to UNKNOWN (forward-compat).
    // -----------------------------------------------------------------

    @Test
    void v11_actionKinds_haveExactWireStrings() {
        assertThat(EdgeMessageKind.ACTION_EXECUTE.wire()).isEqualTo("action.execute");
        assertThat(EdgeMessageKind.ACTION_RESULT.wire()).isEqualTo("action.result");
        assertThat(EdgeMessageKind.ACTION_CANCEL.wire()).isEqualTo("action.cancel");
    }

    @Test
    void v11_indicatorKinds_haveExactWireStrings() {
        assertThat(EdgeMessageKind.INDICATOR_SHOW.wire()).isEqualTo("indicator.show");
        assertThat(EdgeMessageKind.INDICATOR_HIDE.wire()).isEqualTo("indicator.hide");
        assertThat(EdgeMessageKind.INDICATOR_CURSOR.wire()).isEqualTo("indicator.cursor");
        assertThat(EdgeMessageKind.INDICATOR_TOOL_USE_HIDE.wire()).isEqualTo("indicator.tool_use_hide");
        assertThat(EdgeMessageKind.INDICATOR_TOOL_USE_SHOW.wire()).isEqualTo("indicator.tool_use_show");
        assertThat(EdgeMessageKind.INDICATOR_STOP_CLICKED.wire()).isEqualTo("indicator.stop_clicked");
    }

    @Test
    void v11_a11yKinds_haveExactWireStrings() {
        assertThat(EdgeMessageKind.A11Y_SNAPSHOT_REQUEST.wire()).isEqualTo("a11y.snapshot.request");
        assertThat(EdgeMessageKind.A11Y_SNAPSHOT_RESPONSE.wire()).isEqualTo("a11y.snapshot.response");
    }

    @Test
    void v12_screenshotCaptureRequest_hasExactWireString() {
        assertThat(EdgeMessageKind.SCREENSHOT_CAPTURE_REQUEST.wire()).isEqualTo("screenshot.capture.request");
    }

    @Test
    void v12_screenshotCaptureResponse_hasExactWireString() {
        assertThat(EdgeMessageKind.SCREENSHOT_CAPTURE_RESPONSE.wire()).isEqualTo("screenshot.capture.response");
    }

    @Test
    void v11_eventKinds_haveExactWireStrings() {
        assertThat(EdgeMessageKind.EVENT_PAGE_NAVIGATED.wire()).isEqualTo("event.page.navigated");
        assertThat(EdgeMessageKind.EVENT_TAB_CLOSED.wire()).isEqualTo("event.tab.closed");
    }

    @Test
    void v11_actionExecute_roundTripsThroughMapper() throws Exception {
        EdgeMessage msg = EdgeMessage.builder()
                .v(1).msgId("m1").kind(EdgeMessageKind.ACTION_EXECUTE).ts(1L).traceId("t1").sessionId("s1")
                .payload(Map.of(
                        "tab_ref", "main",
                        "kind", "navigate",
                        "params", Map.of("url", "https://example.com"),
                        "deadline_ms", 30_000))
                .build();

        String json = mapper.writeValueAsString(msg);
        EdgeMessage back = mapper.readValue(json, EdgeMessage.class);

        assertThat(json).contains("\"kind\":\"action.execute\"");
        assertThat(back.getKind()).isEqualTo(EdgeMessageKind.ACTION_EXECUTE);
        assertThat(back.getPayload()).containsEntry("tab_ref", "main");
    }

    @Test
    void v11_indicatorStopClicked_extensionInbound_roundTrips() throws Exception {
        // Ext → NH → CP direction. session_id="" from Extension; NH stamps.
        EdgeMessage msg = EdgeMessage.builder()
                .v(1).msgId("m2").kind(EdgeMessageKind.INDICATOR_STOP_CLICKED).ts(2L).traceId("t2").sessionId("")
                .payload(Map.of("tab_ref", 42))
                .build();

        String json = mapper.writeValueAsString(msg);
        EdgeMessage back = mapper.readValue(json, EdgeMessage.class);

        assertThat(json).contains("\"kind\":\"indicator.stop_clicked\"");
        assertThat(back.getKind()).isEqualTo(EdgeMessageKind.INDICATOR_STOP_CLICKED);
    }

    @Test
    void v11_a11ySnapshotResponse_roundTrips() throws Exception {
        EdgeMessage msg = EdgeMessage.builder()
                .v(1).msgId("m3").kind(EdgeMessageKind.A11Y_SNAPSHOT_RESPONSE).ts(3L).traceId("t3").sessionId("s")
                .payload(Map.of(
                        "snapshot_id", "snap-1",
                        "captured_at_ms", 1730000000123L,
                        "tab_ref", 42,
                        "tree", "Button[ref=ref_1]: Submit",
                        "viewport", Map.of("w", 1280, "h", 800)))
                .build();

        String json = mapper.writeValueAsString(msg);
        EdgeMessage back = mapper.readValue(json, EdgeMessage.class);

        assertThat(json).contains("\"kind\":\"a11y.snapshot.response\"");
        assertThat(back.getKind()).isEqualTo(EdgeMessageKind.A11Y_SNAPSHOT_RESPONSE);
    }

    @Test
    void v12_screenshotCaptureRequest_roundTrips() throws Exception {
        EdgeMessage msg = EdgeMessage.builder()
                .v(1).msgId("m5").kind(EdgeMessageKind.SCREENSHOT_CAPTURE_REQUEST).ts(5L).traceId("t5").sessionId("s")
                .payload(Map.of(
                        "tab_ref", "main",
                        "format", "png",
                        "quality", 90,
                        "scale_factor", 1))
                .build();

        String json = mapper.writeValueAsString(msg);
        EdgeMessage back = mapper.readValue(json, EdgeMessage.class);

        assertThat(json).contains("\"kind\":\"screenshot.capture.request\"");
        assertThat(back.getKind()).isEqualTo(EdgeMessageKind.SCREENSHOT_CAPTURE_REQUEST);
        assertThat(back.getPayload()).containsEntry("tab_ref", "main");
    }

    @Test
    void v12_screenshotCaptureResponse_roundTrips() throws Exception {
        EdgeMessage msg = EdgeMessage.builder()
                .v(1).msgId("m6").kind(EdgeMessageKind.SCREENSHOT_CAPTURE_RESPONSE).ts(6L).traceId("t6").sessionId("")
                .payload(Map.of(
                        "snapshot_id", "shot-1",
                        "captured_at_ms", 1730000000123L,
                        "tab_ref", 42,
                        "format", "png",
                        "data_base64", "iVBORw0KGgoAAAANS",
                        "viewport", Map.of("w", 1280, "h", 800),
                        "actual_dimensions", Map.of("w", 1280, "h", 800)))
                .build();

        String json = mapper.writeValueAsString(msg);
        EdgeMessage back = mapper.readValue(json, EdgeMessage.class);

        assertThat(json).contains("\"kind\":\"screenshot.capture.response\"");
        assertThat(back.getKind()).isEqualTo(EdgeMessageKind.SCREENSHOT_CAPTURE_RESPONSE);
        assertThat(back.getSessionId()).isEmpty();
    }

    @Test
    void v11_eventPageNavigated_roundTrips() throws Exception {
        EdgeMessage msg = EdgeMessage.builder()
                .v(1).msgId("m4").kind(EdgeMessageKind.EVENT_PAGE_NAVIGATED).ts(4L).traceId("t4").sessionId("s")
                .payload(Map.of("tab_ref", 42, "url", "https://example.com/page2"))
                .build();

        String json = mapper.writeValueAsString(msg);
        EdgeMessage back = mapper.readValue(json, EdgeMessage.class);

        assertThat(json).contains("\"kind\":\"event.page.navigated\"");
        assertThat(back.getKind()).isEqualTo(EdgeMessageKind.EVENT_PAGE_NAVIGATED);
    }
}
