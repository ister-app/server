package app.ister.server.service;

import app.ister.server.entitiy.ServerEventEntity;
import app.ister.server.enums.EventType;
import app.ister.server.eventHandlers.EpisodeFoundData;
import app.ister.server.eventHandlers.ShowFoundData;
import app.ister.server.repository.ServerEventRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
@Slf4j
public class ServerEventService {
    @Autowired
    private ServerEventRepository serverEventRepository;

    private static final ObjectMapper objectMapper = new ObjectMapper();

    public void createShowFoundEvent(UUID showId) {
        try {
            ShowFoundData showFoundData = ShowFoundData.builder().showId(showId).build();
            String data = objectMapper.writeValueAsString(showFoundData);
            serverEventRepository.save(ServerEventEntity.builder()
                    .eventType(EventType.SHOW_FOUND)
                    .data(data).build());
        } catch (JsonProcessingException jpe) {
            log.error("Cannot convert ShowFoundData into JSON", jpe);
        }
    }

    public void createEpisodeFoundEvent(UUID episodeId) {
        try {
            EpisodeFoundData episodeFoundData = EpisodeFoundData.builder().episodeId(episodeId).build();
            String data = objectMapper.writeValueAsString(episodeFoundData);
            serverEventRepository.save(ServerEventEntity.builder()
                    .eventType(EventType.EPISODE_FOUND)
                    .data(data).build());
        } catch (JsonProcessingException jpe) {
            log.error("Cannot convert EpisodeFoundData into JSON", jpe);
        }
    }
}
