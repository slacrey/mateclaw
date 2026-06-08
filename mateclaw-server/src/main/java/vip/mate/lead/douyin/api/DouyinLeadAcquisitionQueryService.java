package vip.mate.lead.douyin.api;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.springframework.stereotype.Service;
import vip.mate.os.run.model.AgentEventEntity;
import vip.mate.os.run.model.AgentRunEntity;
import vip.mate.os.run.model.LeadCommentEntity;
import vip.mate.os.run.model.LeadEngagementEntity;
import vip.mate.os.run.model.LeadProfileEntity;
import vip.mate.os.run.model.LeadTaskEntity;
import vip.mate.os.run.repository.AgentEventMapper;
import vip.mate.os.run.repository.AgentRunMapper;
import vip.mate.os.run.repository.LeadCommentMapper;
import vip.mate.os.run.repository.LeadEngagementMapper;
import vip.mate.os.run.repository.LeadProfileMapper;
import vip.mate.os.run.repository.LeadTaskMapper;

import java.util.List;

@Service
public class DouyinLeadAcquisitionQueryService {

    private final AgentRunMapper runMapper;
    private final AgentEventMapper eventMapper;
    private final LeadTaskMapper taskMapper;
    private final LeadCommentMapper commentMapper;
    private final LeadProfileMapper profileMapper;
    private final LeadEngagementMapper engagementMapper;

    public DouyinLeadAcquisitionQueryService(AgentRunMapper runMapper,
                                             AgentEventMapper eventMapper,
                                             LeadTaskMapper taskMapper,
                                             LeadCommentMapper commentMapper,
                                             LeadProfileMapper profileMapper,
                                             LeadEngagementMapper engagementMapper) {
        this.runMapper = runMapper;
        this.eventMapper = eventMapper;
        this.taskMapper = taskMapper;
        this.commentMapper = commentMapper;
        this.profileMapper = profileMapper;
        this.engagementMapper = engagementMapper;
    }

    public DouyinLeadAcquisitionRunResponse byRun(Long runId) {
        AgentRunEntity run = requireRun(runId);
        LeadTaskEntity task = taskMapper.selectOne(new LambdaQueryWrapper<LeadTaskEntity>()
                .eq(LeadTaskEntity::getRunId, runId)
                .last("LIMIT 1"));
        Long taskId = task == null ? null : task.getId();
        List<LeadCommentDTO> comments = taskId == null ? List.of() : comments(taskId);
        List<LeadCommentDTO> matches = comments.stream().filter(LeadCommentDTO::matched).toList();
        List<LeadEngagementDTO> engagements = taskId == null ? List.of() : engagements(taskId);
        return new DouyinLeadAcquisitionRunResponse(
                String.valueOf(run.getId()),
                taskId == null ? null : String.valueOf(taskId),
                run.getStatus(),
                comments.size(),
                matches.size(),
                comments,
                matches,
                engagements,
                events(runId));
    }

    public DouyinLeadAcquisitionRunResponse byTask(Long taskId) {
        LeadTaskEntity task = requireTask(taskId);
        return byRun(task.getRunId());
    }

    public List<LeadCommentDTO> comments(Long taskId) {
        return commentMapper.selectList(new LambdaQueryWrapper<LeadCommentEntity>()
                        .eq(LeadCommentEntity::getTaskId, taskId)
                        .orderByAsc(LeadCommentEntity::getCreateTime))
                .stream()
                .map(LeadCommentDTO::from)
                .toList();
    }

    public List<LeadProfileDTO> profiles(Long taskId) {
        return profileMapper.selectList(new LambdaQueryWrapper<LeadProfileEntity>()
                        .eq(LeadProfileEntity::getTaskId, taskId)
                        .orderByAsc(LeadProfileEntity::getCreateTime))
                .stream()
                .map(LeadProfileDTO::from)
                .toList();
    }

    public List<LeadEngagementDTO> engagements(Long taskId) {
        return engagementMapper.selectList(new LambdaQueryWrapper<LeadEngagementEntity>()
                        .eq(LeadEngagementEntity::getTaskId, taskId)
                        .orderByAsc(LeadEngagementEntity::getCreateTime))
                .stream()
                .map(LeadEngagementDTO::from)
                .toList();
    }

    private List<RunTimelineEventDTO> events(Long runId) {
        return eventMapper.selectList(new LambdaQueryWrapper<AgentEventEntity>()
                        .eq(AgentEventEntity::getRunId, runId)
                        .orderByAsc(AgentEventEntity::getCreateTime))
                .stream()
                .map(RunTimelineEventDTO::from)
                .toList();
    }

    private AgentRunEntity requireRun(Long runId) {
        AgentRunEntity run = runMapper.selectById(runId);
        if (run == null) {
            throw new IllegalArgumentException("run not found: " + runId);
        }
        return run;
    }

    private LeadTaskEntity requireTask(Long taskId) {
        LeadTaskEntity task = taskMapper.selectById(taskId);
        if (task == null) {
            throw new IllegalArgumentException("lead task not found: " + taskId);
        }
        return task;
    }
}
