package app.ister.core.service;

import app.ister.core.entity.DeviceEntity;
import app.ister.core.entity.UserEntity;
import app.ister.core.enums.DevicePlatform;
import app.ister.core.repository.DeviceRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Registered client devices: registration upsert, presence pings, rename/remove and listing.
 *
 * <p>{@code deviceId} is the client-generated install UUID and only unique per user, so every
 * lookup is scoped by the authenticated owner. A device the caller does not own is treated as
 * absent (deny-as-not-found). Liveness ("online") is not decided here — that is the in-memory
 * presence registry's job; this service only persists the durable {@code lastSeenAt}, throttled
 * so the ~20s presence heartbeat does not turn into a row write per ping.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class DeviceService {
    /** Persist a new lastSeenAt only when the stored one is at least this stale. */
    private static final Duration LAST_SEEN_WRITE_INTERVAL = Duration.ofSeconds(60);

    private final DeviceRepository deviceRepository;
    private final UserService userService;

    /**
     * Registers (or refreshes) the caller's device. On an existing row the user-chosen name is
     * kept — the client-supplied name only seeds the first registration — while platform and
     * lastSeenAt are refreshed.
     */
    @Transactional
    public DeviceEntity register(Authentication authentication, UUID deviceId, String name, DevicePlatform platform) {
        UserEntity user = userService.getOrCreateUser(authentication);
        DeviceEntity device = deviceRepository.findByUserEntityIdAndDeviceId(user.getId(), deviceId)
                .orElseGet(() -> DeviceEntity.builder()
                        .userEntity(user)
                        .deviceId(deviceId)
                        .name(name)
                        .platform(platform)
                        .lastSeenAt(Instant.now())
                        .build());
        device.setPlatform(platform);
        device.setLastSeenAt(Instant.now());
        return deviceRepository.save(device);
    }

    /**
     * Presence ping: bumps the durable lastSeenAt, but only writes when the stored value is
     * older than {@link #LAST_SEEN_WRITE_INTERVAL}. Returns false for an unknown device.
     */
    @Transactional
    public boolean ping(Authentication authentication, UUID deviceId) {
        UserEntity user = userService.getOrCreateUser(authentication);
        Optional<DeviceEntity> device = deviceRepository.findByUserEntityIdAndDeviceId(user.getId(), deviceId);
        if (device.isEmpty()) {
            return false;
        }
        Instant now = Instant.now();
        if (device.get().getLastSeenAt().isBefore(now.minus(LAST_SEEN_WRITE_INTERVAL))) {
            device.get().setLastSeenAt(now);
            deviceRepository.save(device.get());
        }
        return true;
    }

    /** Renames the caller's device; empty when the device is not the caller's (deny-as-not-found). */
    @Transactional
    public Optional<DeviceEntity> rename(Authentication authentication, UUID deviceId, String name) {
        UserEntity user = userService.getOrCreateUser(authentication);
        return deviceRepository.findByUserEntityIdAndDeviceId(user.getId(), deviceId)
                .map(device -> {
                    device.setName(name);
                    return deviceRepository.save(device);
                });
    }

    /** Removes the caller's device; false when the device is not the caller's. */
    @Transactional
    public boolean remove(Authentication authentication, UUID deviceId) {
        UserEntity user = userService.getOrCreateUser(authentication);
        Optional<DeviceEntity> device = deviceRepository.findByUserEntityIdAndDeviceId(user.getId(), deviceId);
        device.ifPresent(deviceRepository::delete);
        return device.isPresent();
    }

    @Transactional
    public List<DeviceEntity> listForUser(Authentication authentication) {
        UserEntity user = userService.getOrCreateUser(authentication);
        return deviceRepository.findByUserEntityIdOrderByLastSeenAtDesc(user.getId());
    }

    /** Ownership gate for device-targeted commands and the device-command subscription. */
    @Transactional(readOnly = true)
    public Optional<DeviceEntity> findOwned(UUID userId, UUID deviceId) {
        return deviceRepository.findByUserEntityIdAndDeviceId(userId, deviceId);
    }
}
