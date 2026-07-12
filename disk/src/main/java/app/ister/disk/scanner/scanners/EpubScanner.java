package app.ister.disk.scanner.scanners;

import app.ister.core.entity.BaseEntity;
import app.ister.core.entity.BookEntity;
import app.ister.core.entity.DirectoryEntity;
import app.ister.core.entity.LibraryEntity;
import app.ister.core.entity.MediaFileEntity;
import app.ister.core.entity.PersonEntity;
import app.ister.core.enums.EventType;
import app.ister.core.enums.LibraryType;
import app.ister.core.eventdata.EpubFileFoundData;
import app.ister.core.repository.MediaFileRepository;
import app.ister.core.service.MessageSender;
import app.ister.core.service.ScannerHelperService;
import app.ister.disk.scanner.BookPathObject;
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
 * Registers epub files in a book library: creates the author and book from the path and attaches
 * the epub as a MediaFileEntity of the book. Reading the epub contents (metadata, cover, media
 * overlays) happens asynchronously in HandleEpubFileFound.
 */
@Component
@Slf4j
@Transactional(propagation = Propagation.REQUIRES_NEW)
@RequiredArgsConstructor
public class EpubScanner implements Scanner {
    private final ScannerHelperService scannerHelperService;
    private final MediaFileRepository mediaFileRepository;
    private final MessageSender messageSender;

    @Override
    public boolean analyzable(Path path, boolean isRegularFile, long size) {
        return isRegularFile && path.toString().toLowerCase().endsWith(".epub");
    }

    /**
     * Returns true if this path is an epub file in the given book library directory.
     */
    public boolean analyzable(Path path, boolean isRegularFile, DirectoryEntity directoryEntity) {
        if (!isRegularFile) {
            return false;
        }
        if (directoryEntity.getLibraryEntity() == null
                || directoryEntity.getLibraryEntity().getLibraryType() != LibraryType.BOOK) {
            return false;
        }
        BookPathObject bookPath = new BookPathObject(directoryEntity.getPath(), path.toString());
        return bookPath.getFileType().equals(FileType.EPUB);
    }

    @Override
    public Optional<BaseEntity> analyze(DirectoryEntity directoryEntity, Path path, boolean isRegularFile, long size) {
        BookPathObject bookPath = new BookPathObject(directoryEntity.getPath(), path.toString());
        if (!bookPath.getFileType().equals(FileType.EPUB)) {
            return Optional.empty();
        }

        LibraryEntity library = directoryEntity.getLibraryEntity();
        PersonEntity author = scannerHelperService.getOrCreatePerson(library, bookPath.getAuthorName(), bookPath.getAuthorYear());
        BookEntity book = scannerHelperService.getOrCreateBook(library, author, bookPath.getBookName(), bookPath.getBookYear());

        Optional<MediaFileEntity> existing = mediaFileRepository.findByDirectoryEntityAndPath(directoryEntity, path.toString());
        final String directoryName = directoryEntity.getName();
        MediaFileEntity entity;
        if (existing.isEmpty()) {
            entity = MediaFileEntity.builder()
                    .directoryEntityId(directoryEntity.getId())
                    .bookEntity(book)
                    .path(path.toString())
                    .size(size).build();
            mediaFileRepository.save(entity);
        } else {
            entity = existing.get();
            if (entity.getBookEntity() == null || !entity.getBookEntity().getId().equals(book.getId())) {
                log.warn("Fixing wrong book association for {}: now book {} ({})", path, book.getName(), book.getId());
                entity.setBookEntity(book);
                mediaFileRepository.save(entity);
            } else {
                return Optional.of(book);
            }
        }
        sendEpubFileFoundAfterCommit(EpubFileFoundData.builder()
                .eventType(EventType.EPUB_FILE_FOUND)
                .directoryEntityUUID(directoryEntity.getId())
                .bookEntityUUID(book.getId())
                .mediaFileEntityUUID(entity.getId())
                .path(path.toString()).build(), directoryName);
        return Optional.of(book);
    }

    private void sendEpubFileFoundAfterCommit(EpubFileFoundData data, String directoryName) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                messageSender.sendEpubFileFound(data, directoryName);
            }
        });
    }
}
