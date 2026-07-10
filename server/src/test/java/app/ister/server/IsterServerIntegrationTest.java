package app.ister.server;

import app.ister.core.config.RabbitReliabilityConfig;
import app.ister.core.enums.EventType;
import app.ister.core.enums.PlayState;
import app.ister.core.eventdata.PlaybackStatusData;
import app.ister.core.eventdata.ShowFoundData;
import app.ister.core.service.MessageSender;
import app.ister.core.status.PlaybackSessionRegistry;
import app.ister.core.status.RecentFailuresBuffer;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Boots the full application (all modules, Flyway migrations, AMQP listeners) against real
 * PostgreSQL and RabbitMQ containers, and verifies the failure path of the event pipeline:
 * a handler that throws is retried and the message ends up in the dead-letter queue instead
 * of being dropped. Skipped when no container runtime (docker/podman socket) is available.
 */
@SpringBootTest(properties = {
        // StartupTasks creates these directories; keep them inside the build's tmp dir
        "app.ister.server.tmp-dir=${java.io.tmpdir}/ister-it/tmp/",
        "app.ister.server.cache-dir=${java.io.tmpdir}/ister-it/cache/",
        // a minimal single-node library/directory config, as in a real deployment
        "app.ister.disk.libraries[0].name=it-lib",
        "app.ister.disk.libraries[0].type=SHOW",
        "app.ister.disk.directories[0].name=it-disk",
        "app.ister.disk.directories[0].path=${java.io.tmpdir}/ister-it/media/",
        "app.ister.disk.directories[0].library=it-lib",
        // force HandleShowFound past its no-API-key skip so it fails on the unknown show id
        "app.ister.server.TMDB.apikey=dummy-key-for-integration-test",
        // keep the retry backoff short so the dead-letter assertion is fast
        "spring.rabbitmq.listener.simple.retry.initial-interval=100ms",
        "spring.rabbitmq.listener.simple.retry.multiplier=1",
})
@Testcontainers(disabledWithoutDocker = true)
class IsterServerIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:18-alpine");

    @Container
    @ServiceConnection
    static final RabbitMQContainer RABBIT = new RabbitMQContainer("rabbitmq:3-alpine");

    @Autowired
    private MessageSender messageSender;

    @Autowired
    private RabbitTemplate rabbitTemplate;

    @Autowired
    private RecentFailuresBuffer recentFailuresBuffer;

    @Autowired
    private PlaybackSessionRegistry playbackSessionRegistry;

    @Test
    void contextLoadsWithRealDatabaseAndBroker() {
        // Full context: Flyway migrations applied, JPA mappings validated
        // (ddl-auto=validate), all @RabbitListener containers started. If any of that
        // failed the context would not refresh; these beans being wired proves it did.
        assertNotNull(messageSender);
        assertNotNull(rabbitTemplate);
    }

    @Test
    void failingEventIsRetriedAndEndsUpInDeadLetterQueue() {
        UUID unknownShowId = UUID.randomUUID();
        messageSender.sendShowFound(ShowFoundData.builder()
                .eventType(EventType.SHOW_FOUND)
                .showId(unknownShowId)
                .build());

        Message deadLetter = rabbitTemplate.receive(RabbitReliabilityConfig.DEAD_LETTER_QUEUE, 30_000);

        assertNotNull(deadLetter, "message should be republished to the dead-letter queue after retries");
        assertTrue(new String(deadLetter.getBody()).contains(unknownShowId.toString()),
                "dead-lettered message should carry the original payload");
        Object originalRoutingKey = deadLetter.getMessageProperties().getHeader("x-original-routingKey");
        assertEquals("app.ister.server.ShowFound", String.valueOf(originalRoutingKey),
                "original queue should be preserved for shoveling back");
        Object exceptionMessage = deadLetter.getMessageProperties().getHeader("x-exception-message");
        assertNotNull(exceptionMessage, "exception details should be preserved in the headers");

        // The failure must also be broadcast on the status fan-out exchange and land in
        // the in-memory recent-failures buffer that feeds the live activity view.
        awaitTrue(() -> recentFailuresBuffer.snapshot().stream()
                        .anyMatch(failure -> "app.ister.server.ShowFound".equals(failure.getQueue())),
                "failure should be visible in the recent-failures buffer via the status exchange");
    }

    @Test
    void playbackHeartbeatTravelsOverStatusExchangeIntoSessionRegistry() {
        UUID playQueueId = UUID.randomUUID();
        messageSender.sendStatus(PlaybackStatusData.builder()
                .playQueueId(playQueueId)
                .userId(UUID.randomUUID())
                .userName("integration-user")
                .progressInMilliseconds(1234)
                .playState(PlayState.PAUSED)
                .nodeName("some-other-node")
                .timestamp(java.time.Instant.now())
                .build());

        awaitTrue(() -> playbackSessionRegistry.snapshot().stream()
                        .anyMatch(session -> playQueueId.equals(session.getPlayQueueId())
                                && session.getPlayState() == PlayState.PAUSED),
                "heartbeat should arrive in the session registry via the status exchange");
    }

    private static void awaitTrue(java.util.function.BooleanSupplier condition, String message) {
        long deadline = System.currentTimeMillis() + 30_000;
        while (!condition.getAsBoolean()) {
            if (System.currentTimeMillis() > deadline) {
                throw new AssertionError(message);
            }
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError(message, e);
            }
        }
    }
}
