package vip.mate.workflow.v2;

import java.util.Map;

public record WorkflowExecutionContextV2(
        Long runId,
        Long workspaceId,
        Map<String, Object> input
) {
}
