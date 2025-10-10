package app.ister.server.events.nfofilefound;

import app.ister.server.entitiy.DirectoryEntity;
import app.ister.server.entitiy.MetadataEntity;
import app.ister.server.enums.EventType;
import app.ister.server.events.Handle;
import app.ister.server.events.MessageQueue;
import app.ister.server.nfo.Parser;
import app.ister.server.repository.DirectoryRepository;
import app.ister.server.repository.MetadataRepository;
import app.ister.server.scanner.PathObject;
import app.ister.server.scanner.enums.DirType;
import app.ister.server.service.ScannerHelperService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.FileNotFoundException;

@Service
@Transactional
@Slf4j
public class HandleNfoFileFound implements Handle<NfoFileFoundData> {
    @Autowired
    private DirectoryRepository directoryRepository;
    @Autowired
    private MetadataRepository metadataRepository;
    @Autowired
    private ScannerHelperService scannerHelperService;

    @Override
    public EventType handles() {
        return EventType.NFO_FILE_FOUND;
    }

    @RabbitListener(queues = MessageQueue.APP_ISTER_SERVER_NFO_FILE_FOUND)
    @Override
    public void listener(NfoFileFoundData nfoFileFoundData) {
        Handle.super.listener(nfoFileFoundData);
    }

    @Override
    public Boolean handle(NfoFileFoundData nfoFileFoundData) {
        var directoryEntity = directoryRepository.findById(nfoFileFoundData.getDirectoryEntityUUID()).orElseThrow();
        analyze(directoryEntity, nfoFileFoundData.getPath());
        return true;
    }

    public void analyze(DirectoryEntity directoryEntity, String path) {
        PathObject pathObject = new PathObject(path);
        if (pathObject.getDirType().equals(DirType.SHOW)) {
            analyzeShow(directoryEntity, path, pathObject);
        } else if (pathObject.getDirType().equals(DirType.EPISODE)) {
            analyzeEpisode(directoryEntity, path, pathObject);
        }
    }

    private void analyzeShow(DirectoryEntity directoryEntity, String path, PathObject pathObject) {
        var show = scannerHelperService.getOrCreateShow(directoryEntity.getLibraryEntity(), pathObject.getName(), pathObject.getYear());
        try {
            var parsed = Parser.parseShow(path).orElseThrow();
            metadataRepository.save(MetadataEntity.builder()
                    .title(parsed.getTitle())
                    .description(parsed.getPlot())
                    .released(parsed.getPremiered())
                    .showEntity(show)
                    .sourceUri("file://" + path).build());
        } catch (FileNotFoundException e) {
            log.error("Something went wrong when nfo parsing: {}", path);
        }
    }

    private void analyzeEpisode(DirectoryEntity directoryEntity, String path, PathObject pathObject) {
        var episode = scannerHelperService.getOrCreateEpisode(directoryEntity.getLibraryEntity(), pathObject.getName(), pathObject.getYear(), pathObject.getSeason(), pathObject.getEpisode());
        try {
            var parsed = Parser.parseEpisode(path).orElseThrow();
            metadataRepository.save(MetadataEntity.builder()
                    .title(parsed.getTitle())
                    .description(parsed.getPlot())
                    .released(parsed.getAired())
                    .episodeEntity(episode)
                    .sourceUri("file://" + path).build());
        } catch (FileNotFoundException e) {
            log.error("Something went wrong when nfo parsing: {}", path);
        }
    }
}
