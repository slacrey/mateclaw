package vip.mate.browser.edge;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.net.URI;
import java.util.concurrent.ExecutionException;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class EdgeEndpointSmokeTest {

    @LocalServerPort
    int port;

    @Test
    void noAuthHeader_handshakeReturns401() {
        StandardWebSocketClient client = new StandardWebSocketClient();
        var uri = URI.create("ws://localhost:" + port + "/api/v1/browser/edge");
        var future = client.execute(new TextWebSocketHandler() {}, null, uri);

        assertThatThrownBy(future::get)
                .isInstanceOf(ExecutionException.class)
                .hasMessageContaining("401");
    }
}
