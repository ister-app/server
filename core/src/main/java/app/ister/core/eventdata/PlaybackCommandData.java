package app.ister.core.eventdata;

import app.ister.core.enums.PlaybackCommandType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

/**
 * Remote-control command, published on the status fan-out exchange from the
 * sendPlaybackCommand mutation. The mutation may arrive at a different node than the one
 * holding the target client's websocket; the fan-out reaches every node, and the node
 * with a matching playbackCommands subscriber delivers it. Carries only plain values.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PlaybackCommandData {
    private UUID playQueueId;
    private PlaybackCommandType command;
    /** Target position for SEEK. */
    private Long positionInMilliseconds;
    /** Target queue item for SKIP_TO_ITEM. */
    private UUID playQueueItemId;
    private Instant timestamp;
}
