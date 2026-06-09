package vip.mate.workflow.v2;

public record WorkflowStepResultV2(
        Status status,
        String outputRef,
        Object outputValue,
        String failureCode,
        String failureMessage,
        boolean retryable
) {
    public enum Status {
        SUCCEEDED,
        FAILED,
        PAUSED,
        SKIPPED
    }
}
