package vip.mate.browser.edge.protocol.v2;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Map;

public record BrowserActionV2Envelope(
        @JsonProperty("protocolVersion") int protocolVersion,
        String sessionId,
        String runId,
        String stepId,
        BrowserTarget target,
        String kind,
        String id,
        Map<String, Object> payload,
        Map<String, Object> policy,
        Long deadlineMs
) {
    public BrowserActionV2Envelope {
        if (protocolVersion != 2) {
            throw new IllegalArgumentException("protocolVersion must be 2");
        }
        if (sessionId == null || sessionId.isBlank()) {
            throw new IllegalArgumentException("sessionId is required");
        }
        if (runId == null || runId.isBlank()) {
            throw new IllegalArgumentException("runId is required");
        }
        if (stepId == null || stepId.isBlank()) {
            throw new IllegalArgumentException("stepId is required");
        }
        if (target == null) {
            throw new IllegalArgumentException("target is required");
        }
        if (kind == null || kind.isBlank()) {
            throw new IllegalArgumentException("kind is required");
        }
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("id is required");
        }
        if (deadlineMs == null) {
            deadlineMs = 30_000L;
        }
        if (deadlineMs <= 0) {
            throw new IllegalArgumentException("deadlineMs must be > 0");
        }
    }
}
