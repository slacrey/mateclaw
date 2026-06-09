# Account Expiry and Team Limits Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Add account expiry, dedicated team workspaces for registered users, inherited child-account expiry, a one-child default team limit, and frontend renewal/contact UI.

**Architecture:** Store expiry on `mate_user.expires_at` with `null` meaning permanent. Treat `mate_workspace` as the team boundary: registration creates a user-owned workspace, member creation creates child users that inherit owner expiry, and a small entitlement service centralizes expiry and team-limit rules. The frontend owns account status in a Pinia store, renders a header badge, blocks expired accounts with a non-dismissible modal, and shows a QR modal when team limits are exceeded.

**Tech Stack:** Spring Boot, Spring Security filters, MyBatis-Plus, Flyway SQL migrations for H2/MySQL, JUnit 5/Mockito, Vue 3, Pinia, Vue I18n, Axios, Vite.

---

## Preconditions

- Work from a dedicated worktree, not directly on `dev`.
- Current highest migration version is `V129`; use `V130__account_expiry.sql` in both H2 and MySQL migration folders.
- `pnpm build` currently fails because `../scripts/check-snowflake-precision.sh` is missing. Use `pnpm exec vue-tsc --noEmit` and direct `node --max-old-space-size=6144 ./node_modules/vite/bin/vite.js build` for frontend verification.
- Add a static QR placeholder at `mateclaw-ui/public/business-qr.png` only if a real asset is available. If not, create the UI to reference `/business-qr.png` and note that the asset must be supplied before release.

---

### Task 1: Add Account Expiry Schema and Model Field

**Files:**
- Create: `mateclaw-server/src/main/resources/db/migration/h2/V130__account_expiry.sql`
- Create: `mateclaw-server/src/main/resources/db/migration/mysql/V130__account_expiry.sql`
- Modify: `mateclaw-server/src/main/resources/db/schema.sql`
- Modify: `mateclaw-server/src/main/resources/db/schema-mysql.sql`
- Modify: `mateclaw-server/src/main/java/vip/mate/auth/model/UserEntity.java`
- Test: `mateclaw-server/src/test/java/vip/mate/auth/service/AuthServiceRegisterTest.java`

**Step 1: Write the failing model-oriented test**

In `AuthServiceRegisterTest`, add a test that will later verify registration persists an expiry:

```java
@Test
void registerSetsThirtyDayExpiry() {
    RegisterRequest request = validRequest();
    when(userMapper.selectCount(any(LambdaQueryWrapper.class))).thenReturn(0L);
    when(passwordEncoder.encode("pass1234")).thenReturn("$2a$hash");
    doAnswer(invocation -> {
        UserEntity user = invocation.getArgument(0);
        user.setId(99L);
        return 1;
    }).when(userMapper).insert(any(UserEntity.class));

    LocalDateTime before = LocalDateTime.now().plusDays(30).minusSeconds(5);
    authService.register(request);
    LocalDateTime after = LocalDateTime.now().plusDays(30).plusSeconds(5);

    ArgumentCaptor<UserEntity> userCaptor = ArgumentCaptor.forClass(UserEntity.class);
    verify(userMapper).insert(userCaptor.capture());
    assertNotNull(userCaptor.getValue().getExpiresAt());
    assertFalse(userCaptor.getValue().getExpiresAt().isBefore(before));
    assertFalse(userCaptor.getValue().getExpiresAt().isAfter(after));
}
```

Add imports:

```java
import java.time.LocalDateTime;
import static org.junit.jupiter.api.Assertions.assertFalse;
```

**Step 2: Run the test to verify it fails**

Run:

```bash
mvn -pl mateclaw-server -Dtest=AuthServiceRegisterTest#registerSetsThirtyDayExpiry test
```

Expected: compile failure because `UserEntity.getExpiresAt()` does not exist.

**Step 3: Add migrations**

Create `mateclaw-server/src/main/resources/db/migration/h2/V130__account_expiry.sql`:

```sql
ALTER TABLE mate_user ADD COLUMN IF NOT EXISTS expires_at TIMESTAMP;
```

Create `mateclaw-server/src/main/resources/db/migration/mysql/V130__account_expiry.sql`:

```sql
SET @c := (
  SELECT COUNT(*)
  FROM INFORMATION_SCHEMA.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE()
    AND TABLE_NAME = 'mate_user'
    AND COLUMN_NAME = 'expires_at'
);
SET @s := IF(@c = 0, 'ALTER TABLE mate_user ADD COLUMN expires_at DATETIME NULL', 'SELECT 1');
PREPARE stmt FROM @s;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
```

**Step 4: Update baseline schemas**

In both schema files, add `expires_at` after `enabled`:

H2:

```sql
    expires_at  DATETIME,
```

MySQL:

```sql
    expires_at  DATETIME     DEFAULT NULL,
```

**Step 5: Update `UserEntity`**

Add after `enabled`:

```java
/** 账号有效期；null 表示永久有效 */
private LocalDateTime expiresAt;
```

**Step 6: Run the test again**

Run:

```bash
mvn -pl mateclaw-server -Dtest=AuthServiceRegisterTest#registerSetsThirtyDayExpiry test
```

Expected: fail because `AuthService.register()` does not set expiry yet.

**Step 7: Do not commit yet**

This task intentionally leaves the failing behavior test for Task 3.

---

### Task 2: Add Account Entitlement Service

**Files:**
- Create: `mateclaw-server/src/main/java/vip/mate/auth/service/AccountEntitlementService.java`
- Create: `mateclaw-server/src/test/java/vip/mate/auth/service/AccountEntitlementServiceTest.java`
- Later modify: `mateclaw-server/src/main/resources/messages.properties`
- Later modify: `mateclaw-server/src/main/resources/messages_en.properties`

