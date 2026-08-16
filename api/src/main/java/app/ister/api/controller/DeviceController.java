package app.ister.api.controller;

import app.ister.api.dto.Device;
import app.ister.api.dto.DeviceCommand;
import app.ister.core.enums.DeviceCommandType;
import app.ister.core.enums.DevicePlatform;
import app.ister.core.enums.MediaType;
import app.ister.core.eventdata.DeviceCommandData;
import app.ister.core.eventdata.PlaybackStatusData;
import app.ister.core.service.DeviceService;
import app.ister.core.service.UserService;
import app.ister.core.status.DeviceCommandService;
import app.ister.core.status.DevicePresenceRegistry;
import app.ister.core.status.PlaybackSessionRegistry;
import app.ister.core.status.ServerStatusBroadcaster;
import org.springframework.graphql.data.method.annotation.Argument;
import org.springframework.graphql.data.method.annotation.Arguments;
import org.springframework.graphql.data.method.annotation.MutationMapping;
import org.springframework.graphql.data.method.annotation.QueryMapping;
import org.springframework.graphql.data.method.annotation.SubscriptionMapping;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Registered devices and device-targeted commands ("play on / hand off to / listen along on
 * another device"). Strictly own-devices-only: every lookup is scoped by the caller, and a
 * device the caller does not own is treated as absent (deny-as-not-found). Commands travel over
 * the status fan-out exchange like remote-control commands; only an online device (live presence
 * ping within the session timeout) is a valid target, and delivery is best-effort — true means
 * published, not executed.
 */
@Controller
public class DeviceController {

    private final DeviceService deviceService;
    private final DeviceCommandService deviceCommandService;
    private final DevicePresenceRegistry devicePresenceRegistry;
    private final PlaybackSessionRegistry playbackSessionRegistry;
    private final ServerStatusBroadcaster broadcaster;
    private final UserService userService;

    public DeviceController(DeviceService deviceService, DeviceCommandService deviceCommandService,
                            DevicePresenceRegistry devicePresenceRegistry,
                            PlaybackSessionRegistry playbackSessionRegistry,
                            ServerStatusBroadcaster broadcaster, UserService userService) {
        this.deviceService = deviceService;
        this.deviceCommandService = deviceCommandService;
        this.devicePresenceRegistry = devicePresenceRegistry;
        this.playbackSessionRegistry = playbackSessionRegistry;
        this.broadcaster = broadcaster;
        this.userService = userService;
    }

    @PreAuthorize("hasRole('user')")
    @QueryMapping
    public List<Device> myDevices(Authentication authentication) {
        UUID userId = userService.getOrCreateUser(authentication).getId();
        Set<UUID> online = devicePresenceRegistry.onlineDeviceIds(userId);
        return deviceService.listForUser(authentication).stream()
                .map(entity -> Device.from(entity, online.contains(entity.getDeviceId())))
                .toList();
    }

    @PreAuthorize("hasRole('user')")
    @MutationMapping
    public Device registerDevice(@Argument UUID deviceId, @Argument String name,
                                 @Argument DevicePlatform platform, Authentication authentication) {
        UUID userId = userService.getOrCreateUser(authentication).getId();
        Device device = Device.from(deviceService.register(authentication, deviceId, name, platform), true);
        // Registration doubles as the first presence ping, so the device is targetable right away.
        deviceCommandService.publishPresence(userId, deviceId);
        return device;
    }

    @PreAuthorize("hasRole('user')")
    @MutationMapping
    public Device renameDevice(@Argument UUID deviceId, @Argument String name, Authentication authentication) {
        UUID userId = userService.getOrCreateUser(authentication).getId();
        return deviceService.rename(authentication, deviceId, name)
                .map(entity -> Device.from(entity, devicePresenceRegistry.isOnline(userId, deviceId)))
                .orElse(null);
    }

    @PreAuthorize("hasRole('user')")
    @MutationMapping
    public boolean removeDevice(@Argument UUID deviceId, Authentication authentication) {
        return deviceService.remove(authentication, deviceId);
    }

    @PreAuthorize("hasRole('user')")
    @MutationMapping
    public boolean pingDevice(@Argument UUID deviceId, Authentication authentication) {
        UUID userId = userService.getOrCreateUser(authentication).getId();
        if (!deviceService.ping(authentication, deviceId)) {
            return false;
        }
        deviceCommandService.publishPresence(userId, deviceId);
        return true;
    }

    /** All arguments of the {@code sendDeviceCommand} mutation, bound as one object off the argument map. */
    record DeviceCommandArguments(UUID deviceId, DeviceCommandType command, MediaType mediaType,
                                  UUID mediaId, UUID startId, UUID playQueueId,
                                  Double positionInMilliseconds, UUID targetDeviceId) {
    }

    @PreAuthorize("hasRole('user')")
    @MutationMapping
    public boolean sendDeviceCommand(@Arguments DeviceCommandArguments args, Authentication authentication) {
        UUID userId = userService.getOrCreateUser(authentication).getId();
        // Own devices only; an unknown or someone else's device id is indistinguishable (false).
        if (deviceService.findOwned(userId, args.deviceId()).isEmpty()) {
            return false;
        }
        // Only an online device is listening; without a subscriber the fan-out would drop the
        // command anyway, so refuse up front and let the UI say the device is offline.
        if (!devicePresenceRegistry.isOnline(userId, args.deviceId())) {
            return false;
        }
        if ((args.command() == DeviceCommandType.TAKEOVER_QUEUE || args.command() == DeviceCommandType.START_FOLLOW
                || args.command() == DeviceCommandType.HANDOFF_QUEUE)
                && (args.playQueueId() == null || playbackSessionRegistry.find(args.playQueueId()).isEmpty())) {
            return false;
        }
        if (args.command() == DeviceCommandType.HANDOFF_QUEUE) {
            // The queue moves on to the target device, which must therefore also be an own and
            // online device. Handing off to the sending device itself would be a pointless loop.
            if (args.targetDeviceId() == null || args.targetDeviceId().equals(args.deviceId())
                    || deviceService.findOwned(userId, args.targetDeviceId()).isEmpty()
                    || !devicePresenceRegistry.isOnline(userId, args.targetDeviceId())) {
                return false;
            }
            // The recipient must actually be the device playing the queue; a session without a
            // device id is allowed through and left to the recipient's own-live-queue guard.
            UUID sessionDeviceId = playbackSessionRegistry.find(args.playQueueId())
                    .map(PlaybackStatusData::getDeviceId).orElse(null);
            if (sessionDeviceId != null && !sessionDeviceId.equals(args.deviceId())) {
                return false;
            }
        }
        Double position = args.positionInMilliseconds();
        deviceCommandService.publish(DeviceCommandData.builder()
                .ownerUserId(userId)
                .deviceId(args.deviceId())
                .command(args.command())
                .mediaType(args.mediaType())
                .mediaId(args.mediaId())
                .startId(args.startId())
                .playQueueId(args.playQueueId())
                .positionInMilliseconds(position == null ? null : position.longValue())
                .targetDeviceId(args.targetDeviceId())
                .build());
        return true;
    }

    @PreAuthorize("hasRole('user')")
    @SubscriptionMapping
    public Flux<DeviceCommand> deviceCommands(@Argument UUID deviceId, Authentication authentication) {
        // Ownership is proven once at subscribe time (websocket thread — database access is fine
        // here, unlike the RabbitMQ listener). The per-event filter then only needs the plain
        // (ownerUserId, deviceId) pair from the payload: the device id alone is not globally
        // unique, so filtering on it alone would leak another user's commands.
        return Mono.fromCallable(() -> userService.getOrCreateUser(authentication).getId())
                .subscribeOn(Schedulers.boundedElastic())
                .flatMapMany(userId -> {
                    if (deviceService.findOwned(userId, deviceId).isEmpty()) {
                        return Flux.error(new IllegalArgumentException("Unknown device"));
                    }
                    return broadcaster.deviceCommandFlux()
                            .filter(data -> userId.equals(data.getOwnerUserId())
                                    && deviceId.equals(data.getDeviceId()))
                            .map(DeviceCommand::from);
                });
    }
}
