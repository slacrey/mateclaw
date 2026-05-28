package vip.mate.browser.edge.auth;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.impl.DefaultClaims;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.http.server.ServletServerHttpResponse;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.socket.WebSocketHandler;
import vip.mate.auth.pat.PersonalAccessTokenEntity;
import vip.mate.auth.pat.PersonalAccessTokenService;
import vip.mate.auth.service.AuthService;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class EdgeAuthInterceptorTest {

    private AuthService authService;
    private PersonalAccessTokenService patService;
    private EdgeAuthInterceptor interceptor;

    @BeforeEach
    void setUp() {
        authService = mock(AuthService.class);
        patService = mock(PersonalAccessTokenService.class);
        interceptor = new EdgeAuthInterceptor(authService, patService);
    }

    @Test
    void beforeHandshake_validJwt_storesEdgePrincipalWithSubject() throws Exception {
        Claims c = new DefaultClaims(Map.of("sub", "alice"));
        when(authService.parseClaims("good-jwt")).thenReturn(c);

        var req = httpReq("Bearer good-jwt");
        var resp = httpResp();
        Map<String, Object> attrs = new HashMap<>();

        boolean ok = interceptor.beforeHandshake(req, resp, mock(WebSocketHandler.class), attrs);

        assertThat(ok).isTrue();
        EdgePrincipal p = (EdgePrincipal) attrs.get("EDGE_PRINCIPAL");
        assertThat(p.subject()).isEqualTo("alice");
        assertThat(p.scheme()).isEqualTo("jwt");
    }

    @Test
    void beforeHandshake_validPat_storesEdgePrincipalWithUserIdAsString() throws Exception {
        // JWT path miss
        when(authService.parseClaims("mt_pat_xyz")).thenReturn(null);
        // PAT path hit
        PersonalAccessTokenEntity entity = new PersonalAccessTokenEntity();
        entity.setUserId(77L);
        entity.setEnabled(true);
        when(patService.findActiveByPlaintext("mt_pat_xyz")).thenReturn(Optional.of(entity));

        var req = httpReq("Bearer mt_pat_xyz");
        var attrs = new HashMap<String, Object>();
        boolean ok = interceptor.beforeHandshake(req, httpResp(), mock(WebSocketHandler.class), attrs);

        assertThat(ok).isTrue();
        EdgePrincipal p = (EdgePrincipal) attrs.get("EDGE_PRINCIPAL");
        assertThat(p.subject()).isEqualTo("77");
        assertThat(p.scheme()).isEqualTo("pat");
    }

    @Test
    void beforeHandshake_missingAuthHeader_returnsHttp401() throws Exception {
        var req = httpReq(null);
        var resp = httpResp();
        boolean ok = interceptor.beforeHandshake(req, resp, mock(WebSocketHandler.class), new HashMap<>());

        assertThat(ok).isFalse();
        assertThat(((ServletServerHttpResponse) resp).getServletResponse().getStatus()).isEqualTo(401);
    }

    @Test
    void beforeHandshake_badToken_returnsHttp401() throws Exception {
        when(authService.parseClaims("bad-jwt")).thenReturn(null);
        when(patService.findActiveByPlaintext("bad-jwt")).thenReturn(Optional.empty());

        var req = httpReq("Bearer bad-jwt");
        var resp = httpResp();
        boolean ok = interceptor.beforeHandshake(req, resp, mock(WebSocketHandler.class), new HashMap<>());

        assertThat(ok).isFalse();
        assertThat(((ServletServerHttpResponse) resp).getServletResponse().getStatus()).isEqualTo(401);
    }

    private ServerHttpRequest httpReq(String auth) {
        var http = new MockHttpServletRequest("GET", "/api/v1/browser/edge");
        if (auth != null) http.addHeader(HttpHeaders.AUTHORIZATION, auth);
        return new ServletServerHttpRequest(http);
    }

    private ServerHttpResponse httpResp() {
        return new ServletServerHttpResponse(new MockHttpServletResponse());
    }
}
