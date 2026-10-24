package app.ister.api.controller;

import app.ister.core.entity.DeviceEntity;
import app.ister.core.service.DeviceService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Resolves the user-facing name of a session's device for the now-playing heartbeats. Short-lived
 * (owner, device) → name cache: heartbeats arrive every few seconds per session and the name may
 * not cost a query each time; a rename shows up within a minute. Shared by the play-queue and the
 * activity heartbeat paths.
 */
@Component
@RequiredArgsConstructor
public class DeviceNameResolver {

    private static final Duration CACHE_TTL = Duration.ofSeconds(60);

    private final DeviceService deviceService;
    private final Map<String, CacheEntry> cache = new ConcurrentHashMap<>();

    private record CacheEntry(String name, Instant expiresAt) {
    }

    /** Cached lookup of the owner's device name; null for clients that report no device id. */
    public String nameOf(UUID ownerId, UUID deviceId) {
        if (deviceId == null) {
            return null;
        }
        String key = ownerId + ":" + deviceId;
        CacheEntry cached = cache.get(key);
        if (cached != null && cached.expiresAt().isAfter(Instant.now())) {
            return cached.name();
        }
        String name = deviceService.findOwned(ownerId, deviceId).map(DeviceEntity::getName).orElse(null);
        cache.put(key, new CacheEntry(name, Instant.now().plus(CACHE_TTL)));
        return name;
    }
}
