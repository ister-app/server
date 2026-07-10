package app.ister.core.status;

import app.ister.core.enums.PlayState;
import app.ister.core.eventdata.PlaybackStatusData;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class PlaybackSessionRegistryTest {

    private static final Instant START = Instant.parse("2026-07-10T12:00:00Z");
    private static final Duration TIMEOUT = Duration.ofSeconds(60);

    private AtomicReference<Instant> now;
    private PlaybackSessionRegistry registry;

    @BeforeEach
    void setUp() {
        now = new AtomicReference<>(START);
        Clock clock = new Clock() {
            @Override
            public ZoneOffset getZone() {
                return ZoneOffset.UTC;
            }

            @Override
            public Clock withZone(java.time.ZoneId zone) {
                return this;
            }

            @Override
            public Instant instant() {
                return now.get();
            }
        };
        registry = new PlaybackSessionRegistry(clock);
    }

    private static PlaybackStatusData heartbeat(UUID playQueueId, long progress, Instant timestamp) {
        return PlaybackStatusData.builder()
                .playQueueId(playQueueId)
                .userName("user")
                .progressInMilliseconds(progress)
                .playState(PlayState.PLAYING)
                .timestamp(timestamp)
                .build();
    }

    @Test
    void updateStoresSession() {
        UUID id = UUID.randomUUID();
        registry.update(heartbeat(id, 1000, START));

        List<PlaybackStatusData> snapshot = registry.snapshot();

        assertEquals(1, snapshot.size());
        assertEquals(1000, snapshot.getFirst().getProgressInMilliseconds());
    }

    @Test
    void newerHeartbeatReplacesOlder() {
        UUID id = UUID.randomUUID();
        registry.update(heartbeat(id, 1000, START));
        registry.update(heartbeat(id, 2000, START.plusSeconds(5)));

        assertEquals(2000, registry.snapshot().getFirst().getProgressInMilliseconds());
    }

    @Test
    void outOfOrderHeartbeatIsIgnored() {
        UUID id = UUID.randomUUID();
        registry.update(heartbeat(id, 2000, START.plusSeconds(5)));
        registry.update(heartbeat(id, 1000, START));

        assertEquals(2000, registry.snapshot().getFirst().getProgressInMilliseconds());
    }

    @Test
    void sessionExpiresWhenHeartbeatsStop() {
        registry.update(heartbeat(UUID.randomUUID(), 1000, START));

        now.set(START.plusSeconds(61));
        boolean expired = registry.removeExpired(TIMEOUT);

        assertTrue(expired);
        assertTrue(registry.snapshot().isEmpty());
    }

    @Test
    void sessionSurvivesWithinTimeout() {
        registry.update(heartbeat(UUID.randomUUID(), 1000, START));

        now.set(START.plusSeconds(59));
        boolean expired = registry.removeExpired(TIMEOUT);

        assertFalse(expired);
        assertEquals(1, registry.snapshot().size());
    }

    @Test
    void expiryUsesReceiveTimeNotProducerTimestamp() {
        // Producer clock 5 minutes ahead: with producer-based expiry this session would
        // never expire; with receive-side timestamps it does.
        registry.update(heartbeat(UUID.randomUUID(), 1000, START.plus(Duration.ofMinutes(5))));

        now.set(START.plusSeconds(61));

        assertTrue(registry.removeExpired(TIMEOUT));
    }
}
