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
        String updatedAt,
        /** Whether the requesting user may remote-control this session (computed per viewer). */
        boolean controllable,
        /** Number of devices currently following (listening along with) this session. */
        int followerCount,
        /** Tight-sync anchor: playback position (ms) at {@link #anchorServerTimeMs}; null without one. */
        Long anchorPositionMs,
        /** Server-clock instant the anchor was sampled at (epoch ms, Float on the wire); null without one. */
        Double anchorServerTimeMs) {

    public static PlaybackSession from(PlaybackStatusData data, boolean controllable, int followerCount) {
        return new PlaybackSession(data.getPlayQueueId(), data.getPlayQueueItemId(), data.getUserId(),
                data.getUserName(), data.getMediaType(), data.getMediaId(), data.getTitle(),
                data.getDurationInMilliseconds(), data.getArtworkImageId(),
                data.getProgressInMilliseconds(), data.getPlayState(), data.getNodeName(),
                String.valueOf(data.getTimestamp()), controllable, followerCount,
                data.getAnchorPositionMs(),
                data.getAnchorServerTimeMs() == null ? null : data.getAnchorServerTimeMs().doubleValue());
    }
}
