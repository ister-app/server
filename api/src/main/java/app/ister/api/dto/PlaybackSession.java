package app.ister.api.dto;

import app.ister.core.enums.ActivityKind;
import app.ister.core.enums.MediaType;
import app.ister.core.enums.PlayState;
import app.ister.core.enums.RepeatMode;
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
        /** Install id of the playing device; null for clients that don't report one. */
        UUID deviceId,
        /** Name of the playing device as registered at heartbeat time; null without a device id. */
        String deviceName,
        /** Tight-sync anchor: playback position (ms) at {@link #anchorServerTimeMs}; null without one. */
        Long anchorPositionMs,
        /** Server-clock instant the anchor was sampled at (epoch ms, Float on the wire); null without one. */
        Double anchorServerTimeMs,
        /** Repeat mode of the playing client; null for clients that don't report one. */
        RepeatMode repeatMode,
        /** The media file the playing client opened; null when it doesn't report one. */
        UUID mediaFileId,
        /** PLAYBACK for play-queue sessions; READING/GAME for activity sessions (never null). */
        ActivityKind activityKind,
        /** Reading progress of an activity session, 0..1; null for playback and games. */
        Double progressFraction,
        /** Client-rendered position label of an activity session; null otherwise. */
        String progressLabel) {

    public static PlaybackSession from(PlaybackStatusData data, boolean controllable, int followerCount) {
        return new PlaybackSession(data.getPlayQueueId(), data.getPlayQueueItemId(), data.getUserId(),
                data.getUserName(), data.getMediaType(), data.getMediaId(), data.getTitle(),
                data.getDurationInMilliseconds(), data.getArtworkImageId(),
                data.getProgressInMilliseconds(), data.getPlayState(), data.getNodeName(),
                String.valueOf(data.getTimestamp()), controllable, followerCount,
                data.getDeviceId(), data.getDeviceName(),
                data.getAnchorPositionMs(),
                data.getAnchorServerTimeMs() == null ? null : data.getAnchorServerTimeMs().doubleValue(),
                data.getRepeatMode(), data.getMediaFileId(),
                kindOf(data), data.getProgressFraction(), data.getProgressLabel());
    }

    /** Heartbeats from nodes that predate the field carry null; they are playback sessions. */
    public static ActivityKind kindOf(PlaybackStatusData data) {
        return data.getActivityKind() == null ? ActivityKind.PLAYBACK : data.getActivityKind();
    }
}
