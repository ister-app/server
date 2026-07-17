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
import app.ister.core.config.LanguageProperties;
import app.ister.worker.events.openlibrary.OpenLibraryService;
import app.ister.worker.events.tmdbmetadata.ImageDownloadService;
import app.ister.worker.events.tmdbmetadata.ImageSave;
import app.ister.worker.events.wikipedia.WikidataBookSeriesService;
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
    private static final String WIKIDATA_URI_PREFIX = "wikidata://";

    private final BookRepository bookRepository;
    private final ImageRepository imageRepository;
    private final MetadataRepository metadataRepository;
    private final MediaFileRepository mediaFileRepository;
    private final OpenLibraryService openLibraryService;
    private final ImageDownloadService imageDownloadService;
    private final ServerEventService serverEventService;
    private final ScannerHelperService scannerHelperService;
    private final WikidataBookSeriesService wikidataBookSeriesService;
    private final LanguageProperties languageProperties;

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
            if (book.getPersonEntity() == null) {
                // A comic volume: Open Library is a book database and BOOK_FOUND should never
                // fire for comics; the series-level Wikipedia enrichment covers them.
                return;
            }
            String authorName = book.getPersonEntity().getName();
            String bookName = book.getName();

            List<MetadataEntity> existingMetadata = metadataRepository.findByBookEntityId(book.getId());
            boolean hasDescription = existingMetadata.stream()
                    .anyMatch(m -> m.getDescription() != null && !m.getDescription().isBlank());
            boolean hasCover = !imageRepository.findByBookEntityId(book.getId()).isEmpty();
            boolean hasOpenLibraryYear = existingMetadata.stream()
                    .anyMatch(m -> isOpenLibraryRow(m) && m.getReleased() != null);
            boolean hasWikidataYear = existingMetadata.stream()
                    .anyMatch(m -> isWikidataRow(m) && m.getReleased() != null);
            // Wikidata is only consulted for series books: it fills a missing series position and
            // the original (untranslated) publication year, which the Open Library match — often a
            // translated edition's work — gets wrong.
            boolean wantsWikidata = book.getSeriesEntity() != null
                    && (book.getSeriesIndex() == null || !hasWikidataYear);
            if (hasDescription && hasCover && hasOpenLibraryYear && !wantsWikidata) {
                return;
            }

            if (!hasDescription || !hasCover || !hasOpenLibraryYear) {
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
            }

            if (wantsWikidata) {
                enrichFromWikidata(book, existingMetadata);
            }
        });
    }

    /**
     * Series position and original publication year from Wikidata. The index is only adopted when
     * the book has none — epub series metadata stays authoritative. The year is written as the
     * book's single wikidata:// metadata row, which
     * {@link ScannerHelperService#refreshBookReleaseYear} prefers over the Open Library year.
     */
    private void enrichFromWikidata(BookEntity book, List<MetadataEntity> existingMetadata) {
        String title = book.getTitle() != null ? book.getTitle() : book.getName();
        wikidataBookSeriesService
                .findBookInSeries(title, book.getSeriesEntity().getName(), languageProperties.tags())
                .ifPresent(info -> {
                    if (book.getSeriesIndex() == null && info.seriesIndex() != null) {
                        book.setSeriesIndex(info.seriesIndex());
                        bookRepository.save(book);
                    }
                    if (info.firstPublicationYear() != null) {
                        MetadataEntity metadata = existingMetadata.stream()
                                .filter(this::isWikidataRow)
                                .findFirst()
                                .orElseGet(() -> MetadataEntity.builder()
                                        .bookEntity(book)
                                        .build());
                        metadata.setSourceUri(WIKIDATA_URI_PREFIX + info.wikidataId());
                        metadata.setReleased(LocalDate.of(info.firstPublicationYear(), 1, 1));
                        metadataRepository.save(metadata);
                        scannerHelperService.refreshBookReleaseYear(book);
                    }
                    serverEventService.createSearchIndexEvent(SearchEntityType.BOOK, book.getId());
                });
    }

    private boolean isWikidataRow(MetadataEntity metadata) {
        return metadata.getSourceUri() != null && metadata.getSourceUri().startsWith(WIKIDATA_URI_PREFIX);
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
