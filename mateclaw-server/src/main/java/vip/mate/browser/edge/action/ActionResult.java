package vip.mate.browser.edge.action;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.annotation.JsonTypeName;

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.EXISTING_PROPERTY, property = "ok")
@JsonSubTypes({
        @JsonSubTypes.Type(value = ActionResult.Success.class, name = "true"),
        @JsonSubTypes.Type(value = ActionResult.Failure.class, name = "false")
})
public sealed interface ActionResult permits ActionResult.Success, ActionResult.Failure {

    @JsonTypeName("true")
    @JsonPropertyOrder({"ok", "elapsed_ms", "payload"})
    record Success(
            @JsonProperty("elapsed_ms") long elapsedMs,
            ActionSuccessPayload payload
    ) implements ActionResult {

        @JsonProperty("ok")
        public boolean ok() {
            return true;
        }

        public Success {
            if (payload == null) {
                throw new IllegalArgumentException("payload is required");
            }
        }
    }

    @JsonTypeName("false")
    @JsonPropertyOrder({"ok", "code", "message", "retryable"})
    record Failure(
            String code,
            String message,
            boolean retryable
    ) implements ActionResult {

        @JsonProperty("ok")
        public boolean ok() {
            return false;
        }

        public Failure {
            if (code == null) {
                throw new IllegalArgumentException("code is required");
            }
            if (message == null) {
                throw new IllegalArgumentException("message is required");
            }
        }
    }
}
