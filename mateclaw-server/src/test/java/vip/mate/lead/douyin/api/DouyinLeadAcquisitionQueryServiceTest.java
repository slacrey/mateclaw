package vip.mate.lead.douyin.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
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

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DouyinLeadAcquisitionQueryServiceTest {

    @Test
    void leadPoolBuildsCrossTaskRowsWithBatchLookups() {
        AgentRunMapper runMapper = mock(AgentRunMapper.class);
        AgentEventMapper eventMapper = mock(AgentEventMapper.class);
        LeadTaskMapper taskMapper = mock(LeadTaskMapper.class);
        LeadCommentMapper commentMapper = mock(LeadCommentMapper.class);
        LeadProfileMapper profileMapper = mock(LeadProfileMapper.class);
        LeadEngagementMapper engagementMapper = mock(LeadEngagementMapper.class);
        DouyinLeadAcquisitionQueryService service = new DouyinLeadAcquisitionQueryService(
                runMapper,
                eventMapper,
                taskMapper,
                commentMapper,
                profileMapper,
                engagementMapper,
                new ObjectMapper());

        LeadTaskEntity task = new LeadTaskEntity();
        task.setId(20L);
        task.setRunId(10L);
        task.setWorkspaceId(7L);
        task.setPlatform("douyin");
        task.setKeyword("易企秀");
        task.setSortMode("most_liked");
        task.setStatus("running");
        task.setCreateTime(LocalDateTime.parse("2026-06-10T16:00:00"));
        task.setUpdateTime(LocalDateTime.parse("2026-06-10T16:01:00"));

        AgentRunEntity run = new AgentRunEntity();
        run.setId(10L);
        run.setStatus("succeeded");

        LeadCommentEntity comment = new LeadCommentEntity();
        comment.setId(30L);
        comment.setTaskId(20L);
        comment.setRunId(10L);
        comment.setVideoKey("video-1");
        comment.setCommentKey("douyin-comment-1");
        comment.setAuthorName("霞姐一百岁");
        comment.setAuthorProfileUrl("https://www.douyin.com/user/abc");
        comment.setCommentText("慢出心脏病");
        comment.setMatched(true);
        comment.setMatchScore(1.0d);
        comment.setMatchReason("exact_text_contains");
        comment.setCreateTime(LocalDateTime.parse("2026-06-10T16:02:00"));

        LeadEngagementEntity engagement = new LeadEngagementEntity();
        engagement.setId(40L);
        engagement.setTaskId(20L);
        engagement.setRunId(10L);
        engagement.setCommentId(30L);
        engagement.setProfileId(50L);
        engagement.setEngagementType("send_dm");
        engagement.setStatus("succeeded");
        engagement.setDraftText("你好");
        engagement.setUpdateTime(LocalDateTime.parse("2026-06-10T16:03:00"));

        LeadProfileEntity profile = new LeadProfileEntity();
        profile.setId(50L);
        profile.setProfileUrl("https://www.douyin.com/user/abc");
        profile.setDisplayName("霞姐一百岁");

        when(taskMapper.selectList(any())).thenReturn(List.of(task));
        when(runMapper.selectBatchIds(any())).thenReturn(List.of(run));
        when(commentMapper.selectList(any())).thenReturn(List.of(comment));
        when(engagementMapper.selectList(any())).thenReturn(List.of(engagement));
        when(profileMapper.selectBatchIds(any())).thenReturn(List.of(profile));

        List<DouyinLeadPoolItem> rows = service.leadPool(7L, 50, "sent", "易企秀");

        assertThat(rows).hasSize(1);
        DouyinLeadPoolItem row = rows.getFirst();
        assertThat(row.runId()).isEqualTo("10");
        assertThat(row.taskId()).isEqualTo("20");
        assertThat(row.keyword()).isEqualTo("易企秀");
        assertThat(row.runStatus()).isEqualTo("succeeded");
        assertThat(row.commentId()).isEqualTo("30");
        assertThat(row.profileUrl()).isEqualTo("https://www.douyin.com/user/abc");
        assertThat(row.displayName()).isEqualTo("霞姐一百岁");
        assertThat(row.sent()).isTrue();

        verify(runMapper).selectBatchIds(any());
        verify(profileMapper).selectBatchIds(any());
        verify(runMapper, never()).selectById(any());
        verify(profileMapper, never()).selectById(any());
        verify(engagementMapper, never()).selectOne(any());
    }
}
