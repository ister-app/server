package app.ister.server;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The websocket handshake itself, with the raw header combinations a browser and a
 * reverse proxy produce. The server speaks plain HTTP; the public origin is https.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "app.ister.server.tmp-dir=${java.io.tmpdir}/ister-wsh-it/tmp/",
        "app.ister.server.cache-dir=${java.io.tmpdir}/ister-wsh-it/cache/",
        "app.ister.disk.libraries[0].name=it-lib",
        "app.ister.disk.libraries[0].type=SHOW",
        "app.ister.disk.directories[0].name=it-disk",
        "app.ister.disk.directories[0].path=${java.io.tmpdir}/ister-wsh-it/media/",
        "app.ister.disk.directories[0].library=it-lib",
        "server.forward-headers-strategy=framework",
        // The public name the proxy serves under; the app itself speaks plain HTTP.
        "app.ister.server.url=https://media.droogers.cloud/api",
})
@Testcontainers(disabledWithoutDocker = true)
class GraphQlWebSocketHandshakeIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:18-alpine");

    @Container
    @ServiceConnection
    static final RabbitMQContainer RABBIT = new RabbitMQContainer("rabbitmq:3-alpine");

    private static final String PUBLIC_HOST = "media.droogers.cloud";

    @LocalServerPort
    int port;

    @Test
    void noOrigin() throws Exception {
        assertEquals(101, handshake(List.of()));
    }

    @Test
    void httpOrigin() throws Exception {
        assertEquals(101, handshake(List.of("Origin: http://" + PUBLIC_HOST)));
    }

    @Test
    void httpsOrigin() throws Exception {
        assertEquals(101, handshake(List.of("Origin: https://" + PUBLIC_HOST)));
    }

    @Test
    void httpsOriginBehindProxy() throws Exception {
        assertEquals(101, handshake(List.of(
                "Origin: https://" + PUBLIC_HOST,
                "X-Forwarded-Proto: https")));
    }

    @Test
    void httpsOriginBehindProxyWithFullForwardedHeaders() throws Exception {
        assertEquals(101, handshake(List.of(
                "Origin: https://" + PUBLIC_HOST,
                "X-Forwarded-Proto: https",
                "X-Forwarded-Host: " + PUBLIC_HOST,
                "X-Forwarded-Port: 443")));
    }

    @Test
    void foreignOriginIsStillRejected() throws Exception {
        assertEquals(403, handshake(List.of("Origin: https://evil.example.com")));
    }

    private int handshake(List<String> extraHeaders) throws Exception {
        StringBuilder request = new StringBuilder()
                .append("GET /graphql HTTP/1.1\r\n")
                .append("Host: ").append(PUBLIC_HOST).append("\r\n")
                .append("Connection: Upgrade\r\n")
                .append("Upgrade: websocket\r\n")
                .append("Sec-WebSocket-Version: 13\r\n")
                .append("Sec-WebSocket-Protocol: graphql-transport-ws\r\n")
                .append("Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\n");
        for (String header : extraHeaders) {
            request.append(header).append("\r\n");
        }
        request.append("\r\n");

        try (Socket socket = new Socket("localhost", port)) {
            OutputStream out = socket.getOutputStream();
            out.write(request.toString().getBytes(StandardCharsets.UTF_8));
            out.flush();
            BufferedReader in = new BufferedReader(
                    new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            String statusLine = in.readLine();
            return Integer.parseInt(statusLine.split(" ")[1]);
        }
    }
}
