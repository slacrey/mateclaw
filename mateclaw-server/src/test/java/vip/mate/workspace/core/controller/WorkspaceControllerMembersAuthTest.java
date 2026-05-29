package vip.mate.workspace.core.controller;

import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.Authentication;
import vip.mate.auth.model.UserEntity;
import vip.mate.auth.service.AccountEntitlementService;
import vip.mate.auth.service.AuthService;
import vip.mate.common.result.R;
import vip.mate.exception.MateClawException;
import vip.mate.workspace.core.model.WorkspaceMemberEntity;
import vip.mate.workspace.core.service.WorkspaceService;

import java.time.LocalDateTime;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;

class WorkspaceControllerMembersAuthTest {

    @Test
    void listMembersRequiresViewerPermissionForNonGlobalAdmin() throws Exception {
        WorkspaceService workspaceService = mock(WorkspaceService.class);
        AuthService authService = mock(AuthService.class);
        AccountEntitlementService entitlementService = mock(AccountEntitlementService.class);
        WorkspaceController controller = new WorkspaceController(workspaceService, authService, entitlementService);
        UserEntity user = new UserEntity();
        user.setId(42L);
        user.setUsername("alice");
        user.setRole("user");
        when(authService.findByUsername("alice")).thenReturn(user);
        when(workspaceService.listMembers(7L)).thenReturn(List.of());

        invokeListMembers(controller, 7L, new TestingAuthenticationToken("alice", "pw"));

        verify(workspaceService).requirePermission(7L, 42L, "viewer");
        verify(workspaceService).listMembers(7L);
    }

    @Test
    void listMembersLetsGlobalAdminBypassWorkspaceMembership() throws Exception {
        WorkspaceService workspaceService = mock(WorkspaceService.class);
        AuthService authService = mock(AuthService.class);
        AccountEntitlementService entitlementService = mock(AccountEntitlementService.class);
        WorkspaceController controller = new WorkspaceController(workspaceService, authService, entitlementService);
        UserEntity user = new UserEntity();
        user.setId(1L);
        user.setUsername("admin");
        user.setRole("admin");
        when(authService.findByUsername("admin")).thenReturn(user);
        when(workspaceService.listMembers(7L)).thenReturn(List.of());

        invokeListMembers(controller, 7L, new TestingAuthenticationToken("admin", "pw"));

        verify(workspaceService, never()).requirePermission(anyLong(), anyLong(), anyString());
        verify(workspaceService).listMembers(7L);
    }

    @Test
    void addMemberCreatesChildAccountWithOwnerExpiry() {
        WorkspaceService workspaceService = mock(WorkspaceService.class);
        AuthService authService = mock(AuthService.class);
        AccountEntitlementService entitlementService = mock(AccountEntitlementService.class);
        WorkspaceController controller = new WorkspaceController(workspaceService, authService, entitlementService);
        UserEntity admin = new UserEntity();
        admin.setId(42L);
        admin.setUsername("alice");
        admin.setRole("user");
        when(authService.findByUsername("alice")).thenReturn(admin);
        when(authService.findByUsername("new-child")).thenReturn(null);
        LocalDateTime ownerExpiry = LocalDateTime.of(2026, 7, 1, 12, 0);
        when(entitlementService.resolveOwnerExpiry(7L)).thenReturn(ownerExpiry);
        UserEntity created = new UserEntity();
        created.setId(99L);
        created.setUsername("new-child");
        when(authService.createUser(any(UserEntity.class))).thenReturn(created);
        WorkspaceMemberEntity member = new WorkspaceMemberEntity();
        member.setWorkspaceId(7L);
        member.setUserId(99L);
        member.setRole("member");
        when(workspaceService.addMember(7L, 99L, "member")).thenReturn(member);

        R<WorkspaceMemberEntity> response = controller.addMember(
                7L,
                Map.of("username", "new-child", "password", "secret"),
                new TestingAuthenticationToken("alice", "pw"));

        assertEquals(member, response.getData());
        verify(workspaceService).requirePermission(7L, 42L, "admin");
        verify(entitlementService).assertCanAddSubAccount(7L);
        verify(entitlementService).resolveOwnerExpiry(7L);
        verify(authService).createUser(argThat(user ->
                "new-child".equals(user.getUsername())
                        && "secret".equals(user.getPassword())
                        && "new-child".equals(user.getNickname())
                        && ownerExpiry.equals(user.getExpiresAt())));
        verify(workspaceService).addMember(7L, 99L, "member");
    }

