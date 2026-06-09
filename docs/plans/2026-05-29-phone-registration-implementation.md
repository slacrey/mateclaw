# Phone Registration Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Add phone-number registration with fixed code `888888`, password setup, automatic default workspace membership, and post-registration navigation to `/dashboard`.

**Architecture:** Add a small anonymous registration endpoint beside the existing login endpoint. The backend treats the phone number as `mate_user.username`, creates a regular user, adds default workspace membership, and returns `LoginResponse`; the frontend adds a register mode to the current login page and reuses token storage.

**Tech Stack:** Spring Boot, MyBatis-Plus, JWT, BCrypt, JUnit 5/Mockito, Vue 3, Vue Router, Vue I18n, Axios, Vite.

---

### Task 1: Backend Registration Service Tests

**Files:**
- Create: `mateclaw-server/src/test/java/vip/mate/auth/service/AuthServiceRegisterTest.java`
- Later modify: `mateclaw-server/src/main/java/vip/mate/auth/service/AuthService.java`
- Later create: `mateclaw-server/src/main/java/vip/mate/auth/model/RegisterRequest.java`

**Step 1: Write the failing test**

Create `AuthServiceRegisterTest.java`:

```java
package vip.mate.auth.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;
import vip.mate.auth.model.LoginResponse;
import vip.mate.auth.model.RegisterRequest;
import vip.mate.auth.model.UserEntity;
import vip.mate.auth.repository.UserMapper;
import vip.mate.exception.MateClawException;
import vip.mate.workspace.core.service.WorkspaceService;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class AuthServiceRegisterTest {

    private UserMapper userMapper;
    private BCryptPasswordEncoder passwordEncoder;
    private WorkspaceService workspaceService;
    private AuthService authService;

    @BeforeEach
    void setUp() {
        userMapper = mock(UserMapper.class);
        passwordEncoder = mock(BCryptPasswordEncoder.class);
        workspaceService = mock(WorkspaceService.class);
        authService = new AuthService(userMapper, passwordEncoder, workspaceService);
        ReflectionTestUtils.setField(authService, "jwtSecret", "MateClaw-Test-Secret-Key-For-Registration");
        ReflectionTestUtils.setField(authService, "jwtExpiration", 86400000L);
    }

    @Test
    void registerCreatesUserAddsDefaultWorkspaceAndReturnsToken() {
        RegisterRequest request = new RegisterRequest();
        request.setPhone("13800138000");
        request.setCode("888888");
        request.setPassword("pass1234");

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
        assertEquals("user", response.getRole());
        assertNotNull(response.getToken());
        verify(workspaceService).addMember(1L, 99L, "member");

        ArgumentCaptor<UserEntity> userCaptor = ArgumentCaptor.forClass(UserEntity.class);
        verify(userMapper).insert(userCaptor.capture());
        UserEntity saved = userCaptor.getValue();
        assertEquals("13800138000", saved.getUsername());
        assertEquals("$2a$hash", saved.getPassword());
        assertEquals("13800138000", saved.getNickname());
        assertEquals("user", saved.getRole());
        assertTrue(saved.getEnabled());
    }

    @Test
    void registerRejectsInvalidVerificationCode() {
        RegisterRequest request = validRequest();
        request.setCode("123456");

        MateClawException ex = assertThrows(MateClawException.class, () -> authService.register(request));

        assertEquals("err.auth.invalid_verification_code", ex.getMsgKey());
        verify(userMapper, never()).insert(any());
        verify(workspaceService, never()).addMember(anyLong(), anyLong(), anyString());
    }

    @Test
    void registerRejectsDuplicatePhone() {
        RegisterRequest request = validRequest();
        when(userMapper.selectCount(any(LambdaQueryWrapper.class))).thenReturn(1L);

        MateClawException ex = assertThrows(MateClawException.class, () -> authService.register(request));

        assertEquals("err.auth.username_exists", ex.getMsgKey());
        verify(userMapper, never()).insert(any());
    }

    @Test
    void registerRejectsBlankPassword() {
        RegisterRequest request = validRequest();
        request.setPassword(" ");

        MateClawException ex = assertThrows(MateClawException.class, () -> authService.register(request));

        assertEquals("err.auth.password_required", ex.getMsgKey());
        verify(userMapper, never()).insert(any());
    }

    private RegisterRequest validRequest() {
        RegisterRequest request = new RegisterRequest();
        request.setPhone("13800138000");
        request.setCode("888888");
        request.setPassword("pass1234");
        return request;
    }
}
```

