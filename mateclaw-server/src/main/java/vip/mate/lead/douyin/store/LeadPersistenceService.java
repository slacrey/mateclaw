package vip.mate.lead.douyin.store;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vip.mate.lead.douyin.model.CommentCollectionResult;
import vip.mate.lead.douyin.model.CommentMatchResult;
import vip.mate.lead.douyin.model.DouyinCommentItem;
import vip.mate.lead.douyin.model.DouyinLeadAcquisitionInput;
import vip.mate.lead.douyin.model.DouyinLeadRunSummary;
import vip.mate.lead.douyin.model.EngagementResult;
import vip.mate.os.run.model.LeadCommentEntity;
import vip.mate.os.run.model.LeadEngagementEntity;
import vip.mate.os.run.model.LeadProfileEntity;
import vip.mate.os.run.model.LeadTaskEntity;
import vip.mate.os.run.repository.LeadCommentMapper;
import vip.mate.os.run.repository.LeadEngagementMapper;
import vip.mate.os.run.repository.LeadProfileMapper;
import vip.mate.os.run.repository.LeadTaskMapper;

import java.util.List;
import java.util.Map;

@Service
public class LeadPersistenceService {

    private final LeadTaskMapper taskMapper;
    private final LeadCommentMapper commentMapper;
    private final LeadProfileMapper profileMapper;
    private final LeadEngagementMapper engagementMapper;
    private final ObjectMapper mapper;

    public LeadPersistenceService(LeadTaskMapper taskMapper,
                                  LeadCommentMapper commentMapper,
                                  LeadProfileMapper profileMapper,
                                  LeadEngagementMapper engagementMapper,
                                  ObjectMapper mapper) {
        this.taskMapper = taskMapper;
        this.commentMapper = commentMapper;
        this.profileMapper = profileMapper;
        this.engagementMapper = engagementMapper;
        this.mapper = mapper;
    }

    @Transactional
    public LeadTaskEntity createTask(Long runId, Long workspaceId, DouyinLeadAcquisitionInput input) {
        LeadTaskEntity task = new LeadTaskEntity();
        task.setRunId(runId);
        task.setWorkspaceId(workspaceId);
        task.setPlatform("douyin");
        task.setKeyword(input.keyword());
        task.setSortMode(input.sort());
        task.setStatus("running");
        task.setInputJson(json(input));
        taskMapper.insert(task);
        return task;
    }

    @Transactional
    public void saveComments(Long taskId, Long runId, List<DouyinCommentItem> comments) {
        if (comments == null) {
            return;
        }
        for (DouyinCommentItem item : comments) {
            LeadCommentEntity row = new LeadCommentEntity();
            row.setTaskId(taskId);
            row.setRunId(runId);
            row.setVideoKey(item.videoKey());
            row.setCommentKey(item.commentKey());
            row.setParentCommentKey(item.parentCommentKey());
            row.setAuthorName(item.authorName());
            row.setAuthorProfileUrl(item.authorProfileUrl());
            row.setAuthorAvatarUrl(item.authorAvatarUrl());
            row.setCommentText(item.text());
            row.setLikeCount(item.likeCount());
            row.setReplyCount(item.replyCount());
            row.setMatched(false);
            row.setMetadataJson(json(item.metadata()));
            try {
                commentMapper.insert(row);
            } catch (RuntimeException duplicate) {
                // The V133 unique index makes replays idempotent.
            }
        }
    }

    @Transactional
    public void markMatches(Long taskId, List<CommentMatchResult> matches) {
        if (matches == null) {
            return;
        }
        for (CommentMatchResult match : matches) {
            DouyinCommentItem item = match.comment();
            if (item == null) {
                continue;
            }
            List<LeadCommentEntity> rows = commentMapper.selectList(
                    new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<LeadCommentEntity>()
                            .eq(LeadCommentEntity::getTaskId, taskId)
                            .eq(LeadCommentEntity::getCommentKey, item.commentKey()));
            for (LeadCommentEntity row : rows) {
                row.setMatched(match.matched());
                row.setMatchScore(match.score());
                row.setMatchReason(match.reason());
                commentMapper.updateById(row);
            }
        }
    }