**Step 1: Write the failing service tests**

Create `AccountEntitlementServiceTest.java`:

```java
package vip.mate.auth.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.junit.jupiter.api.Test;
import vip.mate.auth.model.UserEntity;
import vip.mate.auth.repository.UserMapper;
import vip.mate.exception.MateClawException;
import vip.mate.workspace.core.model.WorkspaceEntity;
import vip.mate.workspace.core.model.WorkspaceMemberEntity;
import vip.mate.workspace.core.repository.WorkspaceMapper;
import vip.mate.workspace.core.repository.WorkspaceMemberMapper;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class AccountEntitlementServiceTest {

    private final UserMapper userMapper = mock(UserMapper.class);
    private final WorkspaceMapper workspaceMapper = mock(WorkspaceMapper.class);
    private final WorkspaceMemberMapper memberMapper = mock(WorkspaceMemberMapper.class);
    private final AccountEntitlementService service =
            new AccountEntitlementService(userMapper, workspaceMapper, memberMapper);

    @Test
    void nullExpiryIsActive() {
        UserEntity user = new UserEntity();
        user.setExpiresAt(null);

        assertFalse(service.isExpired(user));
    }

    @Test
    void pastExpiryIsExpired() {
        UserEntity user = new UserEntity();
        user.setExpiresAt(LocalDateTime.now().minusSeconds(1));

        assertTrue(service.isExpired(user));
    }

    @Test
    void requireActiveRejectsExpiredUser() {
        UserEntity user = new UserEntity();
        user.setExpiresAt(LocalDateTime.now().minusDays(1));

        MateClawException ex = assertThrows(MateClawException.class, () -> service.requireActive(user));

        assertEquals("err.account.expired", ex.getMsgKey());
        assertEquals(403, ex.getCode());
    }

    @Test
    void resolveOwnerExpiryReturnsWorkspaceOwnersExpiry() {
        WorkspaceEntity ws = new WorkspaceEntity();
        ws.setId(7L);
        ws.setOwnerId(42L);
        LocalDateTime expiresAt = LocalDateTime.now().plusDays(10);
        UserEntity owner = new UserEntity();
        owner.setId(42L);
        owner.setExpiresAt(expiresAt);
        when(workspaceMapper.selectById(7L)).thenReturn(ws);
        when(userMapper.selectById(42L)).thenReturn(owner);

        assertEquals(expiresAt, service.resolveOwnerExpiry(7L));
    }

    @Test
    void assertCanAddSubAccountRejectsWhenOneNonOwnerMemberExists() {
        when(memberMapper.selectCount(any(LambdaQueryWrapper.class))).thenReturn(1L);

        MateClawException ex = assertThrows(MateClawException.class,
                () -> service.assertCanAddSubAccount(7L));

        assertEquals("err.workspace.member_limit_exceeded", ex.getMsgKey());
        assertEquals(409, ex.getCode());
    }

    @Test
    void assertCanAddSubAccountAllowsEmptyTeam() {
        when(memberMapper.selectCount(any(LambdaQueryWrapper.class))).thenReturn(0L);

        assertDoesNotThrow(() -> service.assertCanAddSubAccount(7L));
    }
}
```

**Step 2: Run tests to verify failure**

Run:

```bash
mvn -pl mateclaw-server -Dtest=AccountEntitlementServiceTest test
```

Expected: compile failure because the service does not exist.

**Step 3: Implement the service**

Create `AccountEntitlementService.java`:

```java
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

    private final UserMapper userMapper;
    private final WorkspaceMapper workspaceMapper;
    private final WorkspaceMemberMapper memberMapper;

    public boolean isExpired(UserEntity user) {
        return user != null
                && user.getExpiresAt() != null
                && user.getExpiresAt().isBefore(LocalDateTime.now());
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
        Long count = memberMapper.selectCount(new LambdaQueryWrapper<WorkspaceMemberEntity>()
                .eq(WorkspaceMemberEntity::getWorkspaceId, workspaceId)
                .ne(WorkspaceMemberEntity::getRole, "owner"));
        if (count != null && count >= DEFAULT_MAX_CHILD_ACCOUNTS) {
            throw new MateClawException("err.workspace.member_limit_exceeded", 409,
                    "超过最大团队成员数量");
        }
    }
}
```

**Step 4: Add i18n messages**

Add to `messages.properties`:

```properties
err.account.expired=账号已过期
err.workspace.member_limit_exceeded=超过最大团队成员数量
```

Add to `messages_en.properties`:

```properties
err.account.expired=Account expired
err.workspace.member_limit_exceeded=Maximum team member count exceeded
```

**Step 5: Run tests**

Run:

```bash
mvn -pl mateclaw-server -Dtest=AccountEntitlementServiceTest test
```

Expected: PASS.

**Step 6: Commit**

```bash
git add \
  mateclaw-server/src/main/java/vip/mate/auth/service/AccountEntitlementService.java \
  mateclaw-server/src/test/java/vip/mate/auth/service/AccountEntitlementServiceTest.java \
  mateclaw-server/src/main/resources/messages.properties \
  mateclaw-server/src/main/resources/messages_en.properties
git commit -m "feat(auth): centralize account entitlement rules"
```

---

### Task 3: Registration Creates Expiring Owner and Dedicated Team

**Files:**
- Modify: `mateclaw-server/src/main/java/vip/mate/auth/service/AuthService.java`
- Modify: `mateclaw-server/src/main/java/vip/mate/auth/model/LoginResponse.java`
- Modify: `mateclaw-server/src/test/java/vip/mate/auth/service/AuthServiceRegisterTest.java`
- Modify if necessary: `mateclaw-server/src/test/java/vip/mate/auth/controller/AuthControllerRegisterTest.java`

