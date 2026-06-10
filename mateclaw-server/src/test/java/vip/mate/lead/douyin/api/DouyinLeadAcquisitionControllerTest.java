package vip.mate.lead.douyin.api;

import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import vip.mate.auth.model.UserEntity;
import vip.mate.auth.service.AuthService;
import vip.mate.lead.douyin.DouyinLeadAcquisitionRunService;
import vip.mate.lead.douyin.model.DouyinLeadAcquisitionInput;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DouyinLeadAcquisitionControllerTest {

    @Test
    void startEndpointReturnsAsyncRunHandleWithoutWaitingForCompletion() {
        DouyinLeadAcquisitionRunService runService = mock(DouyinLeadAcquisitionRunService.class);
        DouyinLeadAcquisitionQueryService queryService = mock(DouyinLeadAcquisitionQueryService.class);
        DouyinLeadAcquisitionEventStreamService eventStreamService = mock(DouyinLeadAcquisitionEventStreamService.class);
        AuthService authService = mock(AuthService.class);
        DouyinLeadAcquisitionController controller = new DouyinLeadAcquisitionController(
                runService,
                queryService,
                eventStreamService,
                authService);
        UserEntity user = new UserEntity();
        user.setId(9L);
        when(authService.findByUsername("alice")).thenReturn(user);
        DouyinLeadAcquisitionRunResponse started = DouyinLeadAcquisitionRunResponse.started(10L, 20L, "running");
        when(runService.start(eq(7L), eq(9L), any(DouyinLeadAcquisitionInput.class)))
                .thenReturn(started);

        var response = controller.start(
                new DouyinLeadAcquisitionStartRequest(
                        "openclaw",
                        "most_liked",
                        1,
                        "他叫木马 及 对于99%的人用豆包就行了。",
                        "你好",
                        false),
                7L,
                new TestingAuthenticationToken("alice", "pw"));

        assertThat(response.getData().status()).isEqualTo("running");
        assertThat(response.getData().runId()).isEqualTo("10");
        assertThat(response.getData().taskId()).isEqualTo("20");
        assertThat(response.getData().commentsCollected()).isZero();
        assertThat(response.getData().events()).isEmpty();
        verify(runService).start(eq(7L), eq(9L), any(DouyinLeadAcquisitionInput.class));
        verify(runService, never()).runSync(any(), any(), any(), any());
    }

    @Test
    void streamEndpointDelegatesCursorInputsToEventStreamService() {
        DouyinLeadAcquisitionRunService runService = mock(DouyinLeadAcquisitionRunService.class);
        DouyinLeadAcquisitionQueryService queryService = mock(DouyinLeadAcquisitionQueryService.class);
        DouyinLeadAcquisitionEventStreamService eventStreamService = mock(DouyinLeadAcquisitionEventStreamService.class);
        AuthService authService = mock(AuthService.class);
        DouyinLeadAcquisitionController controller = new DouyinLeadAcquisitionController(
                runService,
                queryService,
                eventStreamService,
                authService);
        SseEmitter emitter = new SseEmitter();
        when(eventStreamService.stream(eq(10L), eq("123"), eq(456L))).thenReturn(emitter);

        SseEmitter response = controller.streamEvents(10L, 456L, "123");

        assertThat(response).isSameAs(emitter);
        verify(eventStreamService).stream(eq(10L), eq("123"), eq(456L));
    }

    @Test
    void recentRunsEndpointReturnsDouyinTaskHistoryForWorkspace() {
        DouyinLeadAcquisitionRunService runService = mock(DouyinLeadAcquisitionRunService.class);
        DouyinLeadAcquisitionQueryService queryService = mock(DouyinLeadAcquisitionQueryService.class);
        DouyinLeadAcquisitionEventStreamService eventStreamService = mock(DouyinLeadAcquisitionEventStreamService.class);
        AuthService authService = mock(AuthService.class);
        DouyinLeadAcquisitionController controller = new DouyinLeadAcquisitionController(
                runService,
                queryService,
                eventStreamService,
                authService);
        DouyinLeadRunListItem item = new DouyinLeadRunListItem(
                "10",
                "20",
                "易企秀",
                "most_liked",
                "succeeded",
                2,
                2,
                0,
                65,
                1,
                1,
                null,
                null,
                "2026-06-10T16:00:00",
                "2026-06-10T16:02:00");
        when(queryService.recentRuns(eq(7L), eq(12))).thenReturn(List.of(item));

        var response = controller.recentRuns(7L, 12);

        assertThat(response.getData()).containsExactly(item);
        verify(queryService).recentRuns(eq(7L), eq(12));
    }
}
