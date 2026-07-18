package app.ister.core.status;

import app.ister.core.enums.MediaType;
import app.ister.core.enums.PlayState;
import app.ister.core.enums.RemoteControlScope;
import app.ister.core.eventdata.PlaybackStatusData;
import app.ister.core.service.MessageSender;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Publishes a playback heartbeat on the status exchange. Called from the
 * updatePlayQueue mutation on the API request thread; only plain values cross into the
 * message, so nothing lazy ever reaches a listener thread.
 */
@Service
public class PlaybackStatusService {

    private final MessageSender messageSender;
    private final String nodeName;

    public PlaybackStatusService(MessageSender messageSender,
                                 @Value("${app.ister.server.name}") String nodeName) {
        this.messageSender = messageSender;
        this.nodeName = nodeName;
    }

    @SuppressWarnings("java:S107") // heartbeat carries exactly these fields
    public void publishHeartbeat(UUID playQueueId, UUID playQueueItemId, UUID userId, String userExternalId,
                                 String userName, MediaType mediaType, UUID mediaId, String title,
                                 Long durationInMilliseconds, UUID artworkImageId,
                                 long progressInMilliseconds, PlayState playState,
                                 RemoteControlScope controlScopeOverride, List<UUID> controlAllowedUserIds) {
        messageSender.sendStatus(PlaybackStatusData.builder()
                .playQueueId(playQueueId)
                .playQueueItemId(playQueueItemId)
                .userId(userId)
                .userExternalId(userExternalId)
                .userName(userName)
                .mediaType(mediaType)
                .mediaId(mediaId)
                .title(title)
                .durationInMilliseconds(durationInMilliseconds)
                .artworkImageId(artworkImageId)
                .progressInMilliseconds(progressInMilliseconds)
                .playState(playState == null ? PlayState.PLAYING : playState)
                .nodeName(nodeName)
                .timestamp(Instant.now())
                .controlScopeOverride(controlScopeOverride)
                .controlAllowedUserIds(controlAllowedUserIds)
                .build());
    }
}
