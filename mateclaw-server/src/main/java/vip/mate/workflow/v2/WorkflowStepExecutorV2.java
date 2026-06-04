package vip.mate.workflow.v2;

public interface WorkflowStepExecutorV2 {

    WorkflowV2StepType type();

    WorkflowStepResultV2 execute(WorkflowV2StepSpec step, WorkflowExecutionContextV2 context);
}
