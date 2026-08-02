package app.ister.api.controller;

import app.ister.core.entity.UserEntity;
import app.ister.core.enums.FollowResult;
import app.ister.core.eventdata.PlaybackStatusData;
import app.ister.core.service.PlayQueueService;
import app.ister.core.service.UserService;
import app.ister.core.status.FollowerStatusService;
import app.ister.core.status.PlaybackSessionRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.Authentication;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
    private UserService userService;
    @Mock
    private Authentication authentication;

    private final UUID queueId = UUID.randomUUID();
    private final UUID userId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        when(userService.getOrCreateUser(authentication))
                .thenReturn(UserEntity.builder().id(userId).build());
    }

    @Test
    void followWithoutLiveSessionIsNotFoundAndNeverConsultsTheDatabase() {
        when(playbackSessionRegistry.find(queueId)).thenReturn(Optional.empty());

        assertEquals(FollowResult.NOT_FOUND,
                subject.followPlayQueue(queueId, "device-a", true, authentication));
        // A stopped session and a denied one must be indistinguishable; no DB access check runs.
        verifyNoInteractions(playQueueService);
        verify(followerStatusService, never()).publish(queueId, "device-a", userId, true);
    }

    @Test
    void followPassesTheAccessDecisionThrough() {
        when(playbackSessionRegistry.find(queueId)).thenReturn(Optional.of(PlaybackStatusData.builder().build()));
        when(playQueueService.checkFollowAccess(queueId, authentication)).thenReturn(FollowResult.NO_LIBRARY_ACCESS);

        assertEquals(FollowResult.NO_LIBRARY_ACCESS,
                subject.followPlayQueue(queueId, "device-a", true, authentication));
        verify(followerStatusService, never()).publish(queueId, "device-a", userId, true);
    }

    @Test
    void allowedFollowPublishesTheRegistration() {
        when(playbackSessionRegistry.find(queueId)).thenReturn(Optional.of(PlaybackStatusData.builder().build()));
        when(playQueueService.checkFollowAccess(queueId, authentication)).thenReturn(FollowResult.OK);

        assertEquals(FollowResult.OK,
                subject.followPlayQueue(queueId, "device-a", true, authentication));
        verify(followerStatusService).publish(queueId, "device-a", userId, true);
    }

    @Test
    void unfollowAlwaysSucceedsEvenWithoutASession() {
        assertEquals(FollowResult.OK,
                subject.followPlayQueue(queueId, "device-a", false, authentication));
        verify(followerStatusService).publish(queueId, "device-a", userId, false);
        verifyNoInteractions(playQueueService, playbackSessionRegistry);
    }
}
