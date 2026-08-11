package app.ister.core.eventdata;

import app.ister.core.enums.DeviceCommandType;
import app.ister.core.enums.MediaType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

/**
 * A command addressed to one specific device of one user, published on the status fan-out
 * exchange from the sendDeviceCommand mutation. The device-command subscription filters on the
 * ({@code ownerUserId}, {@code deviceId}) pair — the device id alone is not globally unique
 * (it is a client-generated install id, only unique per user). Carries only plain values so the
 * RabbitMQ listener thread never needs the database.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DeviceCommandData {
    /** The user owning the target device; also the issuer (own-devices-only). */
    private UUID ownerUserId;
    private UUID deviceId;
    private DeviceCommandType command;
    /** PLAY_MEDIA: what to play. */
    private MediaType mediaType;
    private UUID mediaId;
    /** PLAY_MEDIA: optional item to start at (track/episode/chapter) within the media. */
    private UUID startId;
    /** TAKEOVER_QUEUE / START_FOLLOW: the play queue to resume or follow. */
    private UUID playQueueId;
    /** TAKEOVER_QUEUE: position to resume at. */
    private Long positionInMilliseconds;
    /** HANDOFF_QUEUE: the device the receiving (source) device should push its queue to. */
    private UUID targetDeviceId;
    private Instant timestamp;
}
