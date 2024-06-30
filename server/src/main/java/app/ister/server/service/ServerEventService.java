package app.ister.server.service;

import app.ister.server.enums.EventType;
import app.ister.server.eventHandlers.data.EpisodeFoundData;
import app.ister.server.eventHandlers.data.ShowFoundData;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
@Slf4j
public class ServerEventService {
    @Autowired
    private MessageSender messageSender;

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
