package vip.mate.auth.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import vip.mate.auth.model.UserEntity;
import vip.mate.auth.repository.UserMapper;
import vip.mate.exception.MateClawException;
import vip.mate.workspace.core.model.WorkspaceEntity;
import vip.mate.workspace.core.model.WorkspaceMemberEntity;
import vip.mate.workspace.core.repository.WorkspaceMapper;
import vip.mate.workspace.core.repository.WorkspaceMemberMapper;

import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
public class AccountEntitlementService {

    public static final int DEFAULT_MAX_CHILD_ACCOUNTS = 1;

    private final WorkspaceMapper workspaceMapper;
    private final UserMapper userMapper;
    private final WorkspaceMemberMapper workspaceMemberMapper;

    public boolean isExpired(UserEntity user) {
        return user != null
                && user.getExpiresAt() != null
                && !user.getExpiresAt().isAfter(now());
    }

    protected LocalDateTime now() {
        return LocalDateTime.now();
    }

    public void requireActive(UserEntity user) {
        if (isExpired(user)) {
            throw new MateClawException("err.account.expired", 403, "账号已过期");
        }
    }

    public LocalDateTime resolveOwnerExpiry(Long workspaceId) {
        WorkspaceEntity workspace = workspaceMapper.selectById(workspaceId);
        if (workspace == null || workspace.getOwnerId() == null) {
            return null;
        }
        UserEntity owner = userMapper.selectById(workspace.getOwnerId());
        return owner != null ? owner.getExpiresAt() : null;
    }

    public void assertCanAddSubAccount(Long workspaceId) {
        Long childAccountCount = workspaceMemberMapper.selectCount(
                new LambdaQueryWrapper<WorkspaceMemberEntity>()
                        .eq(WorkspaceMemberEntity::getWorkspaceId, workspaceId)
                        .ne(WorkspaceMemberEntity::getRole, "owner"));
        if (childAccountCount >= DEFAULT_MAX_CHILD_ACCOUNTS) {
            throw new MateClawException(
                    "err.workspace.member_limit_exceeded",
                    409,
                    "超过最大团队成员数量");
        }
    }
}
