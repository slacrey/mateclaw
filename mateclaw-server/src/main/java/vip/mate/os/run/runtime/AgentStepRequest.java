package vip.mate.os.run.runtime;

public record AgentStepRequest(
        Long runId,
        Long parentStepId,
        String stepKey,
        String parentStepKey,
        String stepType,
        String idempotencyKey,
        String policyTags,
        String inputRef
) {
    public AgentStepRequest {
        if (runId == null) {
            throw new IllegalArgumentException("runId is required");
        }
        if (stepKey == null || stepKey.isBlank()) {
            throw new IllegalArgumentException("stepKey is required");
        }
        if (stepType == null || stepType.isBlank()) {
            throw new IllegalArgumentException("stepType is required");
        }
    }
}
