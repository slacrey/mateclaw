package vip.mate.workflow.v2;

import vip.mate.os.run.model.AgentRunEntity;

import java.util.Map;

public interface WorkflowRuntimeV2 {

    AgentRunEntity start(WorkflowV2Graph graph, Map<String, Object> input);
}
