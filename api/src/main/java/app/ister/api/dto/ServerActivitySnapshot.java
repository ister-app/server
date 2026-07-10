package app.ister.api.dto;

import java.util.List;

/** Initial state for the serverActivity/nowPlaying subscriptions. */
public record ServerActivitySnapshot(
        List<ServerActivityEvent> nodes,
        List<ServerActivityEvent.QueueStat> queueStats,
        List<ServerActivityEvent.EventFailure> recentFailures,
        List<PlaybackSession> nowPlaying) {
}
