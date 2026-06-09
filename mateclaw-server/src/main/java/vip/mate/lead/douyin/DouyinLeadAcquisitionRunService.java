package vip.mate.lead.douyin;

import org.springframework.stereotype.Service;
import vip.mate.lead.douyin.api.DouyinLeadAcquisitionRunResponse;
import vip.mate.lead.douyin.model.DouyinLeadAcquisitionInput;
import vip.mate.lead.douyin.store.LeadPersistenceService;
import vip.mate.os.run.model.AgentRunEntity;
import vip.mate.os.run.runtime.AgentRunKernel;
import vip.mate.os.run.runtime.AgentRunRequest;
import vip.mate.os.run.runtime.RunCancellationService;
import vip.mate.os.run.model.LeadTaskEntity;

import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Service
public class DouyinLeadAcquisitionRunService {

    private final AgentRunKernel runKernel;
    private final RunCancellationService cancellationService;
    private final LeadPersistenceService persistence;
    private final DouyinLeadAcquisitionExecutor executor;
    private final ExecutorService worker = Executors.newVirtualThreadPerTaskExecutor();

    public DouyinLeadAcquisitionRunService(AgentRunKernel runKernel,
                                           RunCancellationService cancellationService,
                                           LeadPersistenceService persistence,
                                           DouyinLeadAcquisitionExecutor executor) {
        this.runKernel = runKernel;
        this.cancellationService = cancellationService;
        this.persistence = persistence;
        this.executor = executor;
    }

    public DouyinLeadAcquisitionRunResponse start(Long workspaceId, Long createdBy, DouyinLeadAcquisitionInput input) {
        RunAndTask created = createRunAndTask(workspaceId, createdBy, input);
        worker.submit(() -> executor.execute(created.run().getId(), created.task().getId(), input));
        return DouyinLeadAcquisitionRunResponse.started(created.run().getId(), created.task().getId(), created.run().getStatus());
    }

    public DouyinLeadAcquisitionRunResponse runSync(Long workspaceId, Long createdBy, DouyinLeadAcquisitionInput input,
                                                    vip.mate.lead.douyin.api.DouyinLeadAcquisitionQueryService queryService) {
        RunAndTask created = createRunAndTask(workspaceId, createdBy, input);
        executor.execute(created.run().getId(), created.task().getId(), input);
        return queryService.byRun(created.run().getId());
    }

    private RunAndTask createRunAndTask(Long workspaceId, Long createdBy, DouyinLeadAcquisitionInput input) {
        AgentRunEntity run = runKernel.createRun(new AgentRunRequest(
                workspaceId == null ? 1L : workspaceId,
                null,
                UUID.randomUUID().toString(),
                "skill.douyin.lead_acquisition.v2",
                "skill",
                "douyin.lead_acquisition.v2",
                null,
                createdBy));
        LeadTaskEntity task = persistence.createTask(run.getId(), run.getWorkspaceId(), input);
        return new RunAndTask(run, task);
    }

    public void cancel(Long runId) {
        cancellationService.requestCancel(runId);
        runKernel.requestCancel(runId);
    }

    private record RunAndTask(AgentRunEntity run, LeadTaskEntity task) {
    }
}
