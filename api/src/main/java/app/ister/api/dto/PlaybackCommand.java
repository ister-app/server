package app.ister.api.dto;

import app.ister.core.enums.PlaybackCommandType;
import app.ister.core.eventdata.PlaybackCommandData;

import java.util.UUID;

/** GraphQL view of one remote-control command (see PlaybackCommandService in core). */
public record PlaybackCommand(
        UUID playQueueId,
        PlaybackCommandType command,
        Long positionInMilliseconds,
        UUID playQueueItemId,
        String timestamp) {

    public static PlaybackCommand from(PlaybackCommandData data) {
        return new PlaybackCommand(data.getPlayQueueId(), data.getCommand(),
                data.getPositionInMilliseconds(), data.getPlayQueueItemId(),
                String.valueOf(data.getTimestamp()));
    }
}
