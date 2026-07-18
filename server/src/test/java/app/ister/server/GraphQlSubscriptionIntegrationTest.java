package app.ister.server;

import app.ister.core.enums.PlayState;
import app.ister.core.eventdata.PlaybackStatusData;
import app.ister.core.service.MessageSender;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Reproduction for "subscriptions deliver only the first frame": boots the full
 * application with a real server port, opens a genuine graphql-transport-ws
 * connection with the JDK WebSocket client (same protocol as the Flutter client),
 * subscribes to nowPlaying/serverActivity, then pushes status messages through the
 * RabbitMQ status fan-out exactly as production does, and asserts that follow-up
 * frames arrive on the socket.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "app.ister.server.tmp-dir=${java.io.tmpdir}/ister-ws-it/tmp/",
        "app.ister.server.cache-dir=${java.io.tmpdir}/ister-ws-it/cache/",
        "app.ister.disk.libraries[0].name=it-lib",
        "app.ister.disk.libraries[0].type=SHOW",
        "app.ister.disk.directories[0].name=it-disk",
        "app.ister.disk.directories[0].path=${java.io.tmpdir}/ister-ws-it/media/",
        "app.ister.disk.directories[0].library=it-lib",
})
@Testcontainers(disabledWithoutDocker = true)
class GraphQlSubscriptionIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:18-alpine");

    @Container
    @ServiceConnection
    static final RabbitMQContainer RABBIT = new RabbitMQContainer("rabbitmq:3-alpine");

    /** Accepts the literal token "test-token" and grants the "user" and "admin" roles, so the real
     * AuthenticationWebSocketInterceptor + @PreAuthorize chain is exercised unchanged (admin-only
     * actions such as subscribePodcast are reachable). Shared by the other integration tests in
     * this package via the component scan. */
    @TestConfiguration
    static class FakeJwtConfig {
        @Bean
        JwtDecoder jwtDecoder() {
            return token -> Jwt.withTokenValue(token)
                    .header("alg", "none")
                    .subject(UUID.randomUUID().toString())
                    .claim("roles", List.of("user", "admin"))
                    .issuedAt(Instant.now())
                    .expiresAt(Instant.now().plusSeconds(3600))
                    .build();
        }
    }

    @LocalServerPort
    int port;

    @Autowired
    MessageSender messageSender;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private WebSocket webSocket;
    private final BlockingQueue<JsonNode> messages = new LinkedBlockingQueue<>();

    @AfterEach
    void closeSocket() {
        if (webSocket != null && !webSocket.isOutputClosed()) {
            webSocket.sendClose(WebSocket.NORMAL_CLOSURE, "done").join();
        }
    }

    @Test
    void nowPlayingSubscriptionKeepsDeliveringFramesAfterTheFirst() throws Exception {
        connectAndInit();
        subscribe("sub-1", "subscription { nowPlaying { playQueueId playState progressInMilliseconds } }");

        // Frame 1: the replay sink immediately delivers the latest (seeded, empty) list.
        JsonNode first = awaitNext("sub-1", 15);
        assertEquals(0, first.at("/payload/data/nowPlaying").size(),
                "first frame should be the replayed (empty) current state");

        // Now a playback heartbeat travels over the real status exchange, exactly as in
        // production: RabbitMQ -> StatusEventListener.onPlayback -> broadcaster sink.
        UUID playQueueId = UUID.randomUUID();
        messageSender.sendStatus(playbackStatus(playQueueId, PlayState.PLAYING, 1000));

        JsonNode second = awaitNext("sub-1", 15);
        assertEquals(playQueueId.toString(), second.at("/payload/data/nowPlaying/0/playQueueId").asText(),
                "second frame should carry the session emitted after subscribing");

        // And a third frame, to prove it keeps flowing (the reported symptom is silence
        // after the first frame).
        messageSender.sendStatus(playbackStatus(playQueueId, PlayState.PAUSED, 2000));
        JsonNode third = awaitNextMatching("sub-1", 15,
                node -> "PAUSED".equals(node.at("/payload/data/nowPlaying/0/playState").asText()));
        assertNotNull(third, "third frame with the PAUSED update should arrive");
    }

    @Test
    void serverActivitySubscriptionDeliversFrames() throws Exception {
        connectAndInit();
        subscribe("sub-2", "subscription { serverActivity { type nodeName } }");

        // First prove the subscriber is attached: the periodic node-activity publisher
        // emits every ~2s and the sink replays the latest value, so some frame arrives
        // promptly. (Sending the failure before attachment would race the 1-slot replay
        // buffer against those periodic emissions.)
        awaitNext("sub-2", 15);

        // From here on, every event must produce a frame.
        messageSender.sendStatus(app.ister.core.eventdata.EventFailureStatusData.builder()
                .nodeName("it-node").timestamp(Instant.now())
                .queue("app.ister.server.MovieFound").errorMessage("boom").build());
        JsonNode frame = awaitNextMatching("sub-2", 15,
                node -> "FAILURE".equals(node.at("/payload/data/serverActivity/type").asText()));
        assertEquals("FAILURE", frame.at("/payload/data/serverActivity/type").asText());

        // Once the subscriber is attached, every further event must produce a frame.
        messageSender.sendStatus(app.ister.core.eventdata.EventFailureStatusData.builder()
                .nodeName("it-node").timestamp(Instant.now())
                .queue("app.ister.server.MovieFound").errorMessage("boom-final").build());
        JsonNode frame2 = awaitNextMatching("sub-2", 15,
                node -> "FAILURE".equals(node.at("/payload/data/serverActivity/type").asText()));
        assertNotNull(frame2, "second serverActivity frame should arrive");
    }

    private static PlaybackStatusData playbackStatus(UUID playQueueId, PlayState state, int progress) {
        return PlaybackStatusData.builder()
                .playQueueId(playQueueId)
                .userId(UUID.randomUUID())
                .userName("ws-it-user")
                .progressInMilliseconds(progress)
                .playState(state)
                .nodeName("it-node")
                .timestamp(Instant.now())
                .build();
    }

    private void connectAndInit() throws Exception {
        StringBuilder partial = new StringBuilder();
        WebSocket.Listener listener = new WebSocket.Listener() {
            @Override
            public CompletionStage<?> onText(WebSocket ws, CharSequence data, boolean last) {
                partial.append(data);
                if (last) {
                    try {
                        messages.add(MAPPER.readTree(partial.toString()));
                    } catch (Exception e) {
                        throw new IllegalStateException(e);
                    }
                    partial.setLength(0);
                }
                ws.request(1);
                return null;
            }

            @Override
            public CompletionStage<?> onClose(WebSocket ws, int statusCode, String reason) {
                messages.add(MAPPER.createObjectNode().put("type", "__closed").put("code", statusCode).put("reason", reason));
                return null;
            }
        };
        webSocket = HttpClient.newHttpClient().newWebSocketBuilder()
                .subprotocols("graphql-transport-ws")
                .buildAsync(URI.create("ws://localhost:" + port + "/graphql"), listener)
                .get(10, TimeUnit.SECONDS);

        send("{\"type\":\"connection_init\",\"payload\":{\"Authorization\":\"Bearer test-token\"}}");
        JsonNode ack = awaitType("connection_ack", 10);
        assertNotNull(ack, "connection_ack expected");
    }

    private void subscribe(String id, String query) throws Exception {
        var payload = MAPPER.createObjectNode();
        payload.put("query", query);
        var msg = MAPPER.createObjectNode();
        msg.put("id", id);
        msg.put("type", "subscribe");
        msg.set("payload", payload);
        send(MAPPER.writeValueAsString(msg));
    }

    private void send(String text) {
        webSocket.sendText(text, true).join();
    }

    /** Waits for a "next" frame for the given subscription id, skipping pings/pongs.
     * Fails the test on "error", "complete" or socket close so a terminal frame is
     * reported as such instead of as a timeout. */
    private JsonNode awaitNext(String id, int seconds) throws InterruptedException {
        return awaitNextMatching(id, seconds, node -> true);
    }

    private JsonNode awaitNextMatching(String id, int seconds, java.util.function.Predicate<JsonNode> predicate)
            throws InterruptedException {
        JsonNode node = tryAwaitNextMatching(id, seconds, predicate);
        assertNotNull(node, "timed out waiting for a 'next' frame for " + id);
        return node;
    }

    private JsonNode tryAwaitNextMatching(String id, int seconds, java.util.function.Predicate<JsonNode> predicate)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + seconds * 1000L;
        while (true) {
            long remaining = deadline - System.currentTimeMillis();
            if (remaining <= 0) {
                return null;
            }
            JsonNode node = messages.poll(remaining, TimeUnit.MILLISECONDS);
            if (node == null) {
                return null;
            }
            String type = node.path("type").asText();
            switch (type) {
                case "next" -> {
                    if (id.equals(node.path("id").asText()) && predicate.test(node)) {
                        return node;
                    }
                }
                case "ping" -> webSocket.sendText("{\"type\":\"pong\"}", true).join();
                case "pong" -> { /* ignore */ }
                case "error", "complete", "__closed" ->
                        throw new AssertionError("terminal frame received instead of 'next': " + node);
                default -> { /* ignore */ }
            }
        }
    }

    /** Waits for a frame of the given type, skipping keep-alive pings; any other
     * frame (error, close, unexpected type) fails the test immediately. */
    private JsonNode awaitType(String type, int seconds) throws InterruptedException {
        long deadline = System.currentTimeMillis() + seconds * 1000L;
        while (true) {
            long remaining = deadline - System.currentTimeMillis();
            assertTrue(remaining > 0, "timed out waiting for a '" + type + "' frame");
            JsonNode node = messages.poll(remaining, TimeUnit.MILLISECONDS);
            assertNotNull(node, "timed out waiting for a '" + type + "' frame");
            String actual = node.path("type").asText();
            if (type.equals(actual)) {
                return node;
            }
            if ("ping".equals(actual)) {
                webSocket.sendText("{\"type\":\"pong\"}", true).join();
                continue;
            }
            if (!"pong".equals(actual)) {
                throw new AssertionError("expected '" + type + "' frame but received: " + node);
            }
        }
    }
}
