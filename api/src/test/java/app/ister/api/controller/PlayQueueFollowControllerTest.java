package app.ister.api.controller;

import app.ister.api.dto.SessionFollower;
import app.ister.core.entity.UserEntity;
import app.ister.core.enums.DevicePlatform;
import app.ister.core.enums.FollowResult;
import app.ister.core.eventdata.PlaybackStatusData;
import app.ister.core.service.DeviceService;
import app.ister.core.service.PlayQueueService;
import app.ister.core.service.UserService;
import app.ister.core.status.FollowerRegistry;
import app.ister.core.status.FollowerStatusService;
import app.ister.core.status.PlaybackCommandService;
import app.ister.core.status.PlaybackSessionRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.Authentication;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PlayQueueFollowControllerTest {

    @InjectMocks
    private PlayQueueFollowController subject;

    @Mock
    private PlayQueueService playQueueService;
    @Mock
    private PlaybackSessionRegistry playbackSessionRegistry;
    @Mock
    private FollowerStatusService followerStatusService;
    @Mock
    private FollowerRegistry followerRegistry;
    @Mock
    private PlaybackCommandService playbackCommandService;
    @Mock
    private DeviceService deviceService;
    @Mock
    private UserService userService;
    @Mock
    private Authentication authentication;

    private final UUID queueId = UUID.randomUUID();
    private final UUID userId = UUID.randomUUID();
    private final UUID followerUserId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        when(userService.getOrCreateUser(authentication))
                .thenReturn(UserEntity.builder().id(userId).name("Gerben").build());
    }

    @Test
    void followWithoutLiveSessionIsNotFoundAndNeverConsultsTheDatabase() {
        when(playbackSessionRegistry.find(queueId)).thenReturn(Optional.empty());

        assertEquals(FollowResult.NOT_FOUND,
                subject.followPlayQueue(queueId, "device-a", true, authentication));
        // A stopped session and a denied one must be indistinguishable; no DB access check runs.
        verifyNoInteractions(playQueueService);
        verify(followerStatusService, never())
                .publish(any(), any(), any(), any(), any(), any(), eq(true));
    }

    @Test
    void followPassesTheAccessDecisionThrough() {
        when(playbackSessionRegistry.find(queueId)).thenReturn(Optional.of(PlaybackStatusData.builder().build()));
        when(playQueueService.checkFollowAccess(queueId, authentication)).thenReturn(FollowResult.NO_LIBRARY_ACCESS);

        assertEquals(FollowResult.NO_LIBRARY_ACCESS,
                subject.followPlayQueue(queueId, "device-a", true, authentication));
        verify(followerStatusService, never())
                .publish(any(), any(), any(), any(), any(), any(), eq(true));
    }

    @Test
    void allowedFollowPublishesTheRegistrationWithItsDisplayNames() {
        UUID deviceId = UUID.randomUUID();
        when(playbackSessionRegistry.find(queueId)).thenReturn(Optional.of(PlaybackStatusData.builder().build()));
        when(playQueueService.checkFollowAccess(queueId, authentication)).thenReturn(FollowResult.OK);
        when(deviceService.findOwned(userId, deviceId)).thenReturn(Optional.empty());

        assertEquals(FollowResult.OK,
                subject.followPlayQueue(queueId, deviceId.toString(), true, authentication));
        verify(followerStatusService)
                .publish(queueId, deviceId.toString(), userId, "Gerben", null, null, true);
    }

    @Test
    void aKickedDeviceCannotReRegisterWhileTheKickLasts() {
        when(playbackSessionRegistry.find(queueId)).thenReturn(Optional.of(PlaybackStatusData.builder().build()));
        when(followerRegistry.isKicked(queueId, "device-a")).thenReturn(true);

        assertEquals(FollowResult.NOT_FOUND,
                subject.followPlayQueue(queueId, "device-a", true, authentication));
        verifyNoInteractions(playQueueService);
    }

    @Test
    void unfollowAlwaysSucceedsEvenWithoutASession() {
        assertEquals(FollowResult.OK,
                subject.followPlayQueue(queueId, "device-a", false, authentication));
        verify(followerStatusService).publish(queueId, "device-a", userId, null, null, null, false);
        verifyNoInteractions(playQueueService, playbackSessionRegistry);
    }

    @Test
    void followersAreOwnerOnly() {
        when(playbackSessionRegistry.find(queueId))
                .thenReturn(Optional.of(PlaybackStatusData.builder().userId(UUID.randomUUID()).build()));

        assertTrue(subject.sessionFollowers(queueId, authentication).isEmpty());
        verifyNoInteractions(followerRegistry);
    }

    @Test
    void followersAreListedForTheOwner() {
        when(playbackSessionRegistry.find(queueId))
                .thenReturn(Optional.of(PlaybackStatusData.builder().userId(userId).build()));
        when(followerRegistry.followers(queueId)).thenReturn(List.of(follower("device-a")));

        List<SessionFollower> followers = subject.sessionFollowers(queueId, authentication);

        assertEquals(1, followers.size());
        assertEquals("Kitchen", followers.getFirst().deviceName());
        assertEquals(followerUserId, followers.getFirst().userId());
    }

    @Test
    void removingAFollowerIsOwnerOnly() {
        when(playbackSessionRegistry.find(queueId))
                .thenReturn(Optional.of(PlaybackStatusData.builder().userId(UUID.randomUUID()).build()));

        assertFalse(subject.removeFollower(queueId, followerUserId, "device-a", authentication));
        verifyNoInteractions(followerStatusService, playbackCommandService);
    }

    @Test
    void removingAUserKicksEveryDeviceOfThatUserOnly() {
        when(playbackSessionRegistry.find(queueId))
                .thenReturn(Optional.of(PlaybackStatusData.builder().userId(userId).build()));
        when(followerRegistry.followers(queueId)).thenReturn(List.of(
                follower("device-a"), follower("device-b"),
                new FollowerRegistry.FollowerInfo(UUID.randomUUID(), "Someone", "device-c",
                        null, null, Instant.EPOCH)));

        assertTrue(subject.removeFollower(queueId, followerUserId, null, authentication));

        verify(followerStatusService).publishKick(queueId, "device-a", followerUserId);
        verify(followerStatusService).publishKick(queueId, "device-b", followerUserId);
        verify(followerStatusService, never()).publishKick(queueId, "device-c", followerUserId);
        verify(playbackCommandService).publishStopFollow(queueId, "device-a");
        verify(playbackCommandService).publishStopFollow(queueId, "device-b");
        verify(playbackCommandService, never()).publishStopFollow(queueId, "device-c");
    }

    @Test
    void removingOneDeviceLeavesTheUsersOtherDevicesFollowing() {
        when(playbackSessionRegistry.find(queueId))
                .thenReturn(Optional.of(PlaybackStatusData.builder().userId(userId).build()));
        when(followerRegistry.followers(queueId)).thenReturn(List.of(follower("device-a"), follower("device-b")));

        assertTrue(subject.removeFollower(queueId, followerUserId, "device-b", authentication));

        verify(followerStatusService).publishKick(queueId, "device-b", followerUserId);
        verify(followerStatusService, never()).publishKick(queueId, "device-a", followerUserId);
    }

    @Test
    void removingSomethingThatIsNotFollowingReturnsFalse() {
        when(playbackSessionRegistry.find(queueId))
                .thenReturn(Optional.of(PlaybackStatusData.builder().userId(userId).build()));
        when(followerRegistry.followers(queueId)).thenReturn(List.of());

        assertFalse(subject.removeFollower(queueId, followerUserId, null, authentication));
        verifyNoInteractions(followerStatusService, playbackCommandService);
    }

    private FollowerRegistry.FollowerInfo follower(String deviceId) {
        return new FollowerRegistry.FollowerInfo(followerUserId, "Anna", deviceId, "Kitchen",
                DevicePlatform.ANDROID, Instant.EPOCH);
    }
}
