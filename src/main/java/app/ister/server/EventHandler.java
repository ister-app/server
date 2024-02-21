package app.ister.server;

import app.ister.server.enums.DiskType;
import app.ister.server.repository.DiskRepository;
import app.ister.server.repository.ServerEventRepository;
import app.ister.server.scanner.MediaFileAnalyzer;
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
    private MediaFileAnalyzer mediaFileAnalyzer;
    @Autowired
    private DiskRepository diskRepository;

    private boolean handling;

    @Scheduled(cron = "*/5 * * * * *")
    @Transactional
    public void handleEvents() {
        if (!handling) {
            handling = true;
            var cacheDisk = diskRepository.findByDiskType(DiskType.CACHE).stream().findFirst().orElseThrow();
            serverEventRepository.findAll().forEach(serverEventEntity -> {
                log.debug("Handling event: {}", serverEventEntity.getEpisodeEntity().getId());
                mediaFileAnalyzer.checkMediaFile(serverEventEntity.getDiskEntity(), serverEventEntity.getEpisodeEntity(), serverEventEntity.getPath());
                mediaFileAnalyzer.createBackground(cacheDisk, serverEventEntity.getEpisodeEntity(), cacheDisk.getPath() + serverEventEntity.getEpisodeEntity().getId() + ".jpg", serverEventEntity.getPath());
                serverEventRepository.delete(serverEventEntity);
            });
            handling = false;
        }
    }
}
