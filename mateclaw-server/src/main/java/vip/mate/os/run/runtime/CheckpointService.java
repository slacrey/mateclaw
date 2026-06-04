package vip.mate.os.run.runtime;

import org.springframework.stereotype.Service;
import vip.mate.os.run.model.AgentRunEntity;
import vip.mate.os.run.model.AgentStepEntity;
import vip.mate.os.run.repository.AgentRunMapper;
import vip.mate.os.run.repository.AgentStepMapper;

@Service
public class CheckpointService {

    private final AgentRunMapper runMapper;
    private final AgentStepMapper stepMapper;
    private final RunEventPublisher events;

    public CheckpointService(AgentRunMapper runMapper,
                             AgentStepMapper stepMapper,
                             RunEventPublisher events) {
        this.runMapper = runMapper;
        this.stepMapper = stepMapper;
        this.events = events;
    }

    public void writeRunCheckpoint(Long runId, String checkpointRef, String currentStepKey) {
        AgentRunEntity run = runMapper.selectById(runId);
        if (run == null) {
            throw new IllegalArgumentException("agent run not found: " + runId);
        }
        run.setCheckpointRef(checkpointRef);
        run.setCurrentStepKey(currentStepKey);
        runMapper.updateById(run);
        events.publish(new RunEvent(runId, null, "checkpoint_written", "info",
                "{\"checkpointRef\":\"" + checkpointRef + "\"}", null));
    }

    public void writeStepCheckpoint(Long stepId, String checkpointRef) {
        AgentStepEntity step = stepMapper.selectById(stepId);
        if (step == null) {
            throw new IllegalArgumentException("agent step not found: " + stepId);
        }
        step.setCheckpointRef(checkpointRef);
        stepMapper.updateById(step);
        events.publish(new RunEvent(step.getRunId(), stepId, "checkpoint_written", "info",
                "{\"checkpointRef\":\"" + checkpointRef + "\"}", null));
    }
}
