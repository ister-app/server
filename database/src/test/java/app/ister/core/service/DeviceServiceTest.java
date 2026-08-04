package app.ister.core.service;

import app.ister.core.entity.DeviceEntity;
import app.ister.core.entity.UserEntity;
import app.ister.core.enums.DevicePlatform;
import app.ister.core.repository.DeviceRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.Authentication;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DeviceServiceTest {

    @Mock
    private DeviceRepository deviceRepository;

    @Mock
    private UserService userService;

    @Mock
    private Authentication authentication;

    @InjectMocks
    private DeviceService subject;

    private final UUID userId = UUID.randomUUID();
    private final UUID deviceId = UUID.randomUUID();
    private UserEntity user;

    @BeforeEach
    void setUp() {
        user = UserEntity.builder().id(userId).build();
        lenient().when(userService.getOrCreateUser(authentication)).thenReturn(user);
        lenient().when(deviceRepository.save(any(DeviceEntity.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    private DeviceEntity existing(String name, Instant lastSeenAt) {
        DeviceEntity device = DeviceEntity.builder()
                .userEntity(user)
                .deviceId(deviceId)
                .name(name)
                .platform(DevicePlatform.LINUX)
                .lastSeenAt(lastSeenAt)
                .build();
        lenient().when(deviceRepository.findByUserEntityIdAndDeviceId(userId, deviceId))
                .thenReturn(Optional.of(device));
        return device;
    }

    @Test
    void registerCreatesNewDeviceWithSuppliedName() {
        when(deviceRepository.findByUserEntityIdAndDeviceId(userId, deviceId)).thenReturn(Optional.empty());
        DeviceEntity device = subject.register(authentication, deviceId, "Woonkamer", DevicePlatform.ANDROID_TV);
        assertEquals("Woonkamer", device.getName());
        assertEquals(DevicePlatform.ANDROID_TV, device.getPlatform());
        verify(deviceRepository).save(device);
    }

    @Test
    void registerUpsertKeepsUserChosenNameButRefreshesPlatformAndLastSeen() {
        Instant stale = Instant.now().minusSeconds(3600);
        existing("Mijn telefoon", stale);
        DeviceEntity device = subject.register(authentication, deviceId, "Pixel 9", DevicePlatform.ANDROID);
        assertEquals("Mijn telefoon", device.getName());
        assertEquals(DevicePlatform.ANDROID, device.getPlatform());
        assertTrue(device.getLastSeenAt().isAfter(stale));
    }

    @Test
    void pingBumpsLastSeenWhenStale() {
        Instant stale = Instant.now().minusSeconds(120);
        DeviceEntity device = existing("Laptop", stale);
        assertTrue(subject.ping(authentication, deviceId));
        assertTrue(device.getLastSeenAt().isAfter(stale));
        verify(deviceRepository).save(device);
    }

    @Test
    void pingSkipsWriteWhenRecent() {
        Instant recent = Instant.now().minusSeconds(5);
        DeviceEntity device = existing("Laptop", recent);
        assertTrue(subject.ping(authentication, deviceId));
        assertEquals(recent, device.getLastSeenAt());
        verify(deviceRepository, never()).save(any());
    }

    @Test
    void pingUnknownDeviceReturnsFalse() {
        when(deviceRepository.findByUserEntityIdAndDeviceId(userId, deviceId)).thenReturn(Optional.empty());
        assertFalse(subject.ping(authentication, deviceId));
    }

    @Test
    void renameOnlyTouchesOwnDevice() {
        existing("Oud", Instant.now());
        Optional<DeviceEntity> renamed = subject.rename(authentication, deviceId, "Nieuw");
        assertTrue(renamed.isPresent());
        assertEquals("Nieuw", renamed.get().getName());
    }

    @Test
    void renameSomeoneElsesDeviceIsNotFound() {
        // The lookup is scoped by the caller's user id, so another user's device simply isn't found.
        when(deviceRepository.findByUserEntityIdAndDeviceId(userId, deviceId)).thenReturn(Optional.empty());
        assertTrue(subject.rename(authentication, deviceId, "Nieuw").isEmpty());
        verify(deviceRepository, never()).save(any());
    }

    @Test
    void removeReturnsFalseForUnknownDevice() {
        when(deviceRepository.findByUserEntityIdAndDeviceId(userId, deviceId)).thenReturn(Optional.empty());
        assertFalse(subject.remove(authentication, deviceId));
        verify(deviceRepository, never()).delete(any());
    }

    @Test
    void removeDeletesOwnDevice() {
        DeviceEntity device = existing("Laptop", Instant.now());
        assertTrue(subject.remove(authentication, deviceId));
        verify(deviceRepository).delete(device);
    }
}
