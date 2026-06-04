package vip.mate.workflow.v2;

import java.util.List;

public record WorkflowV2Graph(
        String apiVersion,
        String id,
        String version,
        String entry,
        List<WorkflowV2StepSpec> steps
) {
    public WorkflowV2Graph {
        if (apiVersion == null || apiVersion.isBlank()) {
            apiVersion = "mateclaw.workflow/v2";
        }
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("workflow id is required");
        }
        if (entry == null || entry.isBlank()) {
            throw new IllegalArgumentException("workflow entry is required");
        }
        if (steps == null || steps.isEmpty()) {
            throw new IllegalArgumentException("workflow steps are required");
        }
    }
}
