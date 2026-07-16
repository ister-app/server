package app.ister.worker.events.bookfound;

import app.ister.core.Handle;
import app.ister.core.entity.BookEntity;
import app.ister.core.entity.MediaFileEntity;
import app.ister.core.entity.MetadataEntity;
import app.ister.core.enums.EventType;
import app.ister.core.enums.ImageType;
import app.ister.core.enums.SearchEntityType;
import app.ister.core.eventdata.BookFoundData;
import app.ister.core.repository.BookRepository;
import app.ister.core.repository.ImageRepository;
import app.ister.core.repository.MediaFileRepository;
import app.ister.core.repository.MetadataRepository;
import app.ister.core.service.ScannerHelperService;
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
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;

import static app.ister.core.MessageQueue.APP_ISTER_SERVER_BOOK_FOUND;

/**
 * Open Library enrichment for books. Local sources (epub OPF, nfo, cover.jpg) stay untouched: this
 * writes its own openlibrary:// metadata row (upserted, never replacing local rows) with the
 * description and the original first-publication year, and fills a missing cover. Matching is
 * ISBN-first (from the epub OPF) so translated editions resolve to the original work.
 */
@Slf4j
@Service("workerHandleBookFound")
@Transactional
@RequiredArgsConstructor
public class HandleBookFound implements Handle<BookFoundData> {

    private static final String OPEN_LIBRARY_URI_PREFIX = "openlibrary://";

    private final BookRepository bookRepository;
    private final ImageRepository imageRepository;
    private final MetadataRepository metadataRepository;
    private final MediaFileRepository mediaFileRepository;
    private final OpenLibraryService openLibraryService;
    private final ImageDownloadService imageDownloadService;
    private final ServerEventService serverEventService;
    private final ScannerHelperService scannerHelperService;

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
            boolean hasOpenLibraryYear = existingMetadata.stream()
                    .anyMatch(m -> isOpenLibraryRow(m) && m.getReleased() != null);
            if (hasDescription && hasCover && hasOpenLibraryYear) {
                return;
            }

            List<String> isbns = mediaFileRepository.findByBookEntityId(book.getId()).stream()
                    .map(MediaFileEntity::getIsbn)
                    .filter(Objects::nonNull)
                    .distinct()
                    .toList();

            openLibraryService.getBookInfo(bookName, authorName, isbns).ifPresentOrElse(info -> {
                if (!hasCover && info.coverUrl() != null) {
                    downloadCover(book, info.coverUrl());
                }
                saveOpenLibraryMetadata(book, existingMetadata, info);
            }, () -> log.debug("No Open Library match for author={} book={}", authorName, bookName));
        });
    }

    private boolean isOpenLibraryRow(MetadataEntity metadata) {
        return metadata.getSourceUri() != null && metadata.getSourceUri().startsWith(OPEN_LIBRARY_URI_PREFIX);
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

    /**
     * Upserts the book's single openlibrary:// metadata row; the local (nfo/epub) rows are never
     * touched — which row a client shows is decided by the API's source ordering. The row's
     * released date carries the work's first publication year, which then wins the display year
     * via {@link ScannerHelperService#refreshBookReleaseYear}.
     */
    private void saveOpenLibraryMetadata(BookEntity book, List<MetadataEntity> existingMetadata,
                                         OpenLibraryService.BookInfo info) {
        String sourceUri = info.workKey() != null
                ? "openlibrary:/" + info.workKey()
                : OPEN_LIBRARY_URI_PREFIX + "book/" + book.getName();
        MetadataEntity metadata = existingMetadata.stream()
                .filter(this::isOpenLibraryRow)
                .findFirst()
                .orElseGet(() -> MetadataEntity.builder()
                        .bookEntity(book)
                        // Open Library prose is English.
                        .language("eng")
                        .build());
        metadata.setSourceUri(sourceUri);
        if (info.description() != null) {
            metadata.setDescription(info.description());
        }
        if (info.firstPublishYear() > 0) {
            metadata.setReleased(LocalDate.of(info.firstPublishYear(), 1, 1));
        }
        metadataRepository.save(metadata);
        scannerHelperService.refreshBookReleaseYear(book);
        serverEventService.createSearchIndexEvent(SearchEntityType.BOOK, book.getId());
    }
}
