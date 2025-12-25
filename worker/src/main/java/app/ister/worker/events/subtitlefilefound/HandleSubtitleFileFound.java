package app.ister.worker.events.subtitlefilefound;

import app.ister.core.MessageQueue;
import app.ister.core.entitiy.DirectoryEntity;
import app.ister.core.entitiy.MediaFileStreamEntity;
import app.ister.core.enums.EventType;
import app.ister.core.enums.StreamCodecType;
import app.ister.core.eventdata.SubtitleFileFoundData;
import app.ister.core.repository.DirectoryRepository;
import app.ister.core.repository.MediaFileStreamRepository;
import app.ister.core.service.ScannerHelperService;
import app.ister.worker.events.Handle;
import app.ister.worker.scanner.PathObject;
import app.ister.worker.scanner.enums.DirType;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import static app.ister.worker.events.subtitlefilefound.SubtitleFilePathParser.mediaFileAndSubtitleFileBelongTogether;


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
    public void listener(app.ister.core.eventdata.SubtitleFileFoundData subtitleFileFoundData) {
        Handle.super.listener(subtitleFileFoundData);
    }

    @Override
    public Boolean handle(app.ister.core.eventdata.SubtitleFileFoundData subtitleFileFoundData) {
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
