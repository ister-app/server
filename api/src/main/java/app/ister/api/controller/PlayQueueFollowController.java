package app.ister.api.controller;

import app.ister.api.dto.SessionFollower;
import app.ister.core.entity.DeviceEntity;
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
import org.springframework.graphql.data.method.annotation.Argument;
import org.springframework.graphql.data.method.annotation.MutationMapping;
import org.springframework.graphql.data.method.annotation.QueryMapping;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Listen-along ("follow mode"): a second device — the owner's or that of any user allowed to
 * remote-control the session — registers as a follower of a live playback session. Followers play
 * the queue themselves but never report progress; the owner's updatePlayQueue writes the watch
 * status for every registered following user. Registrations travel over the status fan-out so
 * every node knows the followers, and expire on the session timeout without a heartbeat.
 *
 * <p>The session owner can list the following devices and kick one off again; both are strictly
 * owner-only (deny-as-not-found for everyone else).
 */
@Controller
public class PlayQueueFollowController {

    /** How long a resolved device name is reused; the follow heartbeat comes by every ~20s. */
    private static final Duration DEVICE_CACHE_TTL = Duration.ofSeconds(60);

    private record DeviceCacheEntry(String name, DevicePlatform platform, Instant expiresAt) {
    }

    private final PlayQueueService playQueueService;
    private final PlaybackSessionRegistry playbackSessionRegistry;
    private final FollowerStatusService followerStatusService;
    private final FollowerRegistry followerRegistry;
    private final PlaybackCommandService playbackCommandService;
    private final DeviceService deviceService;
    private final UserService userService;
    private final Map<String, DeviceCacheEntry> deviceCache = new ConcurrentHashMap<>();

    public PlayQueueFollowController(PlayQueueService playQueueService,
                                     PlaybackSessionRegistry playbackSessionRegistry,
                                     FollowerStatusService followerStatusService,
                                     FollowerRegistry followerRegistry,
                                     PlaybackCommandService playbackCommandService,
                                     DeviceService deviceService,
                                     UserService userService) {
        this.playQueueService = playQueueService;
        this.playbackSessionRegistry = playbackSessionRegistry;
        this.followerStatusService = followerStatusService;
        this.followerRegistry = followerRegistry;
        this.playbackCommandService = playbackCommandService;
        this.deviceService = deviceService;
        this.userService = userService;
    }

    @PreAuthorize("hasRole('user')")
    @MutationMapping
    public FollowResult followPlayQueue(@Argument UUID playQueueId, @Argument String deviceId,
                                        @Argument boolean active, Authentication authentication) {
        UserEntity user = userService.getOrCreateUser(authentication);
        if (!active) {
            // Deregistration always succeeds: the session may already be gone, and the registry
            // only removes the caller's own (user-id-matched) device entry.
            followerStatusService.publish(playQueueId, deviceId, user.getId(), null, null, null, false);
            return FollowResult.OK;
        }
        // Check order matters: a live session plus control permission must be proven before the
        // distinct NO_LIBRARY_ACCESS result may be revealed (see PlayQueueService#checkFollowAccess).
        if (playbackSessionRegistry.find(playQueueId).isEmpty()) {
            return FollowResult.NOT_FOUND;
        }
        // A device the owner kicked stays out until the kick expires, whatever its heartbeat says.
        if (followerRegistry.isKicked(playQueueId, deviceId)) {
            return FollowResult.NOT_FOUND;
        }
        FollowResult access = playQueueService.checkFollowAccess(playQueueId, authentication);
        if (access != FollowResult.OK) {
            return access;
        }
        // Resolved here, on the request thread: the follower list is read from the in-memory
        // registry (partly on the RabbitMQ listener thread), which must stay database-free.
        DeviceCacheEntry device = deviceOf(user.getId(), deviceId);
        followerStatusService.publish(playQueueId, deviceId, user.getId(), user.getName(),
                device.name(), device.platform(), true);
        return FollowResult.OK;
    }

    /** The devices listening along with one of the caller's own sessions; empty for anyone else. */
    @PreAuthorize("hasRole('user')")
    @QueryMapping
    public List<SessionFollower> sessionFollowers(@Argument UUID playQueueId, Authentication authentication) {
        if (ownedSession(playQueueId, authentication).isEmpty()) {
            return List.of();
        }
        return followerRegistry.followers(playQueueId).stream().map(SessionFollower::from).toList();
    }

    /**
     * Kicks a follower off one of the caller's own sessions; a null deviceId removes every device
     * of that user. The devices are told to stop over the per-queue command channel, and barred
     * from re-registering until the kick expires.
     */
    @PreAuthorize("hasRole('user')")
    @MutationMapping
    public boolean removeFollower(@Argument UUID playQueueId, @Argument UUID userId,
                                  @Argument String deviceId, Authentication authentication) {
        if (ownedSession(playQueueId, authentication).isEmpty()) {
            return false;
        }
        // One command per device rather than one per user: the follower client then only has to
        // recognise its own install id, and needs no notion of its own user id.
        List<String> targets = followerRegistry.followers(playQueueId).stream()
                .filter(follower -> follower.userId().equals(userId))
                .filter(follower -> deviceId == null || deviceId.equals(follower.deviceId()))
                .map(FollowerRegistry.FollowerInfo::deviceId)
                .toList();
        for (String target : targets) {
            followerStatusService.publishKick(playQueueId, target, userId);
            playbackCommandService.publishStopFollow(playQueueId, target);
        }
        return !targets.isEmpty();
    }

    /** The live session for the queue, but only when the caller owns it (deny-as-not-found). */
    private Optional<PlaybackStatusData> ownedSession(UUID playQueueId, Authentication authentication) {
        UUID callerId = userService.getOrCreateUser(authentication).getId();
        return playbackSessionRegistry.find(playQueueId)
                .filter(session -> callerId.equals(session.getUserId()));
    }

    /** Cached device-registration lookup; a follower need never have called registerDevice. */
    private DeviceCacheEntry deviceOf(UUID userId, String deviceId) {
        String key = userId + ":" + deviceId;
        DeviceCacheEntry cached = deviceCache.get(key);
        if (cached != null && cached.expiresAt().isAfter(Instant.now())) {
            return cached;
        }
        Optional<DeviceEntity> device = parseDeviceId(deviceId)
                .flatMap(uuid -> deviceService.findOwned(userId, uuid));
        DeviceCacheEntry entry = new DeviceCacheEntry(
                device.map(DeviceEntity::getName).orElse(null),
                device.map(DeviceEntity::getPlatform).orElse(null),
                Instant.now().plus(DEVICE_CACHE_TTL));
        deviceCache.put(key, entry);
        return entry;
    }

    /** The follow protocol takes any string as an install id; device rows are keyed by UUID. */
    private static Optional<UUID> parseDeviceId(String deviceId) {
        try {
            return Optional.of(UUID.fromString(deviceId));
        } catch (IllegalArgumentException _) {
            return Optional.empty();
        }
    }
}
