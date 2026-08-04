package app.ister.api.controller;

import app.ister.core.entity.DeviceEntity;
import app.ister.core.entity.UserEntity;
import app.ister.core.enums.DevicePlatform;
import app.ister.core.eventdata.DeviceCommandData;
import app.ister.core.eventdata.PlaybackStatusData;
import app.ister.core.service.DeviceService;
import app.ister.core.service.UserService;
import app.ister.core.status.DeviceCommandService;
import app.ister.core.status.DevicePresenceRegistry;
import app.ister.core.status.PlaybackSessionRegistry;
import app.ister.core.status.ServerStatusBroadcaster;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.graphql.test.autoconfigure.GraphQlTest;
import org.springframework.graphql.test.tester.GraphQlTester;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Schema-wiring test for the device query/mutations: ownership gates and the online gate. */
@GraphQlTest(DeviceController.class)
class DeviceControllerGraphQlTest {

    @Autowired
    private GraphQlTester graphQlTester;

    @MockitoBean
    private DeviceService deviceService;

    @MockitoBean
    private DeviceCommandService deviceCommandService;

    @MockitoBean
    private DevicePresenceRegistry devicePresenceRegistry;

    @MockitoBean
    private PlaybackSessionRegistry playbackSessionRegistry;

    @MockitoBean
    private ServerStatusBroadcaster broadcaster;

    @MockitoBean
    private UserService userService;

    private final UUID userId = UUID.randomUUID();
    private final UUID deviceId = UUID.randomUUID();