**Step 2: Run test to verify it fails**

Run:

```bash
mvn -pl mateclaw-server -Dtest=AuthServiceRegisterTest test
```

Expected: compile failure because `RegisterRequest` and `AuthService.register()` do not exist yet.

**Step 3: Commit nothing**

Do not commit the failing test alone unless the team wants red commits. Keep it staged only after implementation passes.

---

### Task 2: Backend Registration Service Implementation

**Files:**
- Create: `mateclaw-server/src/main/java/vip/mate/auth/model/RegisterRequest.java`
- Modify: `mateclaw-server/src/main/java/vip/mate/auth/service/AuthService.java`

**Step 1: Add request DTO**

Create `RegisterRequest.java`:

```java
package vip.mate.auth.model;

import lombok.Data;

/**
 * 手机号注册请求。
 */
@Data
public class RegisterRequest {
    private String phone;
    private String code;
    private String password;
    private String nickname;
}
```

**Step 2: Extend `AuthService` constructor dependencies**

Add imports:

```java
import org.springframework.transaction.annotation.Transactional;
import vip.mate.workspace.core.service.WorkspaceService;
import java.util.regex.Pattern;
```

Add fields/constants:

```java
private static final long DEFAULT_WORKSPACE_ID = 1L;
private static final String FIXED_REGISTER_CODE = "888888";
private static final Pattern PHONE_PATTERN = Pattern.compile("^\\+?\\d{6,20}$");

private final WorkspaceService workspaceService;
```

**Step 3: Add `register()`**

Place this near `login()`:

```java
/**
 * 手机号注册。验证码临时固定为 888888，手机号作为 username 保存。
 */
@Transactional
public LoginResponse register(RegisterRequest request) {
    String phone = normalizePhone(request != null ? request.getPhone() : null);
    if (!PHONE_PATTERN.matcher(phone).matches()) {
        throw new MateClawException("err.auth.invalid_phone", 400, "手机号格式不正确");
    }
    if (!FIXED_REGISTER_CODE.equals(request.getCode())) {
        throw new MateClawException("err.auth.invalid_verification_code", 400, "验证码错误");
    }
    if (request.getPassword() == null || request.getPassword().isBlank()) {
        throw new MateClawException("err.auth.password_required", 400, "Password is required");
    }

    Long count = userMapper.selectCount(new LambdaQueryWrapper<UserEntity>()
            .eq(UserEntity::getUsername, phone));
    if (count > 0) {
        throw new MateClawException("err.auth.username_exists", 409, "手机号已注册: " + phone);
    }

    UserEntity user = new UserEntity();
    user.setUsername(phone);
    user.setPassword(passwordEncoder.encode(request.getPassword().trim()));
    user.setNickname(request.getNickname() == null || request.getNickname().isBlank()
            ? phone
            : request.getNickname().trim());
    user.setRole("user");
    user.setEnabled(true);
    userMapper.insert(user);

    try {
        workspaceService.addMember(DEFAULT_WORKSPACE_ID, user.getId(), "member");
    } catch (MateClawException e) {
        if (!"err.workspace.member_exists".equals(e.getMsgKey())) {
            throw e;
        }
    }

    String token = generateToken(user);
    return new LoginResponse(user.getId(), token, user.getUsername(), user.getNickname(), user.getRole());
}

private String normalizePhone(String phone) {
    if (phone == null) {
        return "";
    }
    return phone.trim().replaceAll("[\\s-]", "");
}
```

**Step 4: Run service test**

Run:

```bash
mvn -pl mateclaw-server -Dtest=AuthServiceRegisterTest test
```

Expected: PASS.

**Step 5: Commit**

```bash
git add mateclaw-server/src/main/java/vip/mate/auth/model/RegisterRequest.java \
        mateclaw-server/src/main/java/vip/mate/auth/service/AuthService.java \
        mateclaw-server/src/test/java/vip/mate/auth/service/AuthServiceRegisterTest.java
git commit -m "feat(auth): add phone registration service"
```

---

### Task 3: Anonymous Register API, Rate Limit, and Messages

