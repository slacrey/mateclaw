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
}