**Step 1: Replace default-workspace tests with dedicated-team tests**

In `AuthServiceRegisterTest`, update the happy-path test name and assertions:

```java
@Test
void registerCreatesUserDedicatedWorkspaceAndReturnsToken() {
    RegisterRequest request = validRequest();
    when(userMapper.selectCount(any(LambdaQueryWrapper.class))).thenReturn(0L);
    when(passwordEncoder.encode("pass1234")).thenReturn("$2a$hash");
    doAnswer(invocation -> {
        UserEntity user = invocation.getArgument(0);
        user.setId(99L);
        return 1;
    }).when(userMapper).insert(any(UserEntity.class));

    LoginResponse response = authService.register(request);

    assertEquals(99L, response.getId());
    assertEquals("13800138000", response.getUsername());
    assertEquals("13800138000", response.getNickname());
    assertEquals("user", response.getRole());
    assertNotNull(response.getToken());
    assertNotNull(response.getExpiresAt());
    assertFalse(response.isExpired());

    ArgumentCaptor<UserEntity> userCaptor = ArgumentCaptor.forClass(UserEntity.class);
    verify(userMapper).insert(userCaptor.capture());
    UserEntity saved = userCaptor.getValue();
    assertEquals("13800138000", saved.getUsername());
    assertNotNull(saved.getExpiresAt());

    ArgumentCaptor<WorkspaceEntity> workspaceCaptor = ArgumentCaptor.forClass(WorkspaceEntity.class);
    verify(workspaceService).create(workspaceCaptor.capture(), eq(99L));
    assertEquals(99L, workspaceCaptor.getValue().getOwnerId());
    assertTrue(workspaceCaptor.getValue().getName().contains("13800138000"));
}
```

Remove `registerSkipsDefaultWorkspaceMembershipWhenAlreadyPresent`; it no longer applies.

Add import:

```java
import vip.mate.workspace.core.model.WorkspaceEntity;
import static org.mockito.ArgumentMatchers.eq;
```

**Step 2: Run the test to verify failure**

Run:

```bash
mvn -pl mateclaw-server -Dtest=AuthServiceRegisterTest test
```

Expected: failure because registration still adds default workspace membership and `LoginResponse` has no expiry fields.

**Step 3: Extend `LoginResponse`**

Modify `LoginResponse.java`:

```java
@Data
@AllArgsConstructor
public class LoginResponse {
    private Long id;
    private String token;
    private String username;
    private String nickname;
    private String role;
    private LocalDateTime expiresAt;
    private boolean expired;
    private Long currentWorkspaceId;
}
```

Add:

```java
import java.time.LocalDateTime;
```

Update every `new LoginResponse(...)` call to pass `user.getExpiresAt()`, expiry boolean, and `null` unless a workspace id is known.

**Step 4: Inject `AccountEntitlementService` into `AuthService`**

Add field:

```java
private final AccountEntitlementService entitlementService;
```

Update tests using `@InjectMocks` by adding:

```java
@Mock
private AccountEntitlementService entitlementService;
```

Stub where needed:

```java
when(entitlementService.isExpired(any(UserEntity.class))).thenReturn(false);
```

**Step 5: Update registration implementation**

In `register()`:

- Set `user.setExpiresAt(LocalDateTime.now().plusDays(30));`
- Replace default workspace membership with workspace creation:

```java
WorkspaceEntity workspace = new WorkspaceEntity();
String displayName = user.getNickname() == null || user.getNickname().isBlank()
        ? phone
        : user.getNickname();
workspace.setName(displayName + " 的团队");
workspace.setDescription("注册账号自动创建的团队");
workspace.setOwnerId(user.getId());
workspace = workspaceService.create(workspace, user.getId());

String token = generateToken(user);
return new LoginResponse(user.getId(), token, user.getUsername(), user.getNickname(), user.getRole(),
        user.getExpiresAt(), entitlementService.isExpired(user),
        workspace != null ? workspace.getId() : null);
```

Add imports:

```java
import java.time.LocalDateTime;
import vip.mate.workspace.core.model.WorkspaceEntity;
```

**Step 6: Update `login()` and `renewToken()` response usage**

In `login()`, return:

```java
return new LoginResponse(user.getId(), token, user.getUsername(), user.getNickname(), user.getRole(),
        user.getExpiresAt(), entitlementService.isExpired(user), null);
```

Do not reject expired users in `login()`.

**Step 7: Update controller test constructor calls**

In `AuthControllerRegisterTest`, update the sample `LoginResponse` with `null, false, null`.

**Step 8: Run tests**

Run:

```bash
mvn -pl mateclaw-server -Dtest=AuthServiceRegisterTest,AuthControllerRegisterTest test
```

Expected: PASS.

**Step 9: Commit**

```bash
git add \
  mateclaw-server/src/main/java/vip/mate/auth/service/AuthService.java \
  mateclaw-server/src/main/java/vip/mate/auth/model/LoginResponse.java \
  mateclaw-server/src/test/java/vip/mate/auth/service/AuthServiceRegisterTest.java \
  mateclaw-server/src/test/java/vip/mate/auth/controller/AuthControllerRegisterTest.java
git commit -m "feat(auth): create trial team on registration"
```

---

### Task 4: Child Account Expiry Inheritance and Team Limit