    @Transactional
    public LeadProfileEntity saveProfile(Long taskId, Long runId, EngagementResult result) {
        LeadProfileEntity profile = new LeadProfileEntity();
        profile.setTaskId(taskId);
        profile.setRunId(runId);
        profile.setPlatform("douyin");
        profile.setProfileUrl(result.profileUrl() == null ? "" : result.profileUrl());
        profile.setDisplayName(result.author());
        profile.setRawJson(json(result));
        profileMapper.insert(profile);
        return profile;
    }

    @Transactional
    public LeadEngagementEntity saveEngagement(Long taskId, Long runId, Long profileId,
                                               Long commentId, String type, EngagementResult result,
                                               String draftText) {
        LeadEngagementEntity engagement = new LeadEngagementEntity();
        engagement.setTaskId(taskId);
        engagement.setRunId(runId);
        engagement.setProfileId(profileId);
        engagement.setCommentId(commentId);
        engagement.setEngagementType(type);
        engagement.setStatus(result.status());
        engagement.setDraftText(draftText);
        engagement.setFailureCode(result.failureCode());
        engagement.setFailureMessage(result.failureMessage());
        engagement.setEvidenceRef(result.profileUrl());
        engagementMapper.insert(engagement);
        return engagement;
    }

    @Transactional
    public void completeTask(Long taskId, String status, CommentCollectionResult collection,
                             List<CommentMatchResult> matches, List<EngagementResult> engagements) {
        completeTask(taskId, status, new DouyinLeadRunSummary(
                1,
                collection == null ? 0 : 1,
                collection != null && collection.complete() ? 1 : 0,
                collection == null || collection.complete() ? 0 : 1,
                collection == null ? 0 : collection.comments().size(),
                collection == null ? 0 : collection.declaredCommentCount(),
                collection == null || collection.declaredCommentCount() <= 0
                        ? 0
                        : Math.max(0, collection.declaredCommentCount() - collection.comments().size()),
                collection == null || collection.declaredCommentCount() <= 0
                        ? 0.0d
                        : Math.min(1.0d, collection.comments().size() / (double) collection.declaredCommentCount()),
                matches == null ? 0 : matches.size(),
                engagements == null ? 0 : engagements.size(),
                List.of(Map.of(
                        "videoIndex", 0,
                        "commentsCollected", collection == null ? 0 : collection.comments().size(),
                        "declaredCommentCount", collection == null ? 0 : collection.declaredCommentCount(),
                        "collectionComplete", collection != null && collection.complete(),
                        "stopReason", collection == null ? "" : collection.stopReason(),
                        "matchedComments", matches == null ? 0 : matches.size(),
                        "engagementsCreated", engagements == null ? 0 : engagements.size()))));
    }

    @Transactional
    public void completeTask(Long taskId, String status, DouyinLeadRunSummary summary) {
        LeadTaskEntity task = taskMapper.selectById(taskId);
        if (task == null) {
            return;
        }
        task.setStatus(status);
        task.setSummaryJson(json(summary == null ? DouyinLeadRunSummary.empty(0) : summary));
        taskMapper.updateById(task);
    }

    public Long findCommentId(Long taskId, String commentKey) {
        LeadCommentEntity row = commentMapper.selectOne(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<LeadCommentEntity>()
                        .eq(LeadCommentEntity::getTaskId, taskId)
                        .eq(LeadCommentEntity::getCommentKey, commentKey)
                        .last("LIMIT 1"));
        return row == null ? null : row.getId();
    }

    private String json(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            return "{\"serialization_error\":\"" + e.getMessage().replace("\"", "'") + "\"}";
        }
    }
}
