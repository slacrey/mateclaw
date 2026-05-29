package vip.mate.auth.controller;

import org.junit.jupiter.api.Test;
import vip.mate.auth.model.LoginResponse;
import vip.mate.auth.model.RegisterRequest;
import vip.mate.auth.service.AuthService;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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