**Files:**
- Modify: `mateclaw-server/src/main/java/vip/mate/workspace/core/service/WorkspaceService.java`
- Modify: `mateclaw-server/src/main/java/vip/mate/workspace/core/controller/WorkspaceController.java`
- Modify: `mateclaw-server/src/test/java/vip/mate/workspace/core/service/WorkspaceServiceRoleValidationTest.java`
- Modify or create: `mateclaw-server/src/test/java/vip/mate/workspace/core/controller/WorkspaceControllerMembersAuthTest.java`

**Step 1: Write failing limit tests for `WorkspaceService`**

In `WorkspaceServiceRoleValidationTest`, add `AccountEntitlementService` mock and update constructor:

```java
private final AccountEntitlementService entitlementService = mock(AccountEntitlementService.class);
private final WorkspaceService service = new WorkspaceService(
        workspaceMapper, memberMapper, conversationMapper, wikiKnowledgeBaseService, null, entitlementService);
```

Add test:

```java
@Test
void addMemberChecksSubAccountLimitBeforeInsert() {
    WorkspaceEntity workspace = new WorkspaceEntity();
    workspace.setId(1L);
    when(workspaceMapper.selectById(1L)).thenReturn(workspace);
    when(memberMapper.selectOne(any())).thenReturn(null);
    doThrow(new MateClawException("err.workspace.member_limit_exceeded", 409,
            "超过最大团队成员数量"))
            .when(entitlementService).assertCanAddSubAccount(1L);

    MateClawException ex = assertThrows(MateClawException.class,
            () -> service.addMember(1L, 42L, "member"));

    assertEquals("err.workspace.member_limit_exceeded", ex.getMsgKey());
    verify(memberMapper, never()).insert(any(WorkspaceMemberEntity.class));
}
```

**Step 2: Run service test to verify failure**

Run:

```bash
mvn -pl mateclaw-server -Dtest=WorkspaceServiceRoleValidationTest#addMemberChecksSubAccountLimitBeforeInsert test
```

Expected: compile failure until constructor and service are updated.

**Step 3: Update `WorkspaceService`**

Add dependency:

```java
private final AccountEntitlementService entitlementService;
```

In `addMember`, before creating the membership and after duplicate check:

```java
String normalizedRole = normalizeAssignableRole(role);
if (!"owner".equals(normalizedRole)) {
    entitlementService.assertCanAddSubAccount(workspaceId);
}
...
member.setRole(normalizedRole);
```

**Step 4: Write controller tests for child-user creation**

In `WorkspaceControllerMembersAuthTest`, add a test:

```java
@Test
void addMemberCreatesNewChildWithOwnerExpiryAndRejectsExistingUsername() {
    WorkspaceService workspaceService = mock(WorkspaceService.class);
    AuthService authService = mock(AuthService.class);
    AccountEntitlementService entitlementService = mock(AccountEntitlementService.class);
    WorkspaceController controller = new WorkspaceController(workspaceService, authService, entitlementService);
    UserEntity current = new UserEntity();
    current.setId(1L);
    current.setUsername("owner");
    current.setRole("user");
    when(authService.findByUsername("owner")).thenReturn(current);
    when(authService.findByUsername("child")).thenReturn(null);
    LocalDateTime ownerExpiry = LocalDateTime.now().plusDays(20);
    when(entitlementService.resolveOwnerExpiry(7L)).thenReturn(ownerExpiry);
    doAnswer(invocation -> {
        UserEntity user = invocation.getArgument(0);
        user.setId(99L);
        return user;
    }).when(authService).createUser(any(UserEntity.class));

    Map<String, Object> body = new HashMap<>();
    body.put("username", "child");
    body.put("password", "pass1234");

    controller.addMember(7L, body, new TestingAuthenticationToken("owner", "pw"));

    ArgumentCaptor<UserEntity> userCaptor = ArgumentCaptor.forClass(UserEntity.class);
    verify(authService).createUser(userCaptor.capture());
    assertEquals(ownerExpiry, userCaptor.getValue().getExpiresAt());
    verify(workspaceService).addMember(7L, 99L, "member");
}
```

Add imports:

```java
import vip.mate.auth.service.AccountEntitlementService;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import org.mockito.ArgumentCaptor;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
```

Add a separate test:

```java
@Test
void addMemberRejectsExistingUsernameToAvoidExpiryConflicts() {
    ...
    UserEntity existing = new UserEntity();
    existing.setId(99L);
    existing.setUsername("child");
    when(authService.findByUsername("child")).thenReturn(existing);

    MateClawException ex = assertThrows(MateClawException.class,
            () -> controller.addMember(7L, body, new TestingAuthenticationToken("owner", "pw")));

    assertEquals("err.workspace.existing_user_not_allowed", ex.getMsgKey());
    verify(workspaceService, never()).addMember(anyLong(), anyLong(), anyString());
}
```

**Step 5: Update `WorkspaceController`**

Inject:

```java
private final AccountEntitlementService entitlementService;
```

In username path:

```java
UserEntity target = authService.findByUsername(username);
if (target != null) {
    throw new MateClawException("err.workspace.existing_user_not_allowed", 409,
            "账号已存在，请使用新手机号或用户名创建子账号");
}
if (password == null || password.isBlank()) {
    throw new MateClawException("err.workspace.user_not_found",
            "User not found: " + username + ". Provide a password to create the account.");
}
UserEntity newUser = new UserEntity();
newUser.setUsername(username);
newUser.setPassword(password);
newUser.setNickname(body.containsKey("nickname") ? body.get("nickname").toString() : username);
newUser.setExpiresAt(entitlementService.resolveOwnerExpiry(id));
target = authService.createUser(newUser);
```

Add messages:

```properties
err.workspace.existing_user_not_allowed=账号已存在，请使用新手机号或用户名创建子账号
```

English:

```properties
err.workspace.existing_user_not_allowed=Account already exists. Use a new phone or username for the child account.
```

**Step 6: Run tests**

Run:

```bash
mvn -pl mateclaw-server -Dtest=WorkspaceServiceRoleValidationTest,WorkspaceControllerMembersAuthTest test
```

Expected: PASS.

**Step 7: Commit**

```bash
git add \
  mateclaw-server/src/main/java/vip/mate/workspace/core/service/WorkspaceService.java \
  mateclaw-server/src/main/java/vip/mate/workspace/core/controller/WorkspaceController.java \
  mateclaw-server/src/test/java/vip/mate/workspace/core/service/WorkspaceServiceRoleValidationTest.java \
  mateclaw-server/src/test/java/vip/mate/workspace/core/controller/WorkspaceControllerMembersAuthTest.java \
  mateclaw-server/src/main/resources/messages.properties \
  mateclaw-server/src/main/resources/messages_en.properties
git commit -m "feat(workspace): enforce initial child account limit"
```

---

### Task 5: Account Status Endpoint

**Files:**
- Modify: `mateclaw-server/src/main/java/vip/mate/auth/controller/AuthController.java`
- Create: `mateclaw-server/src/main/java/vip/mate/auth/model/AccountStatusResponse.java`
- Create or modify: `mateclaw-server/src/test/java/vip/mate/auth/controller/AuthControllerAccountStatusTest.java`

**Step 1: Write failing controller test**

Create `AuthControllerAccountStatusTest.java`:

```java
package vip.mate.auth.controller;

import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.TestingAuthenticationToken;
import vip.mate.auth.model.UserEntity;
import vip.mate.auth.service.AccountEntitlementService;
import vip.mate.auth.service.AuthService;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AuthControllerAccountStatusTest {

    @Test
    void meReturnsExpiryStatusForCurrentUser() {
        AuthService authService = mock(AuthService.class);
        AccountEntitlementService entitlementService = mock(AccountEntitlementService.class);
        AuthController controller = new AuthController(authService, entitlementService);
        LocalDateTime expiresAt = LocalDateTime.now().plusDays(5);
        UserEntity user = new UserEntity();
        user.setId(42L);
        user.setUsername("alice");
        user.setNickname("Alice");
        user.setRole("user");
        user.setExpiresAt(expiresAt);
        when(authService.findByUsername("alice")).thenReturn(user);
        when(entitlementService.isExpired(user)).thenReturn(false);

        var result = controller.me(new TestingAuthenticationToken("alice", "pw"));

        assertEquals(42L, result.getData().id());
        assertEquals(expiresAt, result.getData().expiresAt());
        assertFalse(result.getData().expired());
    }
}
```

**Step 2: Run test to verify failure**

Run:

```bash
mvn -pl mateclaw-server -Dtest=AuthControllerAccountStatusTest test
```

Expected: compile failure because constructor and `me()` endpoint do not exist.

**Step 3: Add response record**

Create `AccountStatusResponse.java`:

```java
package vip.mate.auth.model;

import java.time.LocalDateTime;

public record AccountStatusResponse(
        Long id,
        String username,
        String nickname,
        String role,
        LocalDateTime expiresAt,
        boolean expired
) {
    public static AccountStatusResponse from(UserEntity user, boolean expired) {
        return new AccountStatusResponse(
                user.getId(),
                user.getUsername(),
                user.getNickname(),
                user.getRole(),
                user.getExpiresAt(),
                expired
        );
    }
}
```

**Step 4: Update `AuthController`**

Inject `AccountEntitlementService`.

Add:

```java
@Operation(summary = "获取当前账号状态")
@GetMapping("/me")
public R<AccountStatusResponse> me(Authentication auth) {
    UserEntity user = authService.findByUsername(auth.getName());
    if (user == null) {
        throw new MateClawException("err.auth.user_not_found", 404, "用户不存在");
    }
    return R.ok(AccountStatusResponse.from(user, entitlementService.isExpired(user)));
}
```

**Step 5: Update existing AuthController tests**

Any direct `new AuthController(authService)` must now pass an `AccountEntitlementService` mock.

**Step 6: Run tests**

Run:

```bash
mvn -pl mateclaw-server -Dtest=AuthControllerRegisterTest,AuthControllerAccountStatusTest test
```

Expected: PASS.

**Step 7: Commit**

```bash
git add \
  mateclaw-server/src/main/java/vip/mate/auth/controller/AuthController.java \
  mateclaw-server/src/main/java/vip/mate/auth/model/AccountStatusResponse.java \
  mateclaw-server/src/test/java/vip/mate/auth/controller/AuthControllerAccountStatusTest.java \
  mateclaw-server/src/test/java/vip/mate/auth/controller/AuthControllerRegisterTest.java
git commit -m "feat(auth): expose account expiry status"
```

---

### Task 6: Backend Expiry Filter

**Files:**
- Create: `mateclaw-server/src/main/java/vip/mate/config/AccountExpiryFilter.java`
- Modify: `mateclaw-server/src/main/java/vip/mate/config/SecurityConfig.java`
- Create: `mateclaw-server/src/test/java/vip/mate/config/AccountExpiryFilterTest.java`

**Step 1: Write failing filter tests**

Create `AccountExpiryFilterTest.java`:

