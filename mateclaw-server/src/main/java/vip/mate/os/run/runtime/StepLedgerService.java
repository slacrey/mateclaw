package vip.mate.os.run.runtime;

import org.springframework.stereotype.Service;
import vip.mate.os.run.model.AgentRunEntity;
import vip.mate.os.run.model.AgentRunStatus;
import vip.mate.os.run.model.AgentStepEntity;
import vip.mate.os.run.model.AgentStepStatus;
import vip.mate.os.run.repository.AgentRunMapper;
import vip.mate.os.run.repository.AgentStepMapper;

import java.time.Duration;
import java.time.LocalDateTime;

@Service
public class StepLedgerService {

    private final AgentRunMapper runMapper;
    private final AgentStepMapper stepMapper;
    private final PolicyEngine policyEngine;
    private final RunEventPublisher events;

    public StepLedgerService(AgentRunMapper runMapper,
                             AgentStepMapper stepMapper,
                             PolicyEngine policyEngine,
                             RunEventPublisher events) {
        this.runMapper = runMapper;
        this.stepMapper = stepMapper;
        this.policyEngine = policyEngine;
        this.events = events;
    }

    public AgentStepEntity openStep(AgentStepRequest request) {
        AgentRunEntity run = requireRun(request.runId());
        if (AgentRunStatus.parse(run.getStatus()).isTerminal()) {
            throw new IllegalStateException("cannot open step for terminal run " + run.getId());
        }

        PolicyDecision policy = policyEngine.evaluate(request);
        AgentStepEntity step = new AgentStepEntity();
        step.setRunId(request.runId());
        step.setParentStepId(request.parentStepId());
        step.setStepKey(request.stepKey());
        step.setParentStepKey(request.parentStepKey());
        step.setStepType(request.stepType());
        step.setStatus(policy.decision() == PolicyDecision.Decision.REQUIRE_APPROVAL
                ? AgentStepStatus.WAITING_APPROVAL.wire()
                : AgentStepStatus.RUNNING.wire());
        step.setAttempt(1);
        step.setIdempotencyKey(request.idempotencyKey());
        step.setPolicyTags(request.policyTags());
        step.setInputRef(request.inputRef());
        step.setStartedAt(LocalDateTime.now());
        stepMapper.insert(step);

        run.setCurrentStepKey(step.getStepKey());
        run.setStatus(policy.decision() == PolicyDecision.Decision.REQUIRE_APPROVAL
                ? AgentRunStatus.WAITING_APPROVAL.wire()
                : AgentRunStatus.RUNNING.wire());
        runMapper.updateById(run);

        events.publish(new RunEvent(run.getId(), step.getId(), "step_started", "info",
                "{\"stepKey\":\"" + step.getStepKey() + "\",\"stepType\":\"" + step.getStepType() + "\"}",
                null));
        events.publish(new RunEvent(run.getId(), step.getId(), "policy_decision", "info",
                "{\"decision\":\"" + policy.decision() + "\",\"reason\":\"" + policy.reason() + "\"}",
                null));
        return step;
    }

    public AgentStepEntity closeStep(StepCloseRequest request) {
        AgentStepEntity step = stepMapper.selectById(request.stepId());
        if (step == null) {
            throw new IllegalArgumentException("agent step not found: " + request.stepId());
        }
        LocalDateTime completedAt = LocalDateTime.now();
        step.setStatus(request.status().wire());
        step.setOutputRef(request.outputRef());
        step.setCheckpointRef(request.checkpointRef());
        step.setFailureCode(request.failureCode());
        step.setFailureMessage(request.failureMessage());
        step.setTokenInput(request.tokenInput());
        step.setTokenOutput(request.tokenOutput());
        step.setCompletedAt(completedAt);
        if (step.getStartedAt() != null) {
            step.setDurationMs(Duration.between(step.getStartedAt(), completedAt).toMillis());
        }
        stepMapper.updateById(step);
        events.publish(new RunEvent(step.getRunId(), step.getId(),
                request.status() == AgentStepStatus.SUCCEEDED ? "step_completed" : "step_failed",
                request.status() == AgentStepStatus.SUCCEEDED ? "info" : "error",
                "{\"status\":\"" + request.status().wire() + "\"}", null));
        return step;
    }

    private AgentRunEntity requireRun(Long runId) {
        AgentRunEntity run = runMapper.selectById(runId);
        if (run == null) {
            throw new IllegalArgumentException("agent run not found: " + runId);
        }
        return run;
    }
}
