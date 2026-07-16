package app.ister.disk.scanner.scanners;

import app.ister.core.entity.BaseEntity;
import app.ister.core.entity.BookEntity;
import app.ister.core.entity.DirectoryEntity;
import app.ister.core.entity.LibraryEntity;
import app.ister.core.entity.MediaFileEntity;
import app.ister.core.entity.SeriesEntity;
import app.ister.core.enums.EventType;
import app.ister.core.enums.LibraryType;
import app.ister.core.eventdata.ComicFileFoundData;
import app.ister.core.eventdata.EpubFileFoundData;
import app.ister.core.repository.MediaFileRepository;
import app.ister.core.service.MessageSender;
import app.ister.core.service.ScannerHelperService;
import app.ister.disk.scanner.ComicPathObject;
import app.ister.disk.scanner.enums.FileType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.nio.file.Path;
import java.util.Optional;

/**
 * Registers comic volume files (cbz, pdf, epub) in a COMIC library: creates the series and the
 * volume (a BookEntity without author) from the path and attaches the file as a MediaFileEntity of
 * the volume. All formats of one volume (same basename) converge on one volume row. Reading the
 * file contents happens asynchronously: cbz/pdf via COMIC_FILE_FOUND, epub volumes reuse the
 * complete EPUB_FILE_FOUND pipeline (OPF metadata, cover, media overlays).
 */
@Component
@Slf4j
@Transactional(propagation = Propagation.REQUIRES_NEW)
@RequiredArgsConstructor
public class ComicScanner implements Scanner {
    private final ScannerHelperService scannerHelperService;
    private final MediaFileRepository mediaFileRepository;
    private final MessageSender messageSender;

    @Override
    public boolean analyzable(Path path, boolean isRegularFile, long size) {
        String lower = path.toString().toLowerCase();
        return isRegularFile
                && (lower.endsWith(".cbz") || lower.endsWith(".pdf") || lower.endsWith(".epub"));
    }

    /** Returns true if this path is a comic volume file in the given COMIC library directory. */
    public boolean analyzable(Path path, boolean isRegularFile, DirectoryEntity directoryEntity) {
        if (!isRegularFile) {
            return false;
        }
        if (directoryEntity.getLibraryEntity() == null
                || directoryEntity.getLibraryEntity().getLibraryType() != LibraryType.COMIC) {
            return false;
        }
        ComicPathObject comicPath = new ComicPathObject(directoryEntity.getPath(), path.toString());
        return comicPath.getFileType().equals(FileType.COMIC);
    }

    @Override
    public Optional<BaseEntity> analyze(DirectoryEntity directoryEntity, Path path, boolean isRegularFile, long size) {
        ComicPathObject comicPath = new ComicPathObject(directoryEntity.getPath(), path.toString());
        if (!comicPath.getFileType().equals(FileType.COMIC)) {
            return Optional.empty();
        }

        LibraryEntity library = directoryEntity.getLibraryEntity();
        SeriesEntity series = scannerHelperService.getOrCreateComicSeries(
                library, comicPath.getSeriesName(), comicPath.getStartYear());
        BookEntity volume = scannerHelperService.getOrCreateComicVolume(
                library, series, comicPath.getVolumeName(), 0,
                comicPath.getVolumeNumber(), comicPath.getVolumeTitle());

        Optional<MediaFileEntity> existing = mediaFileRepository.findByDirectoryEntityAndPath(directoryEntity, path.toString());
        MediaFileEntity entity;
        if (existing.isEmpty()) {
            entity = MediaFileEntity.builder()
                    .directoryEntityId(directoryEntity.getId())
                    .bookEntity(volume)
                    .path(path.toString())
                    .size(size).build();
            mediaFileRepository.save(entity);
        } else {
            entity = existing.get();
            if (entity.getBookEntity() == null || !entity.getBookEntity().getId().equals(volume.getId())) {
                log.warn("Fixing wrong volume association for {}: now volume {} ({})", path, volume.getName(), volume.getId());
                entity.setBookEntity(volume);
                mediaFileRepository.save(entity);
            } else {
                return Optional.of(volume);
            }
        }
        sendAfterCommit(directoryEntity, volume, entity, path, comicPath.getExtension());
        return Optional.of(volume);
    }

    private void sendAfterCommit(DirectoryEntity directoryEntity, BookEntity volume,
                                 MediaFileEntity mediaFile, Path path, String extension) {
        final String directoryName = directoryEntity.getName();
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                if ("epub".equals(extension)) {
                    messageSender.sendEpubFileFound(EpubFileFoundData.builder()
                            .eventType(EventType.EPUB_FILE_FOUND)
                            .directoryEntityUUID(directoryEntity.getId())
                            .bookEntityUUID(volume.getId())
                            .mediaFileEntityUUID(mediaFile.getId())
                            .path(path.toString()).build(), directoryName);
                } else {
                    messageSender.sendComicFileFound(ComicFileFoundData.builder()
                            .eventType(EventType.COMIC_FILE_FOUND)
                            .directoryEntityUUID(directoryEntity.getId())
                            .bookEntityUUID(volume.getId())
                            .mediaFileEntityUUID(mediaFile.getId())
                            .path(path.toString()).build(), directoryName);
                }
            }
        });
    }
}
