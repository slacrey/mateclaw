package vip.mate.lead.douyin.api;

import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.TestingAuthenticationToken;
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
    void startEndpointRunsFullV1WorkflowSynchronously() {
        DouyinLeadAcquisitionRunService runService = mock(DouyinLeadAcquisitionRunService.class);
        DouyinLeadAcquisitionQueryService queryService = mock(DouyinLeadAcquisitionQueryService.class);
        AuthService authService = mock(AuthService.class);
        DouyinLeadAcquisitionController controller = new DouyinLeadAcquisitionController(
                runService,
                queryService,
                authService);
        UserEntity user = new UserEntity();
        user.setId(9L);
        when(authService.findByUsername("alice")).thenReturn(user);
        DouyinLeadAcquisitionRunResponse terminal = new DouyinLeadAcquisitionRunResponse(
                "10",
                "20",
                "succeeded",
                151,
                1,
                List.of(),
                List.of(),
                List.of(),
                List.of());
        when(runService.runSync(eq(7L), eq(9L), any(DouyinLeadAcquisitionInput.class), eq(queryService)))
                .thenReturn(terminal);

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

        assertThat(response.getData().status()).isEqualTo("succeeded");
        assertThat(response.getData().runId()).isEqualTo("10");
        verify(runService).runSync(eq(7L), eq(9L), any(DouyinLeadAcquisitionInput.class), eq(queryService));
        verify(runService, never()).start(any(), any(), any());
    }
}
