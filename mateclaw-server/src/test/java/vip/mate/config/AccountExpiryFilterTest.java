package vip.mate.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import vip.mate.auth.model.UserEntity;
import vip.mate.auth.service.AccountEntitlementService;
import vip.mate.auth.service.AuthService;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AccountExpiryFilterTest {

    private final AuthService authService = mock(AuthService.class);
    private final AccountEntitlementService entitlementService = mock(AccountEntitlementService.class);
    private final ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
    private final AccountExpiryFilter filter = new AccountExpiryFilter(authService, entitlementService, objectMapper);

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void expiredAccountIsBlockedFromBusinessApi() throws Exception {
        LocalDateTime expiresAt = LocalDateTime.of(2026, 5, 1, 10, 30);
        UserEntity user = user("alice", expiresAt);
        when(authService.findByUsername("alice")).thenReturn(user);
        when(entitlementService.isExpired(user)).thenReturn(true);
        authenticate("alice");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request("/api/v1/agents"), response, chain);

        assertEquals(403, response.getStatus());
        assertEquals("application/json;charset=UTF-8", response.getContentType());
        JsonNode body = objectMapper.readTree(response.getContentAsString());
        assertEquals(403, body.path("code").asInt());
        assertEquals("账号已过期", body.path("msg").asText());
        assertEquals("ACCOUNT_EXPIRED", body.path("data").path("reason").asText());
        assertEquals(expiresAt.toString(), body.path("data").path("expiresAt").asText());
        verify(chain, never()).doFilter(any(), any());
    }

    @Test
    void expiredAccountCanAccessMeEndpoint() throws Exception {
        UserEntity user = user("alice", LocalDateTime.now().minusDays(1));
        when(authService.findByUsername("alice")).thenReturn(user);
        when(entitlementService.isExpired(user)).thenReturn(true);
        authenticate("alice");
        MockHttpServletRequest request = request("/api/v1/auth/me");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        assertEquals(200, response.getStatus());
        verify(chain).doFilter(request, response);
    }

    @Test
    void expiredAccountCanAccessWorkspaceBootstrapEndpoints() throws Exception {
        UserEntity user = user("alice", LocalDateTime.now().minusDays(1));
        when(authService.findByUsername("alice")).thenReturn(user);
        when(entitlementService.isExpired(user)).thenReturn(true);
        authenticate("alice");
        FilterChain chain = mock(FilterChain.class);
        MockHttpServletRequest workspacesRequest = request("/api/v1/workspaces");
        MockHttpServletResponse workspacesResponse = new MockHttpServletResponse();
        MockHttpServletRequest accessRequest = request("/api/v1/workspaces/7/access");
        MockHttpServletResponse accessResponse = new MockHttpServletResponse();

        filter.doFilter(workspacesRequest, workspacesResponse, chain);
        filter.doFilter(accessRequest, accessResponse, chain);

        assertEquals(200, workspacesResponse.getStatus());
        assertEquals(200, accessResponse.getStatus());
        verify(chain).doFilter(workspacesRequest, workspacesResponse);
        verify(chain).doFilter(accessRequest, accessResponse);
    }

    @Test
    void expiredAccountCanAccessPublicBootstrapEndpointsWithAttachedToken() throws Exception {
        UserEntity user = user("alice", LocalDateTime.now().minusDays(1));
        when(authService.findByUsername("alice")).thenReturn(user);
        when(entitlementService.isExpired(user)).thenReturn(true);
        authenticate("alice");
        FilterChain chain = mock(FilterChain.class);
        MockHttpServletRequest languageRequest = request("GET", "/api/v1/settings/language");
        MockHttpServletResponse languageResponse = new MockHttpServletResponse();
        MockHttpServletRequest setupRequest = request("GET", "/api/v1/setup/status");
        MockHttpServletResponse setupResponse = new MockHttpServletResponse();
        MockHttpServletRequest generatedFileRequest = request("GET", "/api/v1/files/generated/abc");
        MockHttpServletResponse generatedFileResponse = new MockHttpServletResponse();

        filter.doFilter(languageRequest, languageResponse, chain);
        filter.doFilter(setupRequest, setupResponse, chain);
        filter.doFilter(generatedFileRequest, generatedFileResponse, chain);

        assertEquals(200, languageResponse.getStatus());
        assertEquals(200, setupResponse.getStatus());
        assertEquals(200, generatedFileResponse.getStatus());
        verify(chain).doFilter(languageRequest, languageResponse);
        verify(chain).doFilter(setupRequest, setupResponse);
        verify(chain).doFilter(generatedFileRequest, generatedFileResponse);
    }

    @Test
    void expiredAccountIsBlockedFromNonPublicChatApi() throws Exception {
        UserEntity user = user("alice", LocalDateTime.now().minusDays(1));
        when(authService.findByUsername("alice")).thenReturn(user);
        when(entitlementService.isExpired(user)).thenReturn(true);
        authenticate("alice");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request("GET", "/api/v1/chat/123/messages"), response, chain);

        assertEquals(403, response.getStatus());
        verify(chain, never()).doFilter(any(), any());
    }

    @Test
    void anonymousApiRequestPassesThrough() throws Exception {
        MockHttpServletRequest request = request("/api/v1/agents");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        assertEquals(200, response.getStatus());
        verify(chain).doFilter(request, response);
        verify(authService, never()).findByUsername(anyString());
    }

    @Test
    void nonApiRequestPassesThrough() throws Exception {
        MockHttpServletRequest request = request("/assets/app.js");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);
        authenticate("alice");

        filter.doFilter(request, response, chain);

        assertEquals(200, response.getStatus());
        verify(chain).doFilter(request, response);
        verify(authService, never()).findByUsername(anyString());
    }

    private static MockHttpServletRequest request(String uri) {
        return request("GET", uri);
    }

    private static MockHttpServletRequest request(String method, String uri) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, uri);
        request.setRequestURI(uri);
        return request;
    }

    private static UserEntity user(String username, LocalDateTime expiresAt) {
        UserEntity user = new UserEntity();
        user.setUsername(username);
        user.setExpiresAt(expiresAt);
        return user;
    }

    private static void authenticate(String username) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(
                        username,
                        null,
                        List.of(new SimpleGrantedAuthority("ROLE_user"))));
    }
}
