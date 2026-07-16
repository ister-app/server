package app.ister.disk.events.epubfilefound;

import app.ister.core.Handle;
import app.ister.core.entity.BookEntity;
import app.ister.core.entity.DirectoryEntity;
import app.ister.core.entity.MediaFileEntity;
import app.ister.core.entity.MetadataEntity;
import app.ister.core.enums.DirectoryType;
import app.ister.core.enums.EventType;
import app.ister.core.enums.ImageType;
import app.ister.core.enums.SearchEntityType;
import app.ister.core.eventdata.EpubFileFoundData;
import app.ister.core.eventdata.ImageFoundData;
import app.ister.core.repository.BookRepository;
import app.ister.core.repository.DirectoryRepository;
import app.ister.core.repository.ImageRepository;
import app.ister.core.repository.MediaFileRepository;
import app.ister.core.repository.MetadataRepository;
import app.ister.core.service.BookSeriesService;
import app.ister.core.service.MessageSender;
import app.ister.core.service.ScannerHelperService;
import app.ister.core.service.ServerEventService;
import app.ister.disk.epub.EpubInfo;
import app.ister.disk.epub.EpubParser;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.file.Files;
import java.time.LocalDate;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/**
 * Reads the contents of a scanned epub: OPF metadata becomes a MetadataEntity of the book, the
 * cover image is extracted to the cache directory, and the media-overlay flag and total duration
 * are stored on the MediaFileEntity. Whether an epub is a read-aloud ("karaoke") edition is
 * decided here, from the epub contents — never from the filename.
 */
@Slf4j
@Service
@Transactional
@RequiredArgsConstructor
public class HandleEpubFileFound implements Handle<EpubFileFoundData> {
    private static final String FILE_URI_SCHEME = "file://";

    private final DirectoryRepository directoryRepository;
    private final MediaFileRepository mediaFileRepository;
    private final MetadataRepository metadataRepository;
    private final BookRepository bookRepository;
    private final ImageRepository imageRepository;
    private final EpubParser epubParser;
    private final MessageSender messageSender;
    private final ServerEventService serverEventService;
    private final ScannerHelperService scannerHelperService;
    private final BookSeriesService bookSeriesService;

    @Override
    public EventType handles() {
        return EventType.EPUB_FILE_FOUND;
    }

    @RabbitListener(queues = "#{@diskQueueNamingConfig.getEpubFileFoundQueues()}")
    @Override
    public void listener(EpubFileFoundData epubFileFoundData) {
        Handle.super.listener(epubFileFoundData);
    }

    @Override
    public void handle(EpubFileFoundData messageData) {
        DirectoryEntity directoryEntity = directoryRepository.findById(messageData.getDirectoryEntityUUID()).orElseThrow();
        Optional<MediaFileEntity> mediaFile = mediaFileRepository.findById(messageData.getMediaFileEntityUUID());
        Optional<BookEntity> book = bookRepository.findById(messageData.getBookEntityUUID());
        if (mediaFile.isEmpty() || book.isEmpty()) {
            log.warn("EpubFileFound: media file or book not found for path={} — skipping", messageData.getPath());
            return;
        }

        Optional<EpubInfo> parsed = epubParser.parse(Path.of(messageData.getPath()));
        if (parsed.isEmpty()) {
            return;
        }
        EpubInfo info = parsed.get();

        MediaFileEntity entity = mediaFile.get();
        entity.setMediaOverlays(info.mediaOverlays());
        if (info.durationInMilliseconds() > 0) {
            entity.setDurationInMilliseconds(info.durationInMilliseconds());
        }
        boolean isbnIsNew = info.isbn() != null && !info.isbn().equals(entity.getIsbn());
        if (info.isbn() != null) {
            entity.setIsbn(info.isbn());
        }
        mediaFileRepository.save(entity);

        saveBookMetadata(book.get(), info, messageData.getPath());
        bookSeriesService.assignFromEpub(book.get(), info.seriesName(), info.seriesIndex());
        extractCover(directoryEntity, book.get(), info, messageData.getPath());

        if (isbnIsNew) {
            // Let Open Library re-match with the exact ISBN — a translated edition's ISBN resolves
            // to the original work and its first publication year. No loop: BOOK_FOUND never
            // dispatches EPUB_FILE_FOUND.
            serverEventService.createBookFoundEvent(book.get().getId());
        }
    }

    /**
     * One metadata row per source file, keyed on sourceUri: re-parsing the same epub updates the
     * row in place, and it coexists with the nfo row and the Open Library row — display preference
     * between them is an ordering concern of the API, not of the writers.
     */
    private void saveBookMetadata(BookEntity book, EpubInfo info, String path) {
        if (info.title() == null && info.description() == null && info.releaseYear() <= 0) {
            return;
        }
        String sourceUri = FILE_URI_SCHEME + path;
        MetadataEntity metadata = metadataRepository.findByBookEntityId(book.getId()).stream()
                .filter(existing -> sourceUri.equals(existing.getSourceUri()))
                .findFirst()
                .orElseGet(() -> MetadataEntity.builder()
                        .bookEntity(book)
                        .sourceUri(sourceUri)
                        .build());
        metadata.setTitle(info.title());
        metadata.setDescription(info.description());
        metadata.setLanguage(toIso3(info.language()));
        metadata.setReleased(info.releaseYear() > 0 ? LocalDate.of(info.releaseYear(), 1, 1) : null);
        metadataRepository.save(metadata);
        scannerHelperService.refreshBookReleaseYear(book);
        serverEventService.createSearchIndexEvent(SearchEntityType.BOOK, book.getId());
    }

    private String toIso3(String languageTag) {
        if (languageTag == null || languageTag.isBlank()) {
            return null;
        }
        try {
            String iso3 = Locale.forLanguageTag(languageTag.strip()).getISO3Language();
            return iso3.isBlank() ? null : iso3;
        } catch (Exception _) {
            return null;
        }
    }

    private void extractCover(DirectoryEntity libraryDir, BookEntity book, EpubInfo info, String epubPath) {
        if (info.coverEntry() == null) return;
        UUID bookId = book.getId();
        if (!imageRepository.findByBookEntityId(bookId).isEmpty()) return;

        List<DirectoryEntity> cacheDirs = directoryRepository
                .findByDirectoryTypeAndNodeEntity(DirectoryType.CACHE, libraryDir.getNodeEntity());
        if (cacheDirs.isEmpty()) return;
        DirectoryEntity cacheDir = cacheDirs.get(0);

        Optional<byte[]> coverBytes = epubParser.readEntry(Path.of(epubPath), info.coverEntry());
        if (coverBytes.isEmpty()) return;

        String extension = info.coverEntry().toLowerCase().endsWith(".png") ? "png" : "jpg";
        Path outputPath = Paths.get(cacheDir.getPath(), "book-covers", bookId.toString(), "cover." + extension);
        try {
            Files.createDirectories(outputPath.getParent());
            Files.write(outputPath, coverBytes.get());
        } catch (IOException e) {
            log.warn("Could not write epub cover for {}: {}", epubPath, e.getMessage());
            return;
        }

        messageSender.sendImageFound(ImageFoundData.builder()
                .eventType(EventType.IMAGE_FOUND)
                .directoryEntityId(cacheDir.getId())
                .path(outputPath.toString())
                .imageType(ImageType.COVER)
                .sourceUri(FILE_URI_SCHEME + epubPath)
                .bookEntityId(bookId)
                .build(), cacheDir.getName());
    }
}
