package vip.mate.os.run.runtime;

public record RunEvent(
        Long runId,
        Long stepId,
        String eventType,
        String severity,
        Object payload,
        String artifactIds
) {
    public RunEvent {
        if (runId == null) {
            throw new IllegalArgumentException("runId is required");
        }
        if (eventType == null || eventType.isBlank()) {
            throw new IllegalArgumentException("eventType is required");
        }
        if (severity == null || severity.isBlank()) {
            severity = "info";
        }
    }
}
