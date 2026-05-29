package vip.mate.auth.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;
import vip.mate.auth.model.LoginResponse;
import vip.mate.auth.model.RegisterRequest;
import vip.mate.auth.model.UserEntity;
import vip.mate.auth.repository.UserMapper;
import vip.mate.exception.MateClawException;
import vip.mate.workspace.core.model.WorkspaceMemberEntity;
import vip.mate.workspace.core.service.WorkspaceService;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuthServiceRegisterTest {

    @Mock
    private UserMapper userMapper;

    @Mock
    private BCryptPasswordEncoder passwordEncoder;

    @Mock
    private WorkspaceService workspaceService;

    @InjectMocks
    private AuthService authService;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(authService, "jwtSecret", "MateClaw-Test-Secret-Key-For-Registration");
        ReflectionTestUtils.setField(authService, "jwtExpiration", 86400000L);
    }

    @Test
    void registerCreatesUserAddsDefaultWorkspaceAndReturnsToken() {
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
        verify(userMapper, never()).insert(any(UserEntity.class));
        verifyNoInteractions(workspaceService);
    }

    @Test
    void registerRejectsInvalidPhone() {
        RegisterRequest request = validRequest();
        request.setPhone("555-abc-1212");

        MateClawException ex = assertThrows(MateClawException.class, () -> authService.register(request));

        assertEquals("err.auth.invalid_phone", ex.getMsgKey());
        verifyNoInteractions(userMapper, passwordEncoder, workspaceService);
    }

    @Test
    void registerNormalizesPhoneBeforePersistenceAndResponse() {
        RegisterRequest request = validRequest();
        request.setPhone(" 138 0013-8000 ");
        when(userMapper.selectCount(any(LambdaQueryWrapper.class))).thenReturn(0L);
        when(passwordEncoder.encode("pass1234")).thenReturn("$2a$hash");
        doAnswer(invocation -> {
            UserEntity user = invocation.getArgument(0);
            user.setId(99L);
            return 1;
        }).when(userMapper).insert(any(UserEntity.class));

        LoginResponse response = authService.register(request);

        assertEquals("13800138000", response.getUsername());
        assertEquals("13800138000", response.getNickname());

        ArgumentCaptor<UserEntity> userCaptor = ArgumentCaptor.forClass(UserEntity.class);
        verify(userMapper).insert(userCaptor.capture());
        UserEntity saved = userCaptor.getValue();
        assertEquals("13800138000", saved.getUsername());
        assertEquals("13800138000", saved.getNickname());
        verify(workspaceService).addMember(1L, 99L, "member");
    }

    @Test
    void registerRejectsDuplicatePhone() {
        RegisterRequest request = validRequest();
        when(userMapper.selectCount(any(LambdaQueryWrapper.class))).thenReturn(1L);

        MateClawException ex = assertThrows(MateClawException.class, () -> authService.register(request));

        assertEquals("err.auth.username_exists", ex.getMsgKey());
        verify(userMapper, never()).insert(any(UserEntity.class));
        verifyNoInteractions(workspaceService);
    }

    @Test
    void registerMapsDuplicateKeyRaceToUsernameExists() {
        RegisterRequest request = validRequest();
        when(userMapper.selectCount(any(LambdaQueryWrapper.class))).thenReturn(0L);
        when(passwordEncoder.encode("pass1234")).thenReturn("$2a$hash");
        when(userMapper.insert(any(UserEntity.class))).thenThrow(new DuplicateKeyException("duplicate username"));

        MateClawException ex = assertThrows(MateClawException.class, () -> authService.register(request));

        assertEquals("err.auth.username_exists", ex.getMsgKey());
        verifyNoInteractions(workspaceService);
    }

    @Test
    void registerRejectsBlankPassword() {
        RegisterRequest request = validRequest();
        request.setPassword(" ");

        MateClawException ex = assertThrows(MateClawException.class, () -> authService.register(request));

        assertEquals("err.auth.password_required", ex.getMsgKey());
        verify(userMapper, never()).insert(any(UserEntity.class));
        verifyNoInteractions(workspaceService);
    }

    @Test
    void registerSkipsDefaultWorkspaceMembershipWhenAlreadyPresent() {
        RegisterRequest request = validRequest();
        when(userMapper.selectCount(any(LambdaQueryWrapper.class))).thenReturn(0L);
        when(passwordEncoder.encode("pass1234")).thenReturn("$2a$hash");
        doAnswer(invocation -> {
            UserEntity user = invocation.getArgument(0);
            user.setId(99L);
            return 1;
        }).when(userMapper).insert(any(UserEntity.class));
        WorkspaceMemberEntity existing = new WorkspaceMemberEntity();
        existing.setWorkspaceId(1L);
        existing.setUserId(99L);
        existing.setRole("member");
        when(workspaceService.getMembership(1L, 99L)).thenReturn(existing);

        LoginResponse response = authService.register(request);

        assertEquals(99L, response.getId());
        assertNotNull(response.getToken());
        verify(workspaceService, never()).addMember(1L, 99L, "member");
    }

    private RegisterRequest validRequest() {
        RegisterRequest request = new RegisterRequest();
        request.setPhone("13800138000");
        request.setCode("888888");
        request.setPassword("pass1234");
        return request;
    }
}