```java
package vip.mate.config;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import vip.mate.auth.model.UserEntity;
import vip.mate.auth.service.AccountEntitlementService;
import vip.mate.auth.service.AuthService;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AccountExpiryFilterTest {

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void blocksExpiredUserFromBusinessApi() throws Exception {
        AuthService authService = mock(AuthService.class);
        AccountEntitlementService entitlementService = mock(AccountEntitlementService.class);
        AccountExpiryFilter filter = new AccountExpiryFilter(authService, entitlementService);
        UserEntity user = new UserEntity();
        user.setUsername("alice");
        when(authService.findByUsername("alice")).thenReturn(user);
        when(entitlementService.isExpired(user)).thenReturn(true);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("alice", null));
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/agents");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        assertEquals(403, response.getStatus());
        assertTrue(response.getContentAsString().contains("err.account.expired")
                || response.getContentAsString().contains("账号已过期"));
        verify(chain, never()).doFilter(any(), any());
    }

    @Test
    void allowsExpiredUserToAccessAccountStatus() throws Exception {
        AuthService authService = mock(AuthService.class);
        AccountEntitlementService entitlementService = mock(AccountEntitlementService.class);
        AccountExpiryFilter filter = new AccountExpiryFilter(authService, entitlementService);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("alice", null));
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/auth/me");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        verify(chain).doFilter(any(), any());
    }
}
```

**Step 2: Run test to verify failure**

Run:

```bash
mvn -pl mateclaw-server -Dtest=AccountExpiryFilterTest test
```

Expected: compile failure because `AccountExpiryFilter` does not exist.

**Step 3: Implement filter**

Create `AccountExpiryFilter.java`:

```java
package vip.mate.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import vip.mate.auth.model.UserEntity;
import vip.mate.auth.service.AccountEntitlementService;
import vip.mate.auth.service.AuthService;

import java.io.IOException;
import java.util.List;

@Component
@RequiredArgsConstructor
public class AccountExpiryFilter extends OncePerRequestFilter {

    private static final List<String> EXACT_ALLOWLIST = List.of(
            "/api/v1/auth/login",
            "/api/v1/auth/register",
            "/api/v1/auth/me",
            "/api/v1/workspaces"
    );

    private final AuthService authService;
    private final AccountEntitlementService entitlementService;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String uri = request.getRequestURI();
        if (!uri.startsWith("/api/") || isAllowed(uri)) {
            filterChain.doFilter(request, response);
            return;
        }
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || auth.getName() == null) {
            filterChain.doFilter(request, response);
            return;
        }
        UserEntity user = authService.findByUsername(auth.getName());
        if (user != null && entitlementService.isExpired(user)) {
            response.setStatus(403);
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write("{\"code\":403,\"msg\":\"账号已过期\",\"data\":{\"reason\":\"ACCOUNT_EXPIRED\",\"expiresAt\":\""
                    + (user.getExpiresAt() == null ? "" : user.getExpiresAt())
                    + "\"}}");
            return;
        }
        filterChain.doFilter(request, response);
    }

    private boolean isAllowed(String uri) {
        if (EXACT_ALLOWLIST.contains(uri)) return true;
        return uri.matches("^/api/v1/workspaces/[^/]+/access$");
    }
}
```

**Step 4: Register filter after JWT auth**

In `SecurityConfig`, inject `AccountExpiryFilter`:

```java
private final AccountExpiryFilter accountExpiryFilter;
```

Add after JWT filter:

```java
.addFilterAfter(accountExpiryFilter, JwtAuthFilter.class);
```

**Step 5: Run tests**

Run:

```bash
mvn -pl mateclaw-server -Dtest=AccountExpiryFilterTest test
```

Expected: PASS.

**Step 6: Run auth/workspace regression tests**

Run:

```bash
mvn -pl mateclaw-server -Dtest=AuthServiceRegisterTest,AuthControllerRegisterTest,AuthControllerAccountStatusTest,WorkspaceServiceRoleValidationTest,WorkspaceControllerMembersAuthTest,AccountExpiryFilterTest,AccountEntitlementServiceTest test
```

Expected: PASS.

**Step 7: Commit**

```bash
git add \
  mateclaw-server/src/main/java/vip/mate/config/AccountExpiryFilter.java \
  mateclaw-server/src/main/java/vip/mate/config/SecurityConfig.java \
  mateclaw-server/src/test/java/vip/mate/config/AccountExpiryFilterTest.java
git commit -m "feat(auth): block expired accounts from business APIs"
```

---

### Task 7: Frontend Account API and Store

**Files:**
- Modify: `mateclaw-ui/src/api/index.ts`
- Modify: `mateclaw-ui/src/types/index.ts`
- Create: `mateclaw-ui/src/stores/useAccountStore.ts`
- Modify: `mateclaw-ui/src/views/Login.vue`

**Step 1: Add API and types**

In `types/index.ts`, extend `LoginResponse`:

```ts
export interface LoginResponse {
  token: string
  username: string
  nickname: string
  role: string
  expiresAt?: string | null
  expired?: boolean
  currentWorkspaceId?: string | number | null
}

export interface AccountStatus {
  id: string | number
  username: string
  nickname?: string
  role: string
  expiresAt?: string | null
  expired: boolean
}
```

In `api/index.ts`, add:

```ts
import type { AccountStatus } from '@/types'
```

Under `authApi`:

```ts
me: () => http.get<AccountStatus>('/auth/me'),
```

**Step 2: Create account store**

Create `useAccountStore.ts`:

```ts
import { acceptHMRUpdate, defineStore } from 'pinia'
import { computed, ref } from 'vue'
import { authApi } from '@/api/index'
import type { AccountStatus, LoginResponse } from '@/types'

function formatDateTime(value: string | null | undefined) {
  if (!value) return ''
  const d = new Date(value)
  if (Number.isNaN(d.getTime())) return value
  const pad = (n: number) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}:${pad(d.getSeconds())}`
}

