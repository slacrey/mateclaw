package vip.mate.lead.douyin.api;

import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import vip.mate.auth.model.UserEntity;
import vip.mate.auth.service.AuthService;
import vip.mate.common.result.R;
import vip.mate.exception.MateClawException;
import vip.mate.lead.douyin.DouyinLeadAcquisitionRunService;
import vip.mate.lead.douyin.model.DouyinLeadAcquisitionInput;
import vip.mate.workspace.core.annotation.RequireWorkspaceRole;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/lead-acquisition")
public class DouyinLeadAcquisitionController {

    private final DouyinLeadAcquisitionRunService runService;
    private final DouyinLeadAcquisitionQueryService queryService;
    private final AuthService authService;

    public DouyinLeadAcquisitionController(DouyinLeadAcquisitionRunService runService,
                                           DouyinLeadAcquisitionQueryService queryService,
                                           AuthService authService) {
        this.runService = runService;
        this.queryService = queryService;
        this.authService = authService;
    }

    @PostMapping("/douyin/runs")
    @RequireWorkspaceRole("member")
    public R<DouyinLeadAcquisitionRunResponse> start(
            @RequestBody(required = false) DouyinLeadAcquisitionStartRequest request,
            @RequestHeader(value = "X-Workspace-Id", required = false) Long workspaceId,
            Authentication auth) {
        UserEntity user = requireUser(auth);
        DouyinLeadAcquisitionInput input = normalizeStartRequest(request);
        return R.ok(runService.runSync(workspaceId == null ? 1L : workspaceId, user.getId(), input, queryService));
    }

    @GetMapping("/runs/{runId}")
    @RequireWorkspaceRole("viewer")
    public R<DouyinLeadAcquisitionRunResponse> run(@PathVariable Long runId) {
        return R.ok(queryService.byRun(runId));
    }

    @PostMapping("/runs/{runId}/cancel")
    @RequireWorkspaceRole("member")
    public R<Map<String, Object>> cancel(@PathVariable Long runId) {
        runService.cancel(runId);
        return R.ok(Map.of("cancelled", true, "runId", String.valueOf(runId)));
    }

    @GetMapping("/tasks/{taskId}")
    @RequireWorkspaceRole("viewer")
    public R<DouyinLeadAcquisitionRunResponse> task(@PathVariable Long taskId) {
        return R.ok(queryService.byTask(taskId));
    }

    @GetMapping("/tasks/{taskId}/comments")
    @RequireWorkspaceRole("viewer")
    public R<List<LeadCommentDTO>> comments(@PathVariable Long taskId) {
        return R.ok(queryService.comments(taskId));
    }

    @GetMapping("/tasks/{taskId}/profiles")
    @RequireWorkspaceRole("viewer")
    public R<List<LeadProfileDTO>> profiles(@PathVariable Long taskId) {
        return R.ok(queryService.profiles(taskId));
    }

    @GetMapping("/tasks/{taskId}/engagements")
    @RequireWorkspaceRole("viewer")
    public R<List<LeadEngagementDTO>> engagements(@PathVariable Long taskId) {
        return R.ok(queryService.engagements(taskId));
    }

    private UserEntity requireUser(Authentication auth) {
        if (auth == null || auth.getName() == null) {
            throw new MateClawException("err.auth.unauthenticated", "Authentication required");
        }
        UserEntity user = authService.findByUsername(auth.getName());
        if (user == null) {
            throw new MateClawException("err.auth.user_not_found", "Authenticated user not found");
        }
        return user;
    }

    private DouyinLeadAcquisitionInput normalizeStartRequest(DouyinLeadAcquisitionStartRequest request) {
        if (request == null) {
            throw new MateClawException("err.lead.douyin.keyword_required", "Douyin keyword is required");
        }
        return request.normalized();
    }
}