    @Test
    void addMemberRejectsExistingUsername() {
        WorkspaceService workspaceService = mock(WorkspaceService.class);
        AuthService authService = mock(AuthService.class);
        AccountEntitlementService entitlementService = mock(AccountEntitlementService.class);
        WorkspaceController controller = new WorkspaceController(workspaceService, authService, entitlementService);
        UserEntity admin = new UserEntity();
        admin.setId(42L);
        admin.setUsername("alice");
        admin.setRole("user");
        UserEntity existing = new UserEntity();
        existing.setId(99L);
        existing.setUsername("existing");
        when(authService.findByUsername("alice")).thenReturn(admin);
        when(authService.findByUsername("existing")).thenReturn(existing);

        MateClawException ex = assertThrows(MateClawException.class, () -> controller.addMember(
                7L,
                Map.of("username", "existing", "password", "ignored"),
                new TestingAuthenticationToken("alice", "pw")));

        assertEquals(409, ex.getCode());
        assertEquals("err.workspace.existing_user_not_allowed", ex.getMsgKey());
        verify(workspaceService).requirePermission(7L, 42L, "admin");
        verify(authService, never()).createUser(any(UserEntity.class));
        verify(workspaceService, never()).addMember(anyLong(), anyLong(), anyString());
    }

    @Test
    void addMemberRejectsLimitBeforeCreatingChildAccount() {
        WorkspaceService workspaceService = mock(WorkspaceService.class);
        AuthService authService = mock(AuthService.class);
        AccountEntitlementService entitlementService = mock(AccountEntitlementService.class);
        WorkspaceController controller = new WorkspaceController(workspaceService, authService, entitlementService);
        UserEntity admin = new UserEntity();
        admin.setId(42L);
        admin.setUsername("alice");
        admin.setRole("user");
        when(authService.findByUsername("alice")).thenReturn(admin);
        when(authService.findByUsername("new-child")).thenReturn(null);
        MateClawException limitExceeded = new MateClawException(
                "err.workspace.member_limit_exceeded", 409, "超过最大团队成员数量");
        doThrow(limitExceeded).when(entitlementService).assertCanAddSubAccount(7L);

        MateClawException ex = assertThrows(MateClawException.class, () -> controller.addMember(
                7L,
                Map.of("username", "new-child", "password", "secret"),
                new TestingAuthenticationToken("alice", "pw")));

        assertEquals(limitExceeded, ex);
        verify(workspaceService).requirePermission(7L, 42L, "admin");
        verify(entitlementService).assertCanAddSubAccount(7L);
        verify(entitlementService, never()).resolveOwnerExpiry(anyLong());
        verify(authService, never()).createUser(any(UserEntity.class));
        verify(workspaceService, never()).addMember(anyLong(), anyLong(), anyString());
    }

    @Test
    void addMemberRejectsUserIdPath() {
        WorkspaceService workspaceService = mock(WorkspaceService.class);
        AuthService authService = mock(AuthService.class);
        AccountEntitlementService entitlementService = mock(AccountEntitlementService.class);
        WorkspaceController controller = new WorkspaceController(workspaceService, authService, entitlementService);
        UserEntity admin = new UserEntity();
        admin.setId(42L);
        admin.setUsername("alice");
        admin.setRole("user");
        when(authService.findByUsername("alice")).thenReturn(admin);

        MateClawException ex = assertThrows(MateClawException.class, () -> controller.addMember(
                7L,
                Map.of("userId", 99L),
                new TestingAuthenticationToken("alice", "pw")));

        assertEquals(409, ex.getCode());
        assertEquals("err.workspace.existing_user_not_allowed", ex.getMsgKey());
        assertEquals("账号已存在，请使用新手机号或用户名创建子账号", ex.getMessage());
        verify(workspaceService).requirePermission(7L, 42L, "admin");
        verify(workspaceService, never()).addMember(anyLong(), anyLong(), anyString());
    }

    private void invokeListMembers(WorkspaceController controller, Long workspaceId,
                                   Authentication auth) throws Exception {
        Method method = WorkspaceController.class.getMethod("listMembers", Long.class, Authentication.class);
        assertNotNull(method);
        method.invoke(controller, workspaceId, auth);
    }
}
