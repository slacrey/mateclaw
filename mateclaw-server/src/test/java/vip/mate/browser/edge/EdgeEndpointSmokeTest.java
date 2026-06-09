package vip.mate.browser.edge;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import vip.mate.auth.pat.PersonalAccessTokenService;

import java.net.URI;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class EdgeEndpointSmokeTest {

    @LocalServerPort
    int port;

    @Autowired
    PersonalAccessTokenService patService;

    @Test
    void noAuthHeader_handshakeReturns401() {
        StandardWebSocketClient client = new StandardWebSocketClient();
        var uri = URI.create("ws://localhost:" + port + "/api/v1/browser/edge");
        var future = client.execute(new TextWebSocketHandler() {}, null, uri);

        assertThatThrownBy(future::get)
                .isInstanceOf(ExecutionException.class)
                .hasMessageContaining("401");
    }

    /**
     * Phase 3.1 W0.2 — the server MUST echo {@code mateclaw.edge.v1} (and never
     * the {@code bearer.*} token) when a browser-style client offers both as
     * subprotocols, otherwise the browser closes the socket. This boots the real
     * Spring handshake handler, so it directly verifies that
     * {@code EdgeWebSocketHandler implements SubProtocolCapable} is honored by the
     * plain {@code registry.addHandler(...)} registration.
     */
    @Test
    void subprotocolWithBearer_handshakeSucceeds_andEchoesOnlyEdgeProtocol() throws Exception {
        String pat = mintPat();
        StandardWebSocketClient client = new StandardWebSocketClient();
        var uri = URI.create("ws://localhost:" + port + "/api/v1/browser/edge");

        WebSocketHttpHeaders headers = new WebSocketHttpHeaders();
        headers.setSecWebSocketProtocol(List.of("mateclaw.edge.v1", "bearer." + pat));

        WebSocketSession session = client
                .execute(new TextWebSocketHandler() {}, headers, uri)
                .get(10, TimeUnit.SECONDS);
        try {
            assertThat(session.isOpen()).isTrue();
            // The negotiated protocol is the marker, NEVER the bearer entry.
            assertThat(session.getAcceptedProtocol()).isEqualTo("mateclaw.edge.v1");
            assertThat(session.getAcceptedProtocol()).doesNotContain("bearer.");
        } finally {
            session.close();
        }
    }

    /**
     * Backward-compat: the Native-Messaging bridge authenticates via the
     * Authorization header and offers NO subprotocol. The handshake must still
     * succeed and the server must add no {@code Sec-WebSocket-Protocol} response
     * header (accepted protocol null/blank).
     */
    @Test
    void authorizationHeader_noSubprotocol_handshakeSucceeds_andEchoesNothing() throws Exception {
        String pat = mintPat();
        StandardWebSocketClient client = new StandardWebSocketClient();
        var uri = URI.create("ws://localhost:" + port + "/api/v1/browser/edge");

        WebSocketHttpHeaders headers = new WebSocketHttpHeaders();
        headers.setBearerAuth(pat);

        WebSocketSession session = client
                .execute(new TextWebSocketHandler() {}, headers, uri)
                .get(10, TimeUnit.SECONDS);
        try {
            assertThat(session.isOpen()).isTrue();
            // No subprotocol offered → none echoed.
            assertThat(session.getAcceptedProtocol()).isNullOrEmpty();
        } finally {
            session.close();
        }
    }

    /** Mint a real, active PAT so EdgeAuthInterceptor's PAT cascade accepts it. */
    private String mintPat() {
        return patService.create(
                999_001L,
                "edge-smoke-test",
                "browser:edge",
                LocalDateTime.now().plusDays(1)).plaintext();
    }
}
