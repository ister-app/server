package app.ister.api.controller;

import app.ister.core.enums.FollowResult;
import app.ister.core.service.PlayQueueService;
import app.ister.core.service.UserService;
import app.ister.core.status.FollowerStatusService;
import app.ister.core.status.PlaybackSessionRegistry;
import org.springframework.graphql.data.method.annotation.Argument;
import org.springframework.graphql.data.method.annotation.MutationMapping;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;

import java.util.UUID;

/**
 * Listen-along ("follow mode"): a second device — the owner's or that of any user allowed to
 * remote-control the session — registers as a follower of a live playback session. Followers play
 * the queue themselves but never report progress; the owner's updatePlayQueue writes the watch
 * status for every registered following user. Registrations travel over the status fan-out so
 * every node knows the followers, and expire on the session timeout without a heartbeat.
 */
@Controller
public class PlayQueueFollowController {

    private final PlayQueueService playQueueService;
    private final PlaybackSessionRegistry playbackSessionRegistry;
    private final FollowerStatusService followerStatusService;
    private final UserService userService;

    public PlayQueueFollowController(PlayQueueService playQueueService,
                                     PlaybackSessionRegistry playbackSessionRegistry,
                                     FollowerStatusService followerStatusService,
                                     UserService userService) {
        this.playQueueService = playQueueService;
        this.playbackSessionRegistry = playbackSessionRegistry;
        this.followerStatusService = followerStatusService;
        this.userService = userService;
    }

    @PreAuthorize("hasRole('user')")
    @MutationMapping
    public FollowResult followPlayQueue(@Argument UUID playQueueId, @Argument String deviceId,
                                        @Argument boolean active, Authentication authentication) {
        UUID userId = userService.getOrCreateUser(authentication).getId();
        if (!active) {
            // Deregistration always succeeds: the session may already be gone, and the registry
            // only removes the caller's own (user-id-matched) device entry.
            followerStatusService.publish(playQueueId, deviceId, userId, false);
            return FollowResult.OK;
        }
        // Check order matters: a live session plus control permission must be proven before the
        // distinct NO_LIBRARY_ACCESS result may be revealed (see PlayQueueService#checkFollowAccess).
        if (playbackSessionRegistry.find(playQueueId).isEmpty()) {
            return FollowResult.NOT_FOUND;
        }
        FollowResult access = playQueueService.checkFollowAccess(playQueueId, authentication);
        if (access != FollowResult.OK) {
            return access;
        }
        followerStatusService.publish(playQueueId, deviceId, userId, true);
        return FollowResult.OK;
    }
}