    @BeforeEach
    void authenticateAsUser() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(
                        "test-user", null,
                        List.of(new SimpleGrantedAuthority("ROLE_user"))));
        when(userService.getOrCreateUser(any()))
                .thenReturn(UserEntity.builder().id(userId).build());
    }

    @AfterEach
    void clearAuthentication() {
        SecurityContextHolder.clearContext();
    }

    private DeviceEntity entity(String name) {
        return DeviceEntity.builder()
                .deviceId(deviceId)
                .name(name)
                .platform(DevicePlatform.LINUX)
                .lastSeenAt(Instant.parse("2026-08-04T10:00:00Z"))
                .dateCreated(Instant.parse("2026-08-01T10:00:00Z"))
                .build();
    }

    @Test
    void myDevicesMarksOnlineFromThePresenceRegistry() {
        when(deviceService.listForUser(any())).thenReturn(List.of(entity("Laptop")));
        when(devicePresenceRegistry.onlineDeviceIds(userId)).thenReturn(Set.of(deviceId));

        graphQlTester.document("""
                        query { myDevices { deviceId name platform online lastSeenAt } }
                        """)
                .execute()
                .path("myDevices[0].name").entity(String.class).isEqualTo("Laptop")
                .path("myDevices[0].online").entity(Boolean.class).isEqualTo(true);
    }

    @Test
    void registerDevicePublishesPresenceSoTheDeviceIsTargetableRightAway() {
        when(deviceService.register(any(), eq(deviceId), eq("Laptop"), eq(DevicePlatform.LINUX)))
                .thenReturn(entity("Laptop"));

        graphQlTester.document("""
                        mutation { registerDevice(deviceId: "%s", name: "Laptop", platform: LINUX) { deviceId online } }
                        """.formatted(deviceId))
                .execute()
                .path("registerDevice.online").entity(Boolean.class).isEqualTo(true);

        verify(deviceCommandService).publishPresence(userId, deviceId);
    }

    @Test
    void renameSomeoneElsesDeviceIsNull() {
        when(deviceService.rename(any(), eq(deviceId), eq("Nieuw"))).thenReturn(Optional.empty());

        graphQlTester.document("""
                        mutation { renameDevice(deviceId: "%s", name: "Nieuw") { deviceId } }
                        """.formatted(deviceId))
                .execute()
                .path("renameDevice").valueIsNull();
    }

    @Test
    void sendDeviceCommandToUnownedDeviceIsFalse() {
        when(deviceService.findOwned(userId, deviceId)).thenReturn(Optional.empty());

        String document = """
                mutation { sendDeviceCommand(deviceId: "%s", command: PLAY_MEDIA, mediaType: MOVIE, mediaId: "%s") }
                """.formatted(deviceId, UUID.randomUUID());
        Boolean result = graphQlTester.document(document).execute()
                .path("sendDeviceCommand").entity(Boolean.class).get();

        assertEquals(false, result);
        verify(deviceCommandService, never()).publish(any());
    }

    @Test
    void sendDeviceCommandToOfflineDeviceIsFalse() {
        when(deviceService.findOwned(userId, deviceId)).thenReturn(Optional.of(entity("Laptop")));
        when(devicePresenceRegistry.isOnline(userId, deviceId)).thenReturn(false);

        String document = """
                mutation { sendDeviceCommand(deviceId: "%s", command: PLAY_MEDIA, mediaType: MOVIE, mediaId: "%s") }
                """.formatted(deviceId, UUID.randomUUID());
        Boolean result = graphQlTester.document(document).execute()
                .path("sendDeviceCommand").entity(Boolean.class).get();

        assertEquals(false, result);
        verify(deviceCommandService, never()).publish(any());
    }

    @Test
    void takeoverWithoutLiveSessionIsFalse() {
        when(deviceService.findOwned(userId, deviceId)).thenReturn(Optional.of(entity("Laptop")));
        when(devicePresenceRegistry.isOnline(userId, deviceId)).thenReturn(true);
        UUID queueId = UUID.randomUUID();
        when(playbackSessionRegistry.find(queueId)).thenReturn(Optional.empty());

        String document = """
                mutation { sendDeviceCommand(deviceId: "%s", command: TAKEOVER_QUEUE, playQueueId: "%s", positionInMilliseconds: 42000) }
                """.formatted(deviceId, queueId);
        Boolean result = graphQlTester.document(document).execute()
                .path("sendDeviceCommand").entity(Boolean.class).get();

        assertEquals(false, result);
        verify(deviceCommandService, never()).publish(any());
    }

    @Test
    void takeoverPublishesOwnerScopedCommandWithPosition() {
        when(deviceService.findOwned(userId, deviceId)).thenReturn(Optional.of(entity("Laptop")));
        when(devicePresenceRegistry.isOnline(userId, deviceId)).thenReturn(true);
        UUID queueId = UUID.randomUUID();
        when(playbackSessionRegistry.find(queueId)).thenReturn(Optional.of(PlaybackStatusData.builder().build()));

        graphQlTester.document("""
                        mutation { sendDeviceCommand(deviceId: "%s", command: TAKEOVER_QUEUE, playQueueId: "%s", positionInMilliseconds: 42000) }
                        """.formatted(deviceId, queueId))
                .execute()
                .path("sendDeviceCommand").entity(Boolean.class).isEqualTo(true);

        ArgumentCaptor<DeviceCommandData> captor = ArgumentCaptor.forClass(DeviceCommandData.class);
        verify(deviceCommandService).publish(captor.capture());
        assertEquals(userId, captor.getValue().getOwnerUserId());
        assertEquals(deviceId, captor.getValue().getDeviceId());
        assertEquals(queueId, captor.getValue().getPlayQueueId());
        assertEquals(42000L, captor.getValue().getPositionInMilliseconds());
    }

    @Test
    void pingUnknownDeviceIsFalseAndPublishesNothing() {
        when(deviceService.ping(any(), eq(deviceId))).thenReturn(false);

        Boolean result = graphQlTester.document("""
                        mutation { pingDevice(deviceId: "%s") }
                        """.formatted(deviceId))
                .execute()
                .path("pingDevice").entity(Boolean.class).get();

        assertEquals(false, result);
        verify(deviceCommandService, never()).publishPresence(any(), any());
    }
}
