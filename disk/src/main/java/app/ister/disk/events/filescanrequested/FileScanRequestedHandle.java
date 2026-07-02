package app.ister.disk.events.filescanrequested;

import app.ister.core.entity.DirectoryEntity;
import app.ister.core.enums.EventType;
import app.ister.core.eventdata.FileScanRequestedData;
import app.ister.core.repository.DirectoryRepository;
import app.ister.core.Handle;
import app.ister.core.enums.LibraryType;
import app.ister.disk.scanner.scanners.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static app.ister.core.enums.EventType.FILE_SCAN_REQUESTED;

@Slf4j
@Service
@Transactional
public class FileScanRequestedHandle implements Handle<FileScanRequestedData> {
    private final DirectoryRepository directoryRepository;
    private final MediaFileScanner mediaFileScanner;
    private final ImageScanner imageScanner;
    private final NfoScanner nfoScanner;
    private final SubtitleScanner subtitleScanner;
    private final AudioScanner audioScanner;

    public FileScanRequestedHandle(DirectoryRepository directoryRepository, MediaFileScanner mediaFileScanner, ImageScanner imageScanner, NfoScanner nfoScanner, SubtitleScanner subtitleScanner, AudioScanner audioScanner) {
        this.directoryRepository = directoryRepository;
        this.mediaFileScanner = mediaFileScanner;
        this.imageScanner = imageScanner;
        this.nfoScanner = nfoScanner;
        this.subtitleScanner = subtitleScanner;
        this.audioScanner = audioScanner;
    }

    @RabbitListener(queues = "#{@diskQueueNamingConfig.getFileScanRequestedQueues()}")
    @Override
    public void listener(app.ister.core.eventdata.FileScanRequestedData fileScanRequestedData) {
        Handle.super.listener(fileScanRequestedData);
    }

    @Override
    public EventType handles() {
        return FILE_SCAN_REQUESTED;
    }

    @Override
    public void handle(app.ister.core.eventdata.FileScanRequestedData messageData) {
        DirectoryEntity directoryEntity = directoryRepository.findById(messageData.getDirectoryEntityUUID()).orElseThrow();
        boolean isMusic = directoryEntity.getLibraryEntity() != null
                && directoryEntity.getLibraryEntity().getLibraryType() == LibraryType.MUSIC;
        List<Scanner> scanners = isMusic
                ? List.of(audioScanner, imageScanner, nfoScanner)
                : List.of(mediaFileScanner, imageScanner, nfoScanner, subtitleScanner);
        for (Scanner scanner : scanners) {
            boolean canAnalyze = isMusic
                    ? switch (scanner) {
                        case AudioScanner s -> s.analyzable(messageData.getPath(), messageData.getRegularFile(), directoryEntity);
                        case ImageScanner s -> s.analyzable(messageData.getPath(), messageData.getRegularFile(), messageData.getSize(), directoryEntity);
                        case NfoScanner s -> s.analyzable(messageData.getPath(), messageData.getRegularFile(), messageData.getSize(), directoryEntity);
                        default -> scanner.analyzable(messageData.getPath(), messageData.getRegularFile(), messageData.getSize());
                    }
                    : scanner.analyzable(messageData.getPath(), messageData.getRegularFile(), messageData.getSize());
            if (canAnalyze) {
                log.debug("Scanning file: {}, with scanner: {}", messageData.getPath(), scanner);
                scanner.analyze(directoryEntity, messageData.getPath(), messageData.getRegularFile(), messageData.getSize());
            }
        }
    }
}
