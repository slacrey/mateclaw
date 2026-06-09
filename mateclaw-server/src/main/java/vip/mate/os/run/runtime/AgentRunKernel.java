package vip.mate.os.run.runtime;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vip.mate.os.run.model.AgentRunEntity;
import vip.mate.os.run.model.AgentRunStatus;
import vip.mate.os.run.repository.AgentRunMapper;

import java.time.LocalDateTime;

@Service
public class AgentRunKernel {

    private final AgentRunMapper runMapper;
    private final AgentRunStateMachine stateMachine;
    private final RunCancellationService cancellationService;
    private final RunEventPublisher events;

    public AgentRunKernel(AgentRunMapper runMapper,
                          AgentRunStateMachine stateMachine,
                          RunCancellationService cancellationService,
                          RunEventPublisher events) {
        this.runMapper = runMapper;
        this.stateMachine = stateMachine;
        this.cancellationService = cancellationService;
        this.events = events;
    }

    @Transactional
    public AgentRunEntity createRun(AgentRunRequest request) {
        AgentRunEntity run = new AgentRunEntity();
        run.setWorkspaceId(request.workspaceId());
        run.setConversationId(request.conversationId());
        run.setTraceId(request.traceId());
        run.setRunType(request.runType());
        run.setSourceType(request.sourceType());
        run.setSourceRef(request.sourceRef());
        run.setStatus(AgentRunStatus.CREATED.wire());
        run.setInputRef(request.inputRef());
        run.setCreatedBy(request.createdBy());
        run.setDeleted(0);
        runMapper.insert(run);
        events.publish(new RunEvent(run.getId(), null, "run_created", "info",
                "{\"runType\":\"" + run.getRunType() + "\"}", null));
        return run;
    }

    @Transactional
    public AgentRunEntity startRun(Long runId) {
        AgentRunEntity run = transition(runId, AgentRunStatus.RUNNING);
        if (run.getStartedAt() == null) {
            run.setStartedAt(LocalDateTime.now());
            runMapper.updateById(run);
        }
        events.publish(new RunEvent(runId, null, "run_started", "info", null, null));
        return run;
    }

    @Transactional
    public AgentRunEntity transition(Long runId, AgentRunStatus to) {
        AgentRunEntity run = requireRun(runId);
        AgentRunStatus from = AgentRunStatus.parse(run.getStatus());
        stateMachine.assertTransition(from, to);
        run.setStatus(to.wire());
        if (to.isTerminal()) {
            run.setCompletedAt(LocalDateTime.now());
            cancellationService.clear(runId);
        }
        runMapper.updateById(run);
        events.publish(new RunEvent(runId, null, "run_status_changed", "info",
                "{\"from\":\"" + from.wire() + "\",\"to\":\"" + to.wire() + "\"}", null));
        return run;
    }

    @Transactional
    public AgentRunEntity finishSucceeded(Long runId, String outputRef) {
        AgentRunEntity run = requireRun(runId);
        run.setOutputRef(outputRef);
        runMapper.updateById(run);
        return transition(runId, AgentRunStatus.SUCCEEDED);
    }

    @Transactional
    public AgentRunEntity finishFailed(Long runId, String failureCode, String failureMessage) {
        AgentRunEntity run = requireRun(runId);
        run.setFailureCode(failureCode);
        run.setFailureMessage(truncate(failureMessage, 1900));
        runMapper.updateById(run);
        return transition(runId, AgentRunStatus.FAILED);
    }

    @Transactional
    public AgentRunEntity requestCancel(Long runId) {
        cancellationService.requestCancel(runId);
        AgentRunEntity run = transition(runId, AgentRunStatus.CANCELLING);
        events.publish(new RunEvent(runId, null, "cancel", "warn", null, null));
        return run;
    }

    private AgentRunEntity requireRun(Long runId) {
        AgentRunEntity run = runMapper.selectById(runId);
        if (run == null) {
            throw new IllegalArgumentException("agent run not found: " + runId);
        }
        return run;
    }

    private String truncate(String value, int maxChars) {
        if (value == null || value.length() <= maxChars) {
            return value;
        }
        return value.substring(0, Math.max(0, maxChars - 32)) + "...[truncated " + value.length() + " chars]";
    }
}
