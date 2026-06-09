package vip.mate.os.run.runtime;

import vip.mate.os.run.model.AgentStepStatus;

public record StepCloseRequest(
        Long stepId,
        AgentStepStatus status,
        String outputRef,
        String checkpointRef,
        String failureCode,
        String failureMessage,
        Integer tokenInput,
        Integer tokenOutput
) {
    public StepCloseRequest {
        if (stepId == null) {
            throw new IllegalArgumentException("stepId is required");
        }
        if (status == null || !status.isTerminal()) {
            throw new IllegalArgumentException("terminal step status is required");
        }
    }
}
