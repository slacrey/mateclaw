package vip.mate.browser.edge.protocol;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * Canonical envelope shared between Control Plane, Native Host, and Extension.
 * See docs/specs/edge-protocol.md for the wire contract.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class EdgeMessage {

    /** Protocol version. Receivers MUST close with 4400 on unknown v. */
    private int v;

    @JsonProperty("msg_id")
    private String msgId;

    private EdgeMessageKind kind;

    /** Sender wall-clock epoch millis. */
    private long ts;

    @JsonProperty("trace_id")
    private String traceId;

    @JsonProperty("session_id")
    private String sessionId;

    @JsonProperty("in_reply_to")
    private String inReplyTo;

    /** Kind-specific payload. Schema documented per-kind in the spec. */
    private Map<String, Object> payload;
}
