package vip.mate.lead.douyin.api;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import vip.mate.os.run.model.AgentRunStatus;
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

import java.util.ArrayList;
import java.util.List;

@Service
public class DouyinLeadAcquisitionQueryService {

    private final AgentRunMapper runMapper;
    private final AgentEventMapper eventMapper;
    private final LeadTaskMapper taskMapper;
    private final LeadCommentMapper commentMapper;
    private final LeadProfileMapper profileMapper;
    private final LeadEngagementMapper engagementMapper;
    private final ObjectMapper objectMapper;

    public DouyinLeadAcquisitionQueryService(AgentRunMapper runMapper,
                                             AgentEventMapper eventMapper,
                                             LeadTaskMapper taskMapper,
                                             LeadCommentMapper commentMapper,
                                             LeadProfileMapper profileMapper,
                                             LeadEngagementMapper engagementMapper,
                                             ObjectMapper objectMapper) {
        this.runMapper = runMapper;
        this.eventMapper = eventMapper;
        this.taskMapper = taskMapper;
        this.commentMapper = commentMapper;
        this.profileMapper = profileMapper;
        this.engagementMapper = engagementMapper;
        this.objectMapper = objectMapper;
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
        JsonNode summary = parseSummary(task);
        int commentsCollected = summary.path("commentsCollected").asInt(comments.size());
        int declaredCommentCount = summary.path("declaredCommentCount")
                .asInt(sumVideoResultInt(summary, "declaredCommentCount"));
        int remainingDeclaredComments = summary.path("remainingDeclaredComments").asInt(
                declaredCommentCount > 0 ? Math.max(0, declaredCommentCount - commentsCollected) : 0);
        return new DouyinLeadAcquisitionRunResponse(
                String.valueOf(run.getId()),
                taskId == null ? null : String.valueOf(taskId),
                run.getStatus(),
                commentsCollected,
                declaredCommentCount,
                remainingDeclaredComments,
                summary.path("collectionCoverage").asDouble(declaredCommentCount > 0
                        ? Math.min(1.0d, commentsCollected / (double) declaredCommentCount)
                        : 0.0d),
                summary.path("matchedComments").asInt(matches.size()),
                summary.path("requestedVideoLimit").asInt(0),
                summary.path("processedVideos").asInt(0),
                summary.path("succeededVideos").asInt(0),
                summary.path("failedVideos").asInt(0),
                summary.path("engagementsCreated").asInt(engagements.size()),
                comments,
                matches,
                engagements,
                events(runId));
    }

    public DouyinLeadAcquisitionRunResponse byTask(Long taskId) {
        LeadTaskEntity task = requireTask(taskId);
        return byRun(task.getRunId());
    }

    public List<DouyinLeadRunListItem> recentRuns(Long workspaceId, int limit) {
        int boundedLimit = Math.max(1, Math.min(50, limit));
        LambdaQueryWrapper<LeadTaskEntity> query = new LambdaQueryWrapper<LeadTaskEntity>()
                .eq(LeadTaskEntity::getPlatform, "douyin")
                .orderByDesc(LeadTaskEntity::getUpdateTime)
                .orderByDesc(LeadTaskEntity::getCreateTime);
        if (workspaceId != null) {
            query.eq(LeadTaskEntity::getWorkspaceId, workspaceId);
        }
        query.last("LIMIT " + boundedLimit);

        List<DouyinLeadRunListItem> rows = new ArrayList<>();
        for (LeadTaskEntity task : taskMapper.selectList(query)) {
            AgentRunEntity run = task.getRunId() == null ? null : runMapper.selectById(task.getRunId());
            JsonNode summary = parseSummary(task);
            JsonNode input = parseJson(task.getInputJson());
            int commentsCollected = summary.path("commentsCollected").asInt(countComments(task.getId(), false));
            int matchedComments = summary.path("matchedComments").asInt(countComments(task.getId(), true));
            int engagementsCreated = summary.path("engagementsCreated").asInt(countEngagements(task.getId()));
            rows.add(new DouyinLeadRunListItem(
                    DouyinLeadAcquisitionRunResponse.id(task.getRunId()),
                    DouyinLeadAcquisitionRunResponse.id(task.getId()),
                    task.getKeyword(),
                    task.getSortMode(),
                    run == null || run.getStatus() == null ? task.getStatus() : run.getStatus(),
                    summary.path("requestedVideoLimit").asInt(input.path("videoLimit").asInt(0)),
                    summary.path("processedVideos").asInt(0),
                    summary.path("failedVideos").asInt(0),
                    commentsCollected,
                    matchedComments,
                    engagementsCreated,
                    run == null ? null : run.getFailureCode(),
                    run == null ? null : run.getFailureMessage(),
                    task.getCreateTime() == null ? null : task.getCreateTime().toString(),
                    task.getUpdateTime() == null ? null : task.getUpdateTime().toString()));
        }
        return rows;
    }

