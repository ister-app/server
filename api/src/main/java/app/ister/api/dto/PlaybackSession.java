package app.ister.api.dto;

import app.ister.core.enums.MediaType;
import app.ister.core.enums.PlayState;
import app.ister.core.eventdata.PlaybackStatusData;

import java.util.UUID;

/** GraphQL view of one active playback session (see PlaybackSessionRegistry in core). */
public record PlaybackSession(
        UUID playQueueId,
        UUID playQueueItemId,
        UUID userId,
        String userName,
        MediaType mediaType,
        UUID mediaId,
        String title,
        Long durationInMilliseconds,
        UUID artworkImageId,
        long progressInMilliseconds,
        PlayState playState,
        String nodeName,
        String updatedAt) {

    public static PlaybackSession from(PlaybackStatusData data) {
        return new PlaybackSession(data.getPlayQueueId(), data.getPlayQueueItemId(), data.getUserId(),
                data.getUserName(), data.getMediaType(), data.getMediaId(), data.getTitle(),
                data.getDurationInMilliseconds(), data.getArtworkImageId(),
                data.getProgressInMilliseconds(), data.getPlayState(), data.getNodeName(),
                String.valueOf(data.getTimestamp()));
    }
}
