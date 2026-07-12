package app.ister.worker.events.bookfound;

import app.ister.core.Handle;
import app.ister.core.entity.BookEntity;
import app.ister.core.entity.MetadataEntity;
import app.ister.core.enums.EventType;
import app.ister.core.enums.ImageType;
import app.ister.core.enums.SearchEntityType;
import app.ister.core.eventdata.BookFoundData;
import app.ister.core.repository.BookRepository;
import app.ister.core.repository.ImageRepository;
import app.ister.core.repository.MetadataRepository;
import app.ister.core.service.ServerEventService;
import app.ister.worker.events.openlibrary.OpenLibraryService;
import app.ister.worker.events.tmdbmetadata.ImageDownloadService;
import app.ister.worker.events.tmdbmetadata.ImageSave;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.util.List;

import static app.ister.core.MessageQueue.APP_ISTER_SERVER_BOOK_FOUND;

/**
 * Open Library enrichment for books, modeled on HandleAlbumFound: local sources (epub OPF, nfo,
 * cover.jpg) win; this only fills a missing cover and a missing description.
 */
@Slf4j
@Service("workerHandleBookFound")
@Transactional
@RequiredArgsConstructor
public class HandleBookFound implements Handle<BookFoundData> {

    private final BookRepository bookRepository;
    private final ImageRepository imageRepository;
    private final MetadataRepository metadataRepository;
    private final OpenLibraryService openLibraryService;
    private final ImageDownloadService imageDownloadService;
    private final ServerEventService serverEventService;

    @Override
    public EventType handles() {
        return EventType.BOOK_FOUND;
    }

    @RabbitListener(queues = APP_ISTER_SERVER_BOOK_FOUND)
    @Override
    public void listener(BookFoundData data) {
        Handle.super.listener(data);
    }

    @Override
    public void handle(BookFoundData data) {
        bookRepository.findById(data.getBookId()).ifPresent(book -> {
            String authorName = book.getPersonEntity().getName();
            String bookName = book.getName();

            List<MetadataEntity> existingMetadata = metadataRepository.findByBookEntityId(book.getId());
            boolean hasDescription = existingMetadata.stream()
                    .anyMatch(m -> m.getDescription() != null && !m.getDescription().isBlank());
            boolean hasCover = !imageRepository.findByBookEntityId(book.getId()).isEmpty();
            if (hasDescription && hasCover) {
                return;
            }

            openLibraryService.getBookInfo(bookName, authorName).ifPresentOrElse(info -> {
                if (!hasCover && info.coverUrl() != null) {
                    downloadCover(book, info.coverUrl());
                }
                if (!hasDescription && info.description() != null) {
                    saveDescription(book, existingMetadata, info.description());
                }
            }, () -> log.debug("No Open Library match for author={} book={}", authorName, bookName));
        });
    }

    private void downloadCover(BookEntity book, String coverUrl) {
        try {
            imageDownloadService.downloadAndSave(coverUrl, ImageType.COVER, "eng",
                    "OpenLibrary://" + coverUrl,
                    new ImageSave.MediaEntityRef(null, null, null, null, null, book));
        } catch (IOException e) {
            log.warn("Failed to download cover for book={}: {}", book.getName(), e.getMessage());
        }
    }

    private void saveDescription(BookEntity book, List<MetadataEntity> existingMetadata, String description) {
        if (existingMetadata.isEmpty()) {
            metadataRepository.save(MetadataEntity.builder()
                    .description(description)
                    .bookEntity(book)
                    .sourceUri("openlibrary://book/" + book.getName())
                    .build());
        } else {
            // Rebuild preserving existing fields, adding the description (same as HandleAlbumFound).
            MetadataEntity existing = existingMetadata.getFirst();
            metadataRepository.deleteAll(existingMetadata);
            metadataRepository.save(MetadataEntity.builder()
                    .title(existing.getTitle())
                    .description(description)
                    .released(existing.getReleased())
                    .genre(existing.getGenre())
                    .language(existing.getLanguage())
                    .bookEntity(book)
                    .sourceUri(existing.getSourceUri())
                    .build());
        }
        serverEventService.createSearchIndexEvent(SearchEntityType.BOOK, book.getId());
    }
}