    public List<RunTimelineEventDTO> eventsSince(Long runId, Long afterEventId) {
        LambdaQueryWrapper<AgentEventEntity> query = new LambdaQueryWrapper<AgentEventEntity>()
                .eq(AgentEventEntity::getRunId, runId)
                .orderByAsc(AgentEventEntity::getId);
        if (afterEventId != null && afterEventId > 0) {
            query.gt(AgentEventEntity::getId, afterEventId);
        }
        return eventMapper.selectList(query)
                .stream()
                .map(RunTimelineEventDTO::from)
                .toList();
    }

    public boolean isRunTerminal(Long runId) {
        AgentRunEntity run = requireRun(runId);
        return AgentRunStatus.parse(run.getStatus()).isTerminal();
    }

    public String runStatus(Long runId) {
        return requireRun(runId).getStatus();
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

    public List<RunTimelineEventDTO> events(Long runId) {
        return eventMapper.selectList(new LambdaQueryWrapper<AgentEventEntity>()
                        .eq(AgentEventEntity::getRunId, runId)
                        .orderByAsc(AgentEventEntity::getCreateTime)
                        .orderByAsc(AgentEventEntity::getId))
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

    private JsonNode parseSummary(LeadTaskEntity task) {
        if (task == null || task.getSummaryJson() == null || task.getSummaryJson().isBlank()) {
            return objectMapper.createObjectNode();
        }
        return parseJson(task.getSummaryJson());
    }

    private JsonNode parseJson(String raw) {
        if (raw == null || raw.isBlank()) {
            return objectMapper.createObjectNode();
        }
        try {
            return objectMapper.readTree(raw);
        } catch (Exception ignored) {
            return objectMapper.createObjectNode();
        }
    }

    private int sumVideoResultInt(JsonNode summary, String fieldName) {
        JsonNode videoResults = summary.path("videoResults");
        if (!videoResults.isArray()) {
            return 0;
        }
        int total = 0;
        for (JsonNode video : videoResults) {
            total += Math.max(0, video.path(fieldName).asInt(0));
        }
        return total;
    }

    private int countComments(Long taskId, boolean matchedOnly) {
        if (taskId == null) {
            return 0;
        }
        LambdaQueryWrapper<LeadCommentEntity> query = new LambdaQueryWrapper<LeadCommentEntity>()
                .eq(LeadCommentEntity::getTaskId, taskId);
        if (matchedOnly) {
            query.eq(LeadCommentEntity::getMatched, true);
        }
        Long count = commentMapper.selectCount(query);
        return count == null ? 0 : count.intValue();
    }

    private int countEngagements(Long taskId) {
        if (taskId == null) {
            return 0;
        }
        Long count = engagementMapper.selectCount(new LambdaQueryWrapper<LeadEngagementEntity>()
                .eq(LeadEngagementEntity::getTaskId, taskId));
        return count == null ? 0 : count.intValue();
    }
}
