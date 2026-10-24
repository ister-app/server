package app.ister.core.eventdata;

import app.ister.core.enums.ActivityKind;
import app.ister.core.enums.MediaType;
import app.ister.core.enums.PlayState;
import app.ister.core.enums.RemoteControlScope;
import app.ister.core.enums.RepeatMode;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Playback heartbeat, published on the status fan-out exchange from the updatePlayQueue
 * mutation. A session that stops sending these is considered stopped after a timeout
 * (see PlaybackSessionSweeper). Carries only plain values — no entities.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PlaybackStatusData {
    private UUID playQueueId;
    private UUID playQueueItemId;
    private UUID userId;
    /** OIDC subject of the session owner; lets the degraded (DB-free) heartbeat
     * path verify ownership against the JWT without a database lookup. */
    private String userExternalId;
    private String userName;
    private MediaType mediaType;
    private UUID mediaId;
    private String title;
    /** Total duration of the playing media file; null when no media file is known. */
    private Long durationInMilliseconds;
    /** Cover image of the playing media (movie poster / show poster / album cover). */
    private UUID artworkImageId;
    private long progressInMilliseconds;
    private PlayState playState;
    private String nodeName;
    private Instant timestamp;
    /** Repeat mode of the playing client; null for clients that don't report one. */
    private RepeatMode repeatMode;
    /** The media file of the item the playing client opened (an item can have several versions);
     * null when it doesn't report one. Followers open the same file to share one timeline. */
    private UUID mediaFileId;
    /** Per-session remote-control override; null = use the owner's account-level control scope.
     * Embedded on the heartbeat path (which has DB access) so the now-playing resolver can compute
     * the per-viewer {@code controllable} flag without touching the database on listener threads. */
    private RemoteControlScope controlScopeOverride;
    /** Grantee user ids of this session's per-session control allowlist; used when
     * {@link #controlScopeOverride} is ALLOWLIST. Empty when there is no override list. */
    private List<UUID> controlAllowedUserIds;
    /** Install id of the playing device; null for clients that don't report one. */
    private UUID deviceId;
    /** User-facing name of the playing device at heartbeat time; resolved on the request
     * thread (cached) so the now-playing resolver never needs the database for it. */
    private String deviceName;
    /** Tight-sync anchor: playback position (ms) at {@link #anchorServerTimeMs}. Null when
     * the playing client doesn't report one (older clients, or clock sync unavailable). */
    private Long anchorPositionMs;
    /** Server-clock instant (epoch ms) the anchor position was sampled at, as computed by the
     * playing client from its measured clock offset. */
    private Long anchorServerTimeMs;
    /** PLAYBACK for play-queue heartbeats (null from older nodes means the same); READING/GAME for
     * activity sessions reported through reportActivity, which have no queue to control or follow. */
    private ActivityKind activityKind;
    /** Reading progress of an activity session, 0..1; null for playback and games. */
    private Double progressFraction;
    /** Client-rendered position label of an activity session ("Chapter 3", "Page 12 of 48"). */
    private String progressLabel;
    /** True on the message endActivity publishes: every node drops the session instead of
     * updating it, so a closed reader disappears at once instead of after the sweeper timeout. */
    private boolean ended;
}