**Files:**
- Modify: `mateclaw-server/src/main/java/vip/mate/auth/controller/AuthController.java`
- Modify: `mateclaw-server/src/main/java/vip/mate/config/SecurityConfig.java`
- Modify: `mateclaw-server/src/main/java/vip/mate/config/LoginRateLimitFilter.java`
- Modify: `mateclaw-server/src/main/resources/messages.properties`
- Modify: `mateclaw-server/src/main/resources/messages_en.properties`
- Create: `mateclaw-server/src/test/java/vip/mate/auth/controller/AuthControllerRegisterTest.java`

**Step 1: Write controller test**

Create `AuthControllerRegisterTest.java`:

```java
package vip.mate.auth.controller;

import org.junit.jupiter.api.Test;
import vip.mate.auth.model.LoginResponse;
import vip.mate.auth.model.RegisterRequest;
import vip.mate.auth.service.AuthService;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.*;

class AuthControllerRegisterTest {

    @Test
    void registerDelegatesToAuthService() {
        AuthService authService = mock(AuthService.class);
        AuthController controller = new AuthController(authService);
        RegisterRequest request = new RegisterRequest();
        LoginResponse response = new LoginResponse(7L, "token", "13800138000", "13800138000", "user");
        when(authService.register(request)).thenReturn(response);

        var result = controller.register(request);

        assertEquals(response, result.getData());
        verify(authService).register(request);
    }
}
```

**Step 2: Run test to verify it fails**

Run:

```bash
mvn -pl mateclaw-server -Dtest=AuthControllerRegisterTest test
```

Expected: compile failure because `AuthController.register()` does not exist.

**Step 3: Add controller endpoint**

In `AuthController.java`, import `RegisterRequest` and add:

```java
@Operation(summary = "手机号注册")
@PostMapping("/register")
public R<LoginResponse> register(@RequestBody RegisterRequest request) {
    return R.ok(authService.register(request));
}
```

**Step 4: Permit anonymous register**

In `SecurityConfig.java`, add the register path next to login:

```java
"/api/v1/auth/login",
"/api/v1/auth/register",
```

**Step 5: Rate-limit register too**

Change `LoginRateLimitFilter` from a single `LOGIN_PATH` to a set:

```java
private static final Set<String> AUTH_PATHS = Set.of(
        "/api/v1/auth/login",
        "/api/v1/auth/register"
);
```

Add import:

```java
import java.util.Set;
```

Change the condition:

```java
if ("POST".equalsIgnoreCase(httpReq.getMethod()) && AUTH_PATHS.contains(httpReq.getRequestURI())) {
```

Update the warning and response message if desired:

```java
log.warn("[RateLimit] Auth rate limit exceeded for IP: {} path={} attempts={}",
        ip, httpReq.getRequestURI(), current);
httpResp.getWriter().write("{\"code\":429,\"msg\":\"Too many authentication attempts, please try again later\",\"data\":null}");
```

**Step 6: Add i18n messages**

In `messages.properties`:

```properties
err.auth.invalid_phone=手机号格式不正确
err.auth.invalid_verification_code=验证码错误
```

In `messages_en.properties`:

```properties
err.auth.invalid_phone=Invalid phone number
err.auth.invalid_verification_code=Invalid verification code
```

**Step 7: Run controller and service tests**

Run:

```bash
mvn -pl mateclaw-server -Dtest=AuthServiceRegisterTest,AuthControllerRegisterTest test
```

Expected: PASS.

**Step 8: Commit**

```bash
git add mateclaw-server/src/main/java/vip/mate/auth/controller/AuthController.java \
        mateclaw-server/src/main/java/vip/mate/config/SecurityConfig.java \
        mateclaw-server/src/main/java/vip/mate/config/LoginRateLimitFilter.java \
        mateclaw-server/src/main/resources/messages.properties \
        mateclaw-server/src/main/resources/messages_en.properties \
        mateclaw-server/src/test/java/vip/mate/auth/controller/AuthControllerRegisterTest.java
git commit -m "feat(auth): expose phone registration endpoint"
```

---

### Task 4: Frontend API and Translations

**Files:**
- Modify: `mateclaw-ui/src/api/index.ts`
- Modify: `mateclaw-ui/src/i18n/locales/zh-CN.ts`
- Modify: `mateclaw-ui/src/i18n/locales/en-US.ts`

**Step 1: Add API client method**

In `authApi`:

```ts
register: (data: { phone: string; code: string; password: string; nickname?: string }) =>
  http.post('/auth/register', data),
```

**Step 2: Add Chinese login/register keys**

In the `login` object in `zh-CN.ts`, add:

```ts
modeLogin: '登录',
modeRegister: '注册',
phone: '手机号',
code: '验证码',
confirmPassword: '确认密码',
getCode: '获取验证码',
fixedCodeHint: '当前验证码固定为 888888',
register: '注册并进入工作台',
registerSuccess: '注册成功',
registerFailed: '注册失败，请检查输入',
passwordMismatch: '两次输入的密码不一致',
placeholders: {
  username: '请输入用户名',
  password: '请输入密码',
  phone: '请输入手机号',
  code: '请输入验证码',
  confirmPassword: '请再次输入密码',
},
```

Keep existing `placeholders.username` and `placeholders.password`; merge the new keys rather than replacing the object.

**Step 3: Add English login/register keys**

In the `login` object in `en-US.ts`, add matching keys:

```ts
modeLogin: 'Sign In',
modeRegister: 'Register',
phone: 'Phone',
code: 'Verification code',
confirmPassword: 'Confirm password',
getCode: 'Get code',
fixedCodeHint: 'The current verification code is fixed at 888888',
register: 'Register and enter dashboard',
registerSuccess: 'Registration successful',
registerFailed: 'Registration failed. Please check your input.',
passwordMismatch: 'Passwords do not match',
placeholders: {
  username: 'Enter username',
  password: 'Enter password',
  phone: 'Enter phone number',
  code: 'Enter verification code',
  confirmPassword: 'Enter password again',
},
```

**Step 4: Run type check after UI implementation**

Do not run the frontend build until Task 5 changes `Login.vue`, because these keys are not consumed yet.

**Step 5: Commit with Task 5**

Commit the API and i18n changes together with the login-page UI change.

---

### Task 5: Login Page Register Mode

**Files:**
- Modify: `mateclaw-ui/src/views/Login.vue`

**Step 1: Add register state**

In `<script setup>`, extend state:

```ts
type AuthMode = 'login' | 'register'

const mode = ref<AuthMode>('login')
const registerForm = reactive({
  phone: '',
  code: '',
  password: '',
  confirmPassword: '',
})
```

Add helper to store session:

```ts
function storeSession(data: any, fallbackUsername: string) {
  localStorage.setItem('token', data.token)
  localStorage.setItem('userId', String(data.id || '1'))
  localStorage.setItem('username', data.username || fallbackUsername)
  localStorage.setItem('role', data.role || 'user')
}
```

Update `handleLogin()` to call `storeSession(data, form.username)`.

**Step 2: Add fixed-code helper**

```ts
function fillFixedCode() {
  registerForm.code = '888888'
  errorMsg.value = ''
}
```

**Step 3: Add register submit handler**

```ts
async function handleRegister() {
  if (!registerForm.phone || !registerForm.code || !registerForm.password) return
  if (registerForm.password !== registerForm.confirmPassword) {
    errorMsg.value = t('login.passwordMismatch')
    return
  }
  loading.value = true
  errorMsg.value = ''
  try {
    const res: any = await authApi.register({
      phone: registerForm.phone,
      code: registerForm.code,
      password: registerForm.password,
    })
    const data = res.data || res
    storeSession(data, registerForm.phone)
    try {
      await workspaceStore.fetchWorkspaces()
    } catch {
      /* router guard will handle capability refresh */
    }
    router.push('/dashboard')
  } catch (e: any) {
    errorMsg.value = e?.message || t('login.registerFailed')
  } finally {
    loading.value = false
  }
}
```

**Step 4: Add mode switch in template**

Place above the form:

```vue
<div class="auth-mode-switch" role="tablist" :aria-label="t('login.cardTitle')">
  <button
    type="button"
    class="mode-btn"
    :class="{ active: mode === 'login' }"
    @click="mode = 'login'; errorMsg = ''"
  >
    {{ t('login.modeLogin') }}
  </button>
  <button
    type="button"
    class="mode-btn"
    :class="{ active: mode === 'register' }"
    @click="mode = 'register'; errorMsg = ''"
  >
    {{ t('login.modeRegister') }}
  </button>
</div>
```

Change the form submit:

```vue
<form class="login-form" @submit.prevent="mode === 'login' ? handleLogin() : handleRegister()">
```

Wrap existing username/password inputs in `v-if="mode === 'login'"`. Add register inputs in `v-else`:

```vue
<template v-else>
  <div class="input-wrap">
    <input
      v-model.trim="registerForm.phone"
      type="tel"
      class="form-input"
      :placeholder="t('login.placeholders.phone')"
      :aria-label="t('login.phone')"
      autocomplete="tel"
      required
    />
  </div>

  <div class="input-wrap code-wrap">
    <input
      v-model.trim="registerForm.code"
      type="text"
      inputmode="numeric"
      class="form-input"
      :placeholder="t('login.placeholders.code')"
      :aria-label="t('login.code')"
      autocomplete="one-time-code"
      required
    />
    <button type="button" class="code-btn" @click="fillFixedCode">
      {{ t('login.getCode') }}
    </button>
  </div>

  <div class="fixed-code-hint">{{ t('login.fixedCodeHint') }}</div>

  <div class="input-wrap">
    <input
      v-model="registerForm.password"
      :type="showPassword ? 'text' : 'password'"
      class="form-input form-input--has-eye"
      :placeholder="t('login.placeholders.password')"
      :aria-label="t('login.fields.password')"
      autocomplete="new-password"
      required
    />
    <!-- reuse the existing eye button markup -->
  </div>

  <div class="input-wrap">
    <input
      v-model="registerForm.confirmPassword"
      :type="showPassword ? 'text' : 'password'"
      class="form-input"
      :placeholder="t('login.placeholders.confirmPassword')"
      :aria-label="t('login.confirmPassword')"
      autocomplete="new-password"
      required
    />
  </div>
</template>
```

Change submit label:

```vue
<span v-if="!loading">{{ mode === 'login' ? t('login.signIn') : t('login.register') }}</span>
```

**Step 5: Add styles**

Add compact styles near form styles:

```css
.auth-mode-switch {
  width: 100%;
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 6px;
  padding: 4px;
  border: 1px solid var(--mc-border);
  border-radius: 12px;
  background: var(--mc-bg-sunken);
}

.mode-btn {
  height: 36px;
  border: none;
  border-radius: 8px;
  background: transparent;
  color: var(--mc-text-secondary);
  font-weight: 600;
  cursor: pointer;
}

.mode-btn.active {
  background: var(--mc-bg-elevated);
  color: var(--mc-primary);
  box-shadow: 0 1px 4px rgba(0, 0, 0, 0.08);
}

.code-wrap {
  gap: 8px;
}

.code-wrap .form-input {
  min-width: 0;
}

.code-btn {
  flex: 0 0 auto;
  height: 46px;
  padding: 0 12px;
  border: 1px solid var(--mc-border);
  border-radius: 10px;
  background: var(--mc-bg-elevated);
  color: var(--mc-primary);
  font-weight: 600;
  cursor: pointer;
}

.fixed-code-hint {
  margin-top: -8px;
  color: var(--mc-text-tertiary);
  font-size: 12px;
}
```

**Step 6: Run frontend build**

Run:

```bash
cd mateclaw-ui && npm run build
```

Expected: Vue type check and Vite build pass.

**Step 7: Commit**

```bash
git add mateclaw-ui/src/api/index.ts \
        mateclaw-ui/src/i18n/locales/zh-CN.ts \
        mateclaw-ui/src/i18n/locales/en-US.ts \
        mateclaw-ui/src/views/Login.vue
git commit -m "feat(ui): add phone registration form"
```

---

### Task 6: End-to-End Verification

**Files:**
- No new files unless a bug is found during verification.

**Step 1: Run focused backend tests**

Run:

```bash
mvn -pl mateclaw-server -Dtest=AuthServiceRegisterTest,AuthControllerRegisterTest test
```

Expected: PASS.

**Step 2: Run broader backend auth compile/test check**

Run:

```bash
mvn -pl mateclaw-server -DskipTests compile
```

Expected: PASS.

**Step 3: Run frontend build**

Run:

```bash
cd mateclaw-ui && npm run build
```

Expected: PASS.

**Step 4: Manual browser smoke test**

Start the app using the project's normal dev command. Then verify:

1. Open `/login`.
2. Switch to register mode.
3. Enter a new phone number, click get-code, set password and confirmation.
4. Submit.
5. Confirm the app lands on `/dashboard`.
6. Log out.
7. Log in again with phone number and password.

Expected: registration succeeds, dashboard loads, and subsequent password login works.

**Step 5: Final commit if verification fixes were needed**

If verification required fixes:

```bash
git add <fixed-files>
git commit -m "fix(auth): polish phone registration flow"
```

If no fixes were needed, do not create an empty commit.
