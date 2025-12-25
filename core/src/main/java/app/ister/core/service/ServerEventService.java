package app.ister.core.service;

import app.ister.core.enums.EventType;
import app.ister.core.eventdata.EpisodeFoundData;
import app.ister.core.eventdata.MovieFoundData;
import app.ister.core.eventdata.ShowFoundData;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
@Slf4j
public class ServerEventService {
    @Autowired
    private MessageSender messageSender;

    public void createMovieFoundEvent(UUID movieId) {
        messageSender.sendMovieFound(MovieFoundData.builder()
                .eventType(EventType.MOVIE_FOUND)
                .movieId(movieId)
                .build());
    }

    public void createShowFoundEvent(UUID showId) {
        messageSender.sendShowFound(ShowFoundData.builder()
                .eventType(EventType.SHOW_FOUND)
                .showId(showId)
                .build());
    }

    public void createEpisodeFoundEvent(UUID episodeId) {
        messageSender.sendEpisodeFound(EpisodeFoundData.builder()
                .eventType(EventType.EPISODE_FOUND)
                .episodeId(episodeId)
                .build());
    }
}