export const useAccountStore = defineStore('account', () => {
  const expiresAt = ref<string | null>(localStorage.getItem('account-expires-at'))
  const expired = ref(localStorage.getItem('account-expired') === 'true')

  const isPermanent = computed(() => !expiresAt.value)
  const expiryText = computed(() => {
    if (expired.value) return '已过期'
    if (isPermanent.value) return '永久有效'
    return `有效期至 ${formatDateTime(expiresAt.value)}`
  })

  function applyStatus(status: Partial<AccountStatus | LoginResponse>) {
    expiresAt.value = status.expiresAt ?? null
    expired.value = Boolean(status.expired)
    if (expiresAt.value) localStorage.setItem('account-expires-at', expiresAt.value)
    else localStorage.removeItem('account-expires-at')
    localStorage.setItem('account-expired', String(expired.value))
  }

  function markExpired(payload?: { expiresAt?: string | null }) {
    if (payload && 'expiresAt' in payload) {
      expiresAt.value = payload.expiresAt ?? expiresAt.value
    }
    expired.value = true
    localStorage.setItem('account-expired', 'true')
  }

  async function fetchAccount() {
    const res: any = await authApi.me()
    applyStatus(res.data || res)
  }

  return { expiresAt, expired, isPermanent, expiryText, applyStatus, markExpired, fetchAccount }
})

if (import.meta.hot) {
  import.meta.hot.accept(acceptHMRUpdate(useAccountStore, import.meta.hot))
}
```

**Step 3: Wire login/register responses**

In `Login.vue`, import `useAccountStore`, create `const accountStore = useAccountStore()`, and in `finishAuth(data, fallbackUsername)` call:

```ts
accountStore.applyStatus(data)
if (data.currentWorkspaceId) {
  localStorage.setItem('mc-workspace-id', String(data.currentWorkspaceId))
}
```

Place this before `workspaceStore.fetchWorkspaces()`.

**Step 4: Run typecheck**

Run:

```bash
pnpm exec vue-tsc --noEmit
```

Expected: PASS.

**Step 5: Commit**

```bash
git add \
  mateclaw-ui/src/api/index.ts \
  mateclaw-ui/src/types/index.ts \
  mateclaw-ui/src/stores/useAccountStore.ts \
  mateclaw-ui/src/views/Login.vue
git commit -m "feat(ui): store account expiry status"
```

---

### Task 8: Frontend Expiry Badge, Expired Modal, and Axios Expiry Handling

**Files:**
- Modify: `mateclaw-ui/src/api/index.ts`
- Modify: `mateclaw-ui/src/App.vue` or `mateclaw-ui/src/views/layout/MainLayout.vue`
- Modify: `mateclaw-ui/src/views/layout/MainLayout.vue`
- Modify: `mateclaw-ui/src/i18n/locales/zh-CN.ts`
- Modify: `mateclaw-ui/src/i18n/locales/en-US.ts`

**Step 1: Add i18n keys**

Add account copy in both locale files:

Chinese:

```ts
account: {
  permanent: '永久有效',
  validUntil: '有效期至 {time}',
  expired: '已过期',
  expiredTitle: '账号已过期',
  expiredDesc: '请扫码联系商务续费',
}
```

English:

```ts
account: {
  permanent: 'Permanent',
  validUntil: 'Valid until {time}',
  expired: 'Expired',
  expiredTitle: 'Account expired',
  expiredDesc: 'Scan the QR code to contact sales for renewal.',
}
```

If `useAccountStore.expiryText` stays hardcoded Chinese, refactor it to expose state and formatted time, then let components call `t()`.

**Step 2: Add axios expired detection**

In `api/index.ts` response interceptor, detect structured expired errors without importing Pinia store at module top to avoid initialization cycles:

```ts
function markAccountExpired(payload?: any) {
  import('@/stores/useAccountStore').then(({ useAccountStore }) => {
    useAccountStore().markExpired({ expiresAt: payload?.expiresAt })
  }).catch(() => {})
}
```

Before rejecting non-200 `R` responses:

```ts
if (data.msg === '账号已过期' || data.data?.reason === 'ACCOUNT_EXPIRED') {
  markAccountExpired(data.data)
}
```

In error handler, do the same for `err.response?.data`.

**Step 3: Add header badge**

In `MainLayout.vue`, import and initialize `useAccountStore`.

On mount, after auth exists:

```ts
accountStore.fetchAccount().catch(() => {})
```

In the user info section, add a compact badge near user name or role:

```vue
<div class="account-expiry-badge" :class="{ expired: accountStore.expired }">
  {{ accountExpiryLabel }}
</div>
```

Computed:

```ts
const accountExpiryLabel = computed(() => {
  if (accountStore.expired) return t('account.expired')
  if (!accountStore.expiresAt) return t('account.permanent')
  return t('account.validUntil', { time: formatAccountDateTime(accountStore.expiresAt) })
})
```

Add a local `formatAccountDateTime()` helper or export the helper from store.

**Step 4: Add non-dismissible expired modal**

Place inside layout root, after dialogs:

```vue
<Teleport to="body">
  <div v-if="accountStore.expired" class="account-expired-overlay">
    <div class="account-expired-modal" role="alertdialog" aria-modal="true">
      <h2>{{ t('account.expiredTitle') }}</h2>
      <p>{{ t('account.expiredDesc') }}</p>
      <img src="/business-qr.png" alt="" class="business-qr" />
    </div>
  </div>
