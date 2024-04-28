package app.ister.server;

import app.ister.server.enums.EventType;
import app.ister.server.eventHandlers.Handle;
import app.ister.server.eventHandlers.HandleEpisodeFound;
import app.ister.server.eventHandlers.HandleMediaFileFound;
import app.ister.server.eventHandlers.HandleNewDirectoriesScanRequested;
import app.ister.server.eventHandlers.HandleNfoFileFound;
import app.ister.server.eventHandlers.HandleShowFound;
import app.ister.server.eventHandlers.HandleSubtitleFileFound;
import app.ister.server.repository.ServerEventRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Pageable;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@Slf4j
public class EventHandler {
    private final ServerEventRepository serverEventRepository;
    private final Map<EventType, Handle> handles;

    private boolean handling;

    public EventHandler(final ServerEventRepository serverEventRepository,
                        final Handle... handles) {
        this.serverEventRepository = serverEventRepository;
        this.handles = Arrays.stream(handles).collect(Collectors.toMap(Handle::handles, Function.identity()));

    }

    @Scheduled(cron = "*/5 * * * * *")
    @Transactional
    public void handleEvents() {
        if (!handling) {
            handling = true;
            serverEventRepository.findAllByFailedIsFalse(Pageable.ofSize(300)).forEach(serverEventEntity -> {
                Handle handle = handles.get(serverEventEntity.getEventType());
                if (handle != null) {
                    log.debug("Handling event: {}, for type: {}", serverEventEntity.getPath(), serverEventEntity.getEventType());
                    Boolean successful = handle.handle(serverEventEntity);
                    if (successful) {
                        serverEventRepository.delete(serverEventEntity);
                    } else {
                        serverEventEntity.setFailed(true);
                        serverEventRepository.save(serverEventEntity);
                    }
                } else {
                    log.debug("No handler found for event: {}, for type: {}", serverEventEntity.getPath(), serverEventEntity.getEventType());
                }
            });
            handling = false;
        }
    }
}
