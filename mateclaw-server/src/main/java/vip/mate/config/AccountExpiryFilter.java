package vip.mate.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import vip.mate.auth.model.UserEntity;
import vip.mate.auth.service.AccountEntitlementService;
import vip.mate.auth.service.AuthService;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

@Component
@RequiredArgsConstructor
public class AccountExpiryFilter extends OncePerRequestFilter {

    private static final Set<String> ALLOWED_API_PATHS = Set.of(
            "/api/v1/auth/login",
            "/api/v1/auth/register",
            "/api/v1/auth/me",
            "/api/v1/workspaces",
            "/api/v1/settings/language",
            "/api/v1/chat/stream",
            "/api/v1/talk/ws"
    );
    private static final Set<String> ALLOWED_API_PREFIXES = Set.of(
            "/api/v1/setup/",
            "/api/v1/channels/webhook/",
            "/api/v1/channels/webchat/",
            "/api/v1/files/generated/"
    );
    private static final Pattern WORKSPACE_ACCESS_PATH =
            Pattern.compile("^/api/v1/workspaces/[^/]+/access$");
    private static final Pattern AGENT_CHAT_STREAM_PATH =
            Pattern.compile("^/api/v1/agents/[^/]+/chat/stream$");
    private static final Pattern CHAT_STOP_PATH =
            Pattern.compile("^/api/v1/chat/[^/]+/stop$");

    private final AuthService authService;
    private final AccountEntitlementService entitlementService;
    private final ObjectMapper objectMapper;

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String path = request.getRequestURI();
        if (!path.startsWith("/api/")
                || isAllowedPath(path)
                || !isAuthenticatedUser()) {
            filterChain.doFilter(request, response);
            return;
        }

        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        UserEntity user = authService.findByUsername(auth.getName());
        if (user != null && entitlementService.isExpired(user)) {
            writeExpiredResponse(response, user);
            return;
        }

        filterChain.doFilter(request, response);
    }

    private boolean isAllowedPath(String path) {
        return ALLOWED_API_PATHS.contains(path)
                || ALLOWED_API_PREFIXES.stream().anyMatch(path::startsWith)
                || WORKSPACE_ACCESS_PATH.matcher(path).matches()
                || AGENT_CHAT_STREAM_PATH.matcher(path).matches()
                || CHAT_STOP_PATH.matcher(path).matches();
    }

    private boolean isAuthenticatedUser() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null
                && auth.isAuthenticated()
                && !(auth instanceof AnonymousAuthenticationToken);
    }

    private void writeExpiredResponse(HttpServletResponse response, UserEntity user) throws IOException {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("reason", "ACCOUNT_EXPIRED");
        if (user.getExpiresAt() != null) {
            data.put("expiresAt", user.getExpiresAt().toString());
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", HttpServletResponse.SC_FORBIDDEN);
        body.put("msg", "账号已过期");
        body.put("data", data);

        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setContentType("application/json;charset=UTF-8");
        objectMapper.writeValue(response.getWriter(), body);
    }
}
