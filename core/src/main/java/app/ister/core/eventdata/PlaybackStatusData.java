package app.ister.core.eventdata;

import app.ister.core.enums.MediaType;
import app.ister.core.enums.PlayState;
import app.ister.core.enums.RemoteControlScope;
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
    /** Per-session remote-control override; null = use the owner's account-level control scope.
     * Embedded on the heartbeat path (which has DB access) so the now-playing resolver can compute
     * the per-viewer {@code controllable} flag without touching the database on listener threads. */
    private RemoteControlScope controlScopeOverride;
    /** Grantee user ids of this session's per-session control allowlist; used when
     * {@link #controlScopeOverride} is ALLOWLIST. Empty when there is no override list. */
    private List<UUID> controlAllowedUserIds;
}
