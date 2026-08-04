package app.ister.core.status;

import app.ister.core.eventdata.DevicePresenceData;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DevicePresenceRegistryTest {

    private static final Instant START = Instant.parse("2026-08-04T12:00:00Z");
    private static final Duration TIMEOUT = Duration.ofSeconds(60);

    private AtomicReference<Instant> now;
    private DevicePresenceRegistry registry;

    private final UUID user = UUID.randomUUID();
    private final UUID otherUser = UUID.randomUUID();
    private final UUID device = UUID.randomUUID();

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
        registry = new DevicePresenceRegistry(clock);
    }

    private DevicePresenceData ping(UUID ownerUserId, UUID deviceId) {
        return DevicePresenceData.builder()
                .ownerUserId(ownerUserId)
                .deviceId(deviceId)
                .timestamp(now.get())
                .build();
    }

    @Test
    void pingedDeviceIsOnline() {
        registry.update(ping(user, device));
        assertTrue(registry.isOnline(user, device));
        assertEquals(Set.of(device), registry.onlineDeviceIds(user));
    }

    @Test
    void presenceIsScopedPerUser() {
        // Device ids are only unique per user: the same id pinged by one user must not
        // make the other user's device look online.
        registry.update(ping(user, device));
        assertFalse(registry.isOnline(otherUser, device));
        assertEquals(Set.of(), registry.onlineDeviceIds(otherUser));
    }

    @Test
    void staleDeviceExpires() {
        registry.update(ping(user, device));
        now.set(START.plusSeconds(61));
        assertTrue(registry.removeExpired(TIMEOUT));
        assertFalse(registry.isOnline(user, device));
    }

    @Test
    void freshPingSurvivesSweep() {
        registry.update(ping(user, device));
        now.set(START.plusSeconds(30));
        registry.update(ping(user, device));
        now.set(START.plusSeconds(70));
        registry.removeExpired(TIMEOUT);
        assertTrue(registry.isOnline(user, device));
    }

    @Test
    void ignoresIncompletePayload() {
        registry.update(DevicePresenceData.builder().ownerUserId(user).build());
        registry.update(DevicePresenceData.builder().deviceId(device).build());
        assertEquals(Set.of(), registry.onlineDeviceIds(user));
    }
}
