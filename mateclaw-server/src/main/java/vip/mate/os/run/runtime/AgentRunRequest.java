package vip.mate.os.run.runtime;

public record AgentRunRequest(
        Long workspaceId,
        Long conversationId,
        String traceId,
        String runType,
        String sourceType,
        String sourceRef,
        String inputRef,
        Long createdBy
) {
    public AgentRunRequest {
        if (workspaceId == null) {
            throw new IllegalArgumentException("workspaceId is required");
        }
        if (runType == null || runType.isBlank()) {
            throw new IllegalArgumentException("runType is required");
        }
    }
}
