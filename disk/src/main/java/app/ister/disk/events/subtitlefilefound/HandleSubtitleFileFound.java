package app.ister.disk.events.subtitlefilefound;

import app.ister.core.entity.DirectoryEntity;
import app.ister.core.entity.MediaFileStreamEntity;
import app.ister.core.enums.EventType;
import app.ister.core.enums.StreamCodecType;
import app.ister.core.eventdata.SubtitleFileFoundData;
import app.ister.core.repository.DirectoryRepository;
import app.ister.core.repository.MediaFileStreamRepository;
import app.ister.core.repository.OtherPathFileRepository;
import app.ister.core.service.ScannerHelperService;
import app.ister.core.Handle;
import app.ister.disk.scanner.PathObject;
import app.ister.disk.scanner.enums.DirType;
import lombok.RequiredArgsConstructor;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import static app.ister.disk.events.subtitlefilefound.SubtitleFilePathParser.mediaFileAndSubtitleFileBelongTogether;


@Service
@Transactional
@RequiredArgsConstructor
public class HandleSubtitleFileFound implements Handle<SubtitleFileFoundData> {
    private final DirectoryRepository directoryRepository;
    private final ScannerHelperService scannerHelperService;
    private final MediaFileStreamRepository mediaFileStreamRepository;
    private final OtherPathFileRepository otherPathFileRepository;

    @Override
    public EventType handles() {
        return EventType.SUBTITLE_FILE_FOUND;
    }

    @RabbitListener(queues = "#{@diskQueueNamingConfig.getSubtitleFileFoundQueues()}")
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
            if (directoryEntity.getId().equals(mediaFileEntity.getDirectoryEntity().getId()) && mediaFileAndSubtitleFileBelongTogether(mediaFileEntity.getPath(), path)
                    && !mediaFileStreamRepository.existsByMediaFileEntityAndStreamIndexAndPath(mediaFileEntity, 0, path)) {
                var saved = mediaFileStreamRepository.save(MediaFileStreamEntity.builder()
                        .mediaFileEntity(mediaFileEntity)
                        .codecName("subtitle srt")
                        .codecType(StreamCodecType.EXTERNAL_SUBTITLE)
                        .language(SubtitleFilePathParser.langCodeToIso3(path))
                        .path(path)
                        .build());
                otherPathFileRepository.findByDirectoryEntityAndPath(directoryEntity, path).ifPresent(f -> {
                    f.setMediaFileStreamEntity(saved);
                    otherPathFileRepository.save(f);
                });
            }
        });
    }
}
