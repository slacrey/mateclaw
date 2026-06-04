package vip.mate.workflow.v2;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;

public record WorkflowV2StepSpec(
        String id,
        WorkflowV2StepType type,
        JsonNode with,
        JsonNode policy,
        JsonNode retry,
        String idempotencyKey,
        String output,
        Long timeoutMs,
        JsonNode precondition,
        JsonNode postcondition,
        List<WorkflowV2StepSpec> steps
) {
    public WorkflowV2StepSpec {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("workflow step id is required");
        }
        if (type == null) {
            throw new IllegalArgumentException("workflow step type is required");
        }
    }
}
