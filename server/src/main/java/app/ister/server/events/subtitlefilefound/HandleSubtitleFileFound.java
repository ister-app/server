package app.ister.server.events.subtitlefilefound;

import app.ister.server.entitiy.DirectoryEntity;
import app.ister.server.entitiy.MediaFileStreamEntity;
import app.ister.server.enums.EventType;
import app.ister.server.enums.StreamCodecType;
import app.ister.server.events.Handle;
import app.ister.server.events.MessageQueue;
import app.ister.server.repository.DirectoryRepository;
import app.ister.server.repository.MediaFileStreamRepository;
import app.ister.server.scanner.PathObject;
import app.ister.server.scanner.enums.DirType;
import app.ister.server.service.ScannerHelperService;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import static app.ister.server.events.subtitlefilefound.SubtitleFilePathParser.mediaFileAndSubtitleFileBelongTogether;

@Service
@Transactional
public class HandleSubtitleFileFound implements Handle<SubtitleFileFoundData> {
    @Autowired
    private DirectoryRepository directoryRepository;
    @Autowired
    private ScannerHelperService scannerHelperService;
    @Autowired
    private MediaFileStreamRepository mediaFileStreamRepository;

    @Override
    public EventType handles() {
        return EventType.SUBTITLE_FILE_FOUND;
    }

    @RabbitListener(queues = MessageQueue.APP_ISTER_SERVER_SUBTITLE_FILE_FOUND)
    @Override
    public void listener(SubtitleFileFoundData subtitleFileFoundData) {
        Handle.super.listener(subtitleFileFoundData);
    }

    @Override
    public Boolean handle(SubtitleFileFoundData subtitleFileFoundData) {
        var directoryEntity = directoryRepository.findById(subtitleFileFoundData.getDirectoryEntityUUID()).orElseThrow();
        analyze(directoryEntity, subtitleFileFoundData.getPath());
        return true;
    }

    private void analyze(DirectoryEntity directoryEntity, String path) {
        PathObject pathObject = new PathObject(path);
        if (pathObject.getDirType().equals(DirType.EPISODE)) {
            analyzeSubtitleFile(directoryEntity, path, pathObject);
        }
    }


    private void analyzeSubtitleFile(DirectoryEntity directoryEntity, String path, PathObject pathObject) {
        var episode = scannerHelperService.getOrCreateEpisode(directoryEntity.getLibraryEntity(), pathObject.getName(), pathObject.getYear(), pathObject.getSeason(), pathObject.getEpisode());
        episode.getMediaFileEntities().forEach(mediaFileEntity -> {
            if (directoryEntity.getId().equals(mediaFileEntity.getDirectoryEntity().getId()) && mediaFileAndSubtitleFileBelongTogether(mediaFileEntity.getPath(), path)) {
                var mediaFileStream = MediaFileStreamEntity.builder()
                        .mediaFileEntity(mediaFileEntity)
                        .codecName("subtitle srt")
                        .codecType(StreamCodecType.EXTERNAL_SUBTITLE)
                        .language(SubtitleFilePathParser.langCodeToIso3(path))
                        .path(path);
                mediaFileStreamRepository.save(mediaFileStream.build());
            }
        });
    }
}
