package app.ister.server;

import app.ister.server.entitiy.DiskEntity;
import app.ister.server.entitiy.ServerEventEntity;
import app.ister.server.enums.DiskType;
import app.ister.server.enums.EventType;
import app.ister.server.repository.DiskRepository;
import app.ister.server.repository.ServerEventRepository;
import app.ister.server.scanner.MediaFileAnalyzer;
import app.ister.server.scanner.NfoAnalyzer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@Slf4j
public class EventHandler {
    @Autowired
    private ServerEventRepository serverEventRepository;
    @Autowired
    private DiskRepository diskRepository;
    @Autowired
    private MediaFileAnalyzer mediaFileAnalyzer;
    @Autowired
    private NfoAnalyzer nfoAnalyzer;

    private boolean handling;

    @Scheduled(cron = "*/5 * * * * *")
    @Transactional
    public void handleEvents() {
        if (!handling) {
            handling = true;
            var cacheDisk = diskRepository.findByDiskType(DiskType.CACHE).stream().findFirst().orElseThrow();
            serverEventRepository.findAll().forEach(serverEventEntity -> {
                log.debug("Handling event: {}, for type: {}", serverEventEntity.getPath(), serverEventEntity.getEventType());
                if (serverEventEntity.getEventType().equals(EventType.MEDIA_FILE_FOUND)) {
                    handleMediaFileFound(serverEventEntity, cacheDisk);
                } else if (serverEventEntity.getEventType().equals(EventType.NFO_FILE_FOUND)) {
                    handleNfoFileFound(serverEventEntity);
                }
                serverEventRepository.delete(serverEventEntity);
            });
            handling = false;
        }
    }

    private void handleMediaFileFound(ServerEventEntity serverEventEntity, DiskEntity cacheDisk) {
        mediaFileAnalyzer.checkMediaFile(serverEventEntity.getDiskEntity(), serverEventEntity.getEpisodeEntity(), serverEventEntity.getPath());
        mediaFileAnalyzer.createBackground(cacheDisk, serverEventEntity.getEpisodeEntity(), cacheDisk.getPath() + serverEventEntity.getEpisodeEntity().getId() + ".jpg", serverEventEntity.getPath());
    }

    private void handleNfoFileFound(ServerEventEntity serverEventEntity) {
        nfoAnalyzer.analyze(serverEventEntity.getDiskEntity(), serverEventEntity.getPath());
    }
}
