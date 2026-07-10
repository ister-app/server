package app.ister.core.eventdata;

import app.ister.core.enums.MediaType;
import app.ister.core.enums.PlayState;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
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
    private String userName;
    private MediaType mediaType;
    private UUID mediaId;
    private String title;
    private long progressInMilliseconds;
    private PlayState playState;
    private String nodeName;
    private Instant timestamp;
}
