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

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
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

    // ── Phase 3.1: token via Sec-WebSocket-Protocol (browser direct mode) ──────

    @Test
    void beforeHandshake_validPatViaSubprotocol_authorizesAndStashesPrincipal() throws Exception {
        // No Authorization header — token rides as a subprotocol entry.
        when(authService.parseClaims("mc_validpat")).thenReturn(null);
        PersonalAccessTokenEntity entity = new PersonalAccessTokenEntity();
        entity.setUserId(42L);
        entity.setEnabled(true);
        when(patService.findActiveByPlaintext("mc_validpat")).thenReturn(Optional.of(entity));

        var req = subprotocolReq("mateclaw.edge.v1, bearer.mc_validpat");
        var attrs = new HashMap<String, Object>();
        boolean ok = interceptor.beforeHandshake(req, httpResp(), mock(WebSocketHandler.class), attrs);

        assertThat(ok).isTrue();
        EdgePrincipal p = (EdgePrincipal) attrs.get("EDGE_PRINCIPAL");
        assertThat(p.subject()).isEqualTo("42");
        assertThat(p.scheme()).isEqualTo("pat");
        // Authorization-only token resolution must NOT have been consulted.
        verify(patService).findActiveByPlaintext("mc_validpat");
    }

    @Test
    void beforeHandshake_jwtWithDotsViaSubprotocol_parsesTokenIntact() throws Exception {
        // JWTs contain dots — the bearer. prefix must be stripped with substring,
        // not by splitting on '.', so the full "eyJ.aaa.bbb" survives.
        String jwt = "eyJ.aaa.bbb";
        Claims c = new DefaultClaims(Map.of("sub", "carol"));
        when(authService.parseClaims(jwt)).thenReturn(c);

        var req = subprotocolReq("mateclaw.edge.v1, bearer." + jwt);
        var attrs = new HashMap<String, Object>();
        boolean ok = interceptor.beforeHandshake(req, httpResp(), mock(WebSocketHandler.class), attrs);

        assertThat(ok).isTrue();
        EdgePrincipal p = (EdgePrincipal) attrs.get("EDGE_PRINCIPAL");
        assertThat(p.subject()).isEqualTo("carol");
        assertThat(p.scheme()).isEqualTo("jwt");
        verify(authService).parseClaims(jwt);
    }

    @Test
    void beforeHandshake_badTokenViaSubprotocol_returnsHttp401() throws Exception {
        when(authService.parseClaims("mc_bad")).thenReturn(null);
        when(patService.findActiveByPlaintext("mc_bad")).thenReturn(Optional.empty());

        var req = subprotocolReq("mateclaw.edge.v1, bearer.mc_bad");
        var resp = httpResp();
        boolean ok = interceptor.beforeHandshake(req, resp, mock(WebSocketHandler.class), new HashMap<>());

        assertThat(ok).isFalse();
        assertThat(((ServletServerHttpResponse) resp).getServletResponse().getStatus()).isEqualTo(401);
    }

    @Test
    void beforeHandshake_subprotocolWithoutBearerEntry_returnsHttp401() throws Exception {
        // Only the protocol marker, no bearer.<token> — nothing to authenticate.
        var req = subprotocolReq("mateclaw.edge.v1");
        var resp = httpResp();
        boolean ok = interceptor.beforeHandshake(req, resp, mock(WebSocketHandler.class), new HashMap<>());

        assertThat(ok).isFalse();
        assertThat(((ServletServerHttpResponse) resp).getServletResponse().getStatus()).isEqualTo(401);
        verifyNoInteractions(authService);
        verifyNoInteractions(patService);
    }

    @Test
    void beforeHandshake_authorizationHeaderWinsOverSubprotocol() throws Exception {
        // When both are present the Authorization header (NH bridge) is used.
        Claims c = new DefaultClaims(Map.of("sub", "header-user"));
        when(authService.parseClaims("hdr-jwt")).thenReturn(c);

        var http = new MockHttpServletRequest("GET", "/api/v1/browser/edge");
        http.addHeader(HttpHeaders.AUTHORIZATION, "Bearer hdr-jwt");
        http.addHeader("Sec-WebSocket-Protocol", "mateclaw.edge.v1, bearer.mc_other");
        var req = new ServletServerHttpRequest(http);

        var attrs = new HashMap<String, Object>();
        boolean ok = interceptor.beforeHandshake(req, httpResp(), mock(WebSocketHandler.class), attrs);

        assertThat(ok).isTrue();
        assertThat(((EdgePrincipal) attrs.get("EDGE_PRINCIPAL")).subject()).isEqualTo("header-user");
        verify(authService).parseClaims("hdr-jwt");
        // The subprotocol token must never be looked up when the header authenticates.
        verify(patService, never()).findActiveByPlaintext("mc_other");
    }

    // ── Direct unit tests of the subprotocol parsing helper ───────────────────

    @Test
    void extractBearerFromSubprotocol_stripsPrefix_keepingJwtDots() {
        assertThat(EdgeAuthInterceptor.extractBearerFromSubprotocol(
                List.of("mateclaw.edge.v1, bearer.eyJ.aaa.bbb")))
                .isEqualTo("eyJ.aaa.bbb");
    }

    @Test
    void extractBearerFromSubprotocol_handlesMultipleHeaderValues() {
        // Servlet container may surface a repeated header as multiple values.
        assertThat(EdgeAuthInterceptor.extractBearerFromSubprotocol(
                Arrays.asList("mateclaw.edge.v1", "bearer.mc_split")))
                .isEqualTo("mc_split");
    }

    @Test
    void extractBearerFromSubprotocol_returnsNull_whenNoBearerEntry() {
        assertThat(EdgeAuthInterceptor.extractBearerFromSubprotocol(
                List.of("mateclaw.edge.v1"))).isNull();
        assertThat(EdgeAuthInterceptor.extractBearerFromSubprotocol(null)).isNull();
        assertThat(EdgeAuthInterceptor.extractBearerFromSubprotocol(List.of())).isNull();
    }

    @Test
    void extractBearerFromSubprotocol_returnsNull_whenBearerEntryIsEmpty() {
        assertThat(EdgeAuthInterceptor.extractBearerFromSubprotocol(
                List.of("mateclaw.edge.v1, bearer."))).isNull();
    }

    private ServerHttpRequest httpReq(String auth) {
        var http = new MockHttpServletRequest("GET", "/api/v1/browser/edge");
        if (auth != null) http.addHeader(HttpHeaders.AUTHORIZATION, auth);
        return new ServletServerHttpRequest(http);
    }

    private ServerHttpRequest subprotocolReq(String secWebSocketProtocol) {
        var http = new MockHttpServletRequest("GET", "/api/v1/browser/edge");
        http.addHeader("Sec-WebSocket-Protocol", secWebSocketProtocol);
        return new ServletServerHttpRequest(http);
    }

    private ServerHttpResponse httpResp() {
        return new ServletServerHttpResponse(new MockHttpServletResponse());
    }
}
