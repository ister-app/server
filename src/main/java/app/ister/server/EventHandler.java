package app.ister.server;

import app.ister.server.eventHandlers.HandleMediaFileFound;
import app.ister.server.eventHandlers.HandleNfoFileFound;
import app.ister.server.eventHandlers.HandleSubtitleFileFound;
import app.ister.server.repository.ServerEventRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Pageable;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@Slf4j
public class EventHandler {
    @Autowired
    private ServerEventRepository serverEventRepository;
    @Autowired
    private HandleMediaFileFound handleMediaFileFound;
    @Autowired
    private HandleNfoFileFound handleNfoFileFound;
    @Autowired
    private HandleSubtitleFileFound handleSubtitleFileFound;


    private boolean handling;

    @Scheduled(cron = "*/5 * * * * *")
    @Transactional
    public void handleEvents() {
        if (!handling) {
            handling = true;
            serverEventRepository.findAll(Pageable.ofSize(30)).forEach(serverEventEntity -> {
                log.debug("Handling event: {}, for type: {}", serverEventEntity.getPath(), serverEventEntity.getEventType());
                Boolean successful = switch (serverEventEntity.getEventType()) {
                    case MEDIA_FILE_FOUND -> handleMediaFileFound.handle(serverEventEntity);
                    case NFO_FILE_FOUND -> handleNfoFileFound.handle(serverEventEntity);
                    case SUBTITLE_FILE_FOUND -> handleSubtitleFileFound.handle(serverEventEntity);
                };
                if (successful) {
                    serverEventRepository.delete(serverEventEntity);
                }
            });
            handling = false;
        }
    }
}