</Teleport>
```

No close button and no overlay click handler.

**Step 5: Add CSS**

In `MainLayout.vue` scoped style, add:

```css
.account-expiry-badge {
  margin-top: 2px;
  font-size: 11px;
  color: var(--mc-text-tertiary);
  white-space: nowrap;
}
.account-expiry-badge.expired {
  color: var(--mc-danger);
  font-weight: 600;
}
.account-expired-overlay {
  position: fixed;
  inset: 0;
  z-index: 9999;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 24px;
  background: rgba(20, 17, 15, 0.48);
}
.account-expired-modal {
  width: min(360px, 100%);
  border-radius: 12px;
  background: var(--mc-bg-elevated);
  border: 1px solid var(--mc-border);
  padding: 24px;
  text-align: center;
  box-shadow: 0 18px 48px rgba(0, 0, 0, 0.22);
}
.business-qr {
  width: 180px;
  height: 180px;
  object-fit: contain;
}
```

**Step 6: Run typecheck**

Run:

```bash
pnpm exec vue-tsc --noEmit
```

Expected: PASS.

**Step 7: Commit**

```bash
git add \
  mateclaw-ui/src/api/index.ts \
  mateclaw-ui/src/views/layout/MainLayout.vue \
  mateclaw-ui/src/i18n/locales/zh-CN.ts \
  mateclaw-ui/src/i18n/locales/en-US.ts
git commit -m "feat(ui): show account expiry state"
```

---

### Task 9: Member Limit QR Modal

**Files:**
- Modify: `mateclaw-ui/src/views/Security/Members/index.vue`
- Modify: `mateclaw-ui/src/i18n/locales/zh-CN.ts`
- Modify: `mateclaw-ui/src/i18n/locales/en-US.ts`

**Step 1: Add i18n copy**

Under `security.members`, add:

Chinese:

```ts
limitModal: {
  title: '超过最大团队成员数量',
  desc: '请扫码联系商务开通更多成员',
  close: '我知道了',
}
```

English:

```ts
limitModal: {
  title: 'Maximum team member count exceeded',
  desc: 'Scan the QR code to contact sales for more seats.',
  close: 'Got it',
}
```

**Step 2: Add modal state**

In `Members/index.vue` script:

```ts
const showLimitDialog = ref(false)

function isMemberLimitError(e: any) {
  return e?.response?.data?.msg === '超过最大团队成员数量'
    || e?.response?.data?.data?.reason === 'MEMBER_LIMIT_EXCEEDED'
    || e?.message === t('security.members.messages.memberLimitExceeded')
}
```

Prefer checking `e.response?.data?.msg` or a backend `data.reason` if implemented. If backend only returns `msg`, use that.

**Step 3: Open modal on add failure**

In `addMember()` catch:

```ts
if (isMemberLimitError(e)) {
  showLimitDialog.value = true
  return
}
mcToast.error(e?.msg || e?.message || t('security.members.messages.addFailed'))
```

**Step 4: Add template modal**

Inside existing `Teleport to="body"` or a new one:

```vue
<Teleport to="body">
  <div v-if="showLimitDialog" class="modal-overlay">
    <div class="modal business-modal">
      <div class="modal-header">
        <h3>{{ t('security.members.limitModal.title') }}</h3>
        <button class="modal-close" @click="showLimitDialog = false">&times;</button>
      </div>
      <div class="modal-body business-modal-body">
        <p>{{ t('security.members.limitModal.desc') }}</p>
        <img src="/business-qr.png" alt="" class="business-qr" />
      </div>
      <div class="modal-footer">
        <button class="btn-primary" @click="showLimitDialog = false">
          {{ t('security.members.limitModal.close') }}
        </button>
      </div>
    </div>
  </div>
</Teleport>
```

**Step 5: Add CSS**

```css
.business-modal {
  max-width: 360px;
}
.business-modal-body {
  text-align: center;
}
.business-qr {
  width: 180px;
  height: 180px;
  object-fit: contain;
  margin-top: 12px;
}
```

**Step 6: Run typecheck**

Run:

```bash
pnpm exec vue-tsc --noEmit
```

Expected: PASS.

**Step 7: Commit**

```bash
git add \
  mateclaw-ui/src/views/Security/Members/index.vue \
  mateclaw-ui/src/i18n/locales/zh-CN.ts \
  mateclaw-ui/src/i18n/locales/en-US.ts
git commit -m "feat(ui): guide seat-limit upgrades"
```

---

### Task 10: Full Verification

**Files:**
- No new files unless fixing failures.

**Step 1: Run backend focused suite**

Run:

```bash
mvn -pl mateclaw-server -Dtest=AuthServiceRegisterTest,AuthControllerRegisterTest,AuthControllerAccountStatusTest,WorkspaceServiceRoleValidationTest,WorkspaceControllerMembersAuthTest,AccountExpiryFilterTest,AccountEntitlementServiceTest test
```

Expected: PASS, 0 failures.

**Step 2: Run frontend typecheck**

Run from `mateclaw-ui`:

```bash
pnpm exec vue-tsc --noEmit
```

Expected: PASS.

**Step 3: Run direct Vite build**

Run from `mateclaw-ui`:

```bash
node --max-old-space-size=6144 ./node_modules/vite/bin/vite.js build
```

Expected: PASS. Large chunk warnings are acceptable if they match current project behavior.

**Step 4: Browser verification**

Start the frontend:

```bash
pnpm exec vite --host 127.0.0.1 --port 5173
```

Use Browser to verify:

- Login/register page still renders.
- Register response stores account expiry state.
- Main layout shows the expiry badge.
- Simulating expired store state shows a non-dismissible modal with `/business-qr.png`.
- Members page shows limit modal when an `err.workspace.member_limit_exceeded` response occurs.

Stop the dev server after verification.

**Step 5: Final status**

Run:

```bash
git status --short --branch
git log --oneline -8
```

Expected: clean working tree with the implementation commits on the feature branch.

