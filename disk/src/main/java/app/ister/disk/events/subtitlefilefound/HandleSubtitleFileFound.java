package app.ister.disk.events.subtitlefilefound;

import app.ister.core.status.ActivityContext;
import app.ister.core.status.ActivitySubjects;
import app.ister.core.entity.DirectoryEntity;
import app.ister.core.enums.EventType;
import app.ister.core.eventdata.SubtitleFileFoundData;
import app.ister.core.repository.DirectoryRepository;
import app.ister.core.repository.MediaFileRepository;
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
    private final MediaFileRepository mediaFileRepository;
    private final SidecarSubtitleLinker sidecarSubtitleLinker;

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
    public void handle(app.ister.core.eventdata.SubtitleFileFoundData subtitleFileFoundData) {
        var directoryEntity = directoryRepository.findById(subtitleFileFoundData.getDirectoryEntityUUID()).orElseThrow();
        ActivityContext.report(ActivitySubjects.describe(directoryEntity).withTitle(ActivitySubjects.fileName(subtitleFileFoundData.getPath())));
        analyze(directoryEntity, subtitleFileFoundData.getPath());
    }

    private void analyze(DirectoryEntity directoryEntity, String path) {
        PathObject pathObject = new PathObject(path);
        if (pathObject.getDirType().equals(DirType.EPISODE)) {
            analyzeSubtitleFile(directoryEntity, path, pathObject);
        }
    }


    private void analyzeSubtitleFile(DirectoryEntity directoryEntity, String path, PathObject pathObject) {
        var episode = scannerHelperService.getOrCreateEpisode(directoryEntity.getLibraryEntity(), pathObject.getName(), pathObject.getYear(), pathObject.getSeason(), pathObject.getEpisode());
        // Only the media files that are already there: one that is scanned later links this
        // subtitle itself when its analysis persists (SidecarSubtitleLinker.linkAllFor).
        mediaFileRepository.findByEpisodeEntityId(episode.getId()).stream()
                .filter(mediaFileEntity -> directoryEntity.getId().equals(mediaFileEntity.getDirectoryEntityId())
                        && mediaFileAndSubtitleFileBelongTogether(mediaFileEntity.getPath(), path))
                .forEach(mediaFileEntity -> sidecarSubtitleLinker.link(directoryEntity, mediaFileEntity, path));
    }
}
