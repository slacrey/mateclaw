package vip.mate.auth.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.junit.jupiter.api.Test;
import vip.mate.auth.model.UserEntity;
import vip.mate.auth.repository.UserMapper;
import vip.mate.exception.MateClawException;
import vip.mate.workspace.core.model.WorkspaceEntity;
import vip.mate.workspace.core.repository.WorkspaceMapper;
import vip.mate.workspace.core.repository.WorkspaceMemberMapper;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AccountEntitlementServiceTest {

    private final WorkspaceMapper workspaceMapper = mock(WorkspaceMapper.class);
    private final UserMapper userMapper = mock(UserMapper.class);
    private final WorkspaceMemberMapper workspaceMemberMapper = mock(WorkspaceMemberMapper.class);
    private final AccountEntitlementService service = new AccountEntitlementService(
            workspaceMapper, userMapper, workspaceMemberMapper);

    @Test
    void defaultMaxChildAccountsIsOne() {
        assertEquals(1, AccountEntitlementService.DEFAULT_MAX_CHILD_ACCOUNTS);
    }

    @Test
    void isExpiredOnlyWhenUserHasPastExpiry() {
        assertFalse(service.isExpired(null));
        assertFalse(service.isExpired(userWithExpiry(null)));
        assertFalse(service.isExpired(userWithExpiry(LocalDateTime.now().plusMinutes(1))));
        assertTrue(service.isExpired(userWithExpiry(LocalDateTime.now().minusMinutes(1))));
    }

    @Test
    void isExpiredWhenExpiryEqualsNow() {
        LocalDateTime now = LocalDateTime.of(2026, 5, 29, 13, 30);
        AccountEntitlementService service = serviceAt(now);

        assertTrue(service.isExpired(userWithExpiry(now)));
    }

    @Test
    void requireActiveRejectsExpiredUser() {
        MateClawException ex = assertThrows(MateClawException.class,
                () -> service.requireActive(userWithExpiry(LocalDateTime.now().minusMinutes(1))));

        assertEquals("err.account.expired", ex.getMsgKey());
        assertEquals(403, ex.getCode());
        assertEquals("账号已过期", ex.getMessage());
    }

    @Test
    void requireActiveAllowsPermanentAndFutureUsers() {
        assertDoesNotThrow(() -> service.requireActive(userWithExpiry(null)));
        assertDoesNotThrow(() -> service.requireActive(userWithExpiry(LocalDateTime.now().plusMinutes(1))));
    }

    @Test
    void resolveOwnerExpiryReturnsOwnerExpiry() {
        WorkspaceEntity workspace = new WorkspaceEntity();
        workspace.setId(10L);
        workspace.setOwnerId(42L);
        LocalDateTime expiry = LocalDateTime.now().plusDays(30);
        UserEntity owner = userWithExpiry(expiry);
        owner.setId(42L);
        when(workspaceMapper.selectById(10L)).thenReturn(workspace);
        when(userMapper.selectById(42L)).thenReturn(owner);

        assertEquals(expiry, service.resolveOwnerExpiry(10L));
    }

    @Test
    void resolveOwnerExpiryReturnsNullWhenWorkspaceMissing() {
        when(workspaceMapper.selectById(10L)).thenReturn(null);

        assertNull(service.resolveOwnerExpiry(10L));
        verify(userMapper, never()).selectById(any());
    }

    @Test
    void resolveOwnerExpiryReturnsNullWhenOwnerMissing() {
        WorkspaceEntity workspace = new WorkspaceEntity();
        workspace.setId(10L);
        workspace.setOwnerId(42L);
        when(workspaceMapper.selectById(10L)).thenReturn(workspace);
        when(userMapper.selectById(42L)).thenReturn(null);

        assertNull(service.resolveOwnerExpiry(10L));
    }

    @Test
    void assertCanAddSubAccountAllowsFirstChildAccount() {
        when(workspaceMemberMapper.selectCount(any(LambdaQueryWrapper.class))).thenReturn(0L);

        assertDoesNotThrow(() -> service.assertCanAddSubAccount(10L));
    }

    @Test
    void assertCanAddSubAccountRejectsWhenChildLimitReached() {
        when(workspaceMemberMapper.selectCount(any(LambdaQueryWrapper.class))).thenReturn(1L);

        MateClawException ex = assertThrows(MateClawException.class,
                () -> service.assertCanAddSubAccount(10L));

        assertEquals("err.workspace.member_limit_exceeded", ex.getMsgKey());
        assertEquals(409, ex.getCode());
        assertEquals("超过最大团队成员数量", ex.getMessage());
    }

    private static UserEntity userWithExpiry(LocalDateTime expiresAt) {
        UserEntity user = new UserEntity();
        user.setExpiresAt(expiresAt);
        return user;
    }

    private AccountEntitlementService serviceAt(LocalDateTime now) {
        return new AccountEntitlementService(workspaceMapper, userMapper, workspaceMemberMapper) {
            @Override
            protected LocalDateTime now() {
                return now;
            }
        };
    }
}
