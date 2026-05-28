package vip.mate.browser.edge.auth;

import io.jsonwebtoken.Claims;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;
import vip.mate.auth.pat.PersonalAccessTokenEntity;
import vip.mate.auth.pat.PersonalAccessTokenService;
import vip.mate.auth.service.AuthService;

import java.util.Map;
import java.util.Optional;

/**
 * Validates JWT or PAT on the /api/v1/browser/edge WebSocket handshake.
 * On success stashes {@link EdgePrincipal} under {@code EDGE_PRINCIPAL}.
 *
 * <p>Handshake-time auth failures return HTTP 401 (per edge-protocol spec).
 * Post-upgrade auth loss (token revoked mid-session, WS close 4401) is NOT
 * implemented in Phase 1.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class EdgeAuthInterceptor implements HandshakeInterceptor {

    public static final String ATTR_PRINCIPAL = "EDGE_PRINCIPAL";

    private final AuthService authService;
    private final PersonalAccessTokenService patService;

    @Override
    public boolean beforeHandshake(ServerHttpRequest request,
                                   ServerHttpResponse response,
                                   WebSocketHandler handler,
                                   Map<String, Object> attributes) {
        String auth = request.getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
        if (auth == null || !auth.startsWith("Bearer ")) {
            response.setStatusCode(HttpStatus.UNAUTHORIZED);
            return false;
        }
        String token = auth.substring("Bearer ".length()).trim();

        // JWT first — cheaper (signature verify, no DB hit).
        Claims claims = authService.parseClaims(token);
        if (claims != null && claims.getSubject() != null) {
            attributes.put(ATTR_PRINCIPAL, new EdgePrincipal(claims.getSubject(), "jwt"));
            return true;
        }

        // PAT fallback — DB lookup.
        // TODO(phase-3): once per-scope enforcement RFC lands, also check
        //   entity.getScopes().contains("browser:edge") and return 403 if
        //   the token is active but lacks the scope.
        Optional<PersonalAccessTokenEntity> pat = patService.findActiveByPlaintext(token);
        if (pat.isPresent()) {
            attributes.put(ATTR_PRINCIPAL, new EdgePrincipal(pat.get().getUserId().toString(), "pat"));
            return true;
        }

        log.debug("[edge] rejected handshake — bad/expired token");
        response.setStatusCode(HttpStatus.UNAUTHORIZED);
        return false;
    }

    @Override
    public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
                               WebSocketHandler handler, Exception exception) {
        // no-op
    }
}
