package app.ister.core.status;

import app.ister.core.eventdata.FollowerStatusData;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class FollowerRegistryTest {

    private static final Instant START = Instant.parse("2026-07-10T12:00:00Z");
    private static final Duration TIMEOUT = Duration.ofSeconds(60);

    private AtomicReference<Instant> now;
    private FollowerRegistry registry;

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
        registry = new FollowerRegistry(clock);
    }

    private static FollowerStatusData follow(UUID playQueueId, String deviceId, UUID userId, boolean active) {
        return FollowerStatusData.builder()
                .playQueueId(playQueueId)
                .deviceId(deviceId)
                .userId(userId)
                .active(active)
                .timestamp(Instant.now())
                .build();
    }

    @Test
    void twoDevicesOfOneUserCountAsOneUserButTwoDevices() {
        UUID queueId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        registry.update(follow(queueId, "device-a", userId, true));
        registry.update(follow(queueId, "device-b", userId, true));

        assertEquals(Set.of(userId), registry.activeUserIds(queueId));
        assertEquals(2, registry.deviceCount(queueId));
    }

    @Test
    void distinctUsersAreAllReported() {
        UUID queueId = UUID.randomUUID();
        UUID userA = UUID.randomUUID();
        UUID userB = UUID.randomUUID();
        registry.update(follow(queueId, "device-a", userA, true));
        registry.update(follow(queueId, "device-b", userB, true));

        assertEquals(Set.of(userA, userB), registry.activeUserIds(queueId));
    }

    @Test
    void deregistrationRemovesOnlyTheMatchingUsersDevice() {
        UUID queueId = UUID.randomUUID();
        UUID owner = UUID.randomUUID();
        UUID other = UUID.randomUUID();
        registry.update(follow(queueId, "device-a", owner, true));

        // Another user may not deregister someone else's device by reusing its id.
        registry.update(follow(queueId, "device-a", other, false));
        assertEquals(Set.of(owner), registry.activeUserIds(queueId));

        registry.update(follow(queueId, "device-a", owner, false));
        assertEquals(Set.of(), registry.activeUserIds(queueId));
        assertEquals(0, registry.deviceCount(queueId));
    }

    @Test
    void followersExpireWithoutHeartbeat() {
        UUID queueId = UUID.randomUUID();
        registry.update(follow(queueId, "device-a", UUID.randomUUID(), true));

        now.set(START.plusSeconds(30));
        assertFalse(registry.removeExpired(TIMEOUT));
        assertEquals(1, registry.deviceCount(queueId));

        now.set(START.plusSeconds(61));
        assertTrue(registry.removeExpired(TIMEOUT));
        assertEquals(0, registry.deviceCount(queueId));
        assertEquals(Set.of(), registry.activeUserIds(queueId));
    }

    @Test
    void heartbeatKeepsAFollowerAlive() {
        UUID queueId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        registry.update(follow(queueId, "device-a", userId, true));

        now.set(START.plusSeconds(40));
        registry.update(follow(queueId, "device-a", userId, true));

        now.set(START.plusSeconds(70));
        registry.removeExpired(TIMEOUT);
        assertEquals(Set.of(userId), registry.activeUserIds(queueId));
    }

    @Test
    void unknownQueueIsEmpty() {
        assertEquals(Set.of(), registry.activeUserIds(UUID.randomUUID()));
        assertEquals(0, registry.deviceCount(UUID.randomUUID()));
    }
}
