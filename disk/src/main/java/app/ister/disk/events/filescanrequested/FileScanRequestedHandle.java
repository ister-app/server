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
    private final EpubScanner epubScanner;
    private final ComicScanner comicScanner;

    public FileScanRequestedHandle(DirectoryRepository directoryRepository, MediaFileScanner mediaFileScanner, ImageScanner imageScanner, NfoScanner nfoScanner, SubtitleScanner subtitleScanner, AudioScanner audioScanner, EpubScanner epubScanner, ComicScanner comicScanner) {
        this.directoryRepository = directoryRepository;
        this.mediaFileScanner = mediaFileScanner;
        this.imageScanner = imageScanner;
        this.nfoScanner = nfoScanner;
        this.subtitleScanner = subtitleScanner;
        this.audioScanner = audioScanner;
        this.epubScanner = epubScanner;
        this.comicScanner = comicScanner;
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
        LibraryType libraryType = directoryEntity.getLibraryEntity() != null
                ? directoryEntity.getLibraryEntity().getLibraryType() : null;
        boolean isMusic = libraryType == LibraryType.MUSIC;
        boolean isBook = libraryType == LibraryType.BOOK;
        boolean isComic = libraryType == LibraryType.COMIC;
        boolean directoryScoped = isMusic || isBook || isComic;
        List<Scanner> scanners;
        if (isMusic) {
            scanners = List.of(audioScanner, imageScanner, nfoScanner);
        } else if (isBook) {
            scanners = List.of(epubScanner, audioScanner, imageScanner, nfoScanner);
        } else if (isComic) {
            scanners = List.of(comicScanner, imageScanner);
        } else {
            scanners = List.of(mediaFileScanner, imageScanner, nfoScanner, subtitleScanner);
        }
        for (Scanner scanner : scanners) {
            boolean canAnalyze = directoryScoped
                    ? switch (scanner) {
                        case AudioScanner s -> s.analyzable(messageData.getPath(), messageData.getRegularFile(), directoryEntity);
                        case EpubScanner s -> s.analyzable(messageData.getPath(), messageData.getRegularFile(), directoryEntity);
                        case ComicScanner s -> s.analyzable(messageData.getPath(), messageData.getRegularFile(), directoryEntity);
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
