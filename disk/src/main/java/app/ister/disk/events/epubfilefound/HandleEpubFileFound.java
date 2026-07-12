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
import app.ister.core.service.MessageSender;
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
        mediaFileRepository.save(entity);

        saveBookMetadata(book.get(), info, messageData.getPath());
        extractCover(directoryEntity, book.get(), info, messageData.getPath());
    }

    /**
     * The OPF metadata is stored in the epub's own language. Existing metadata rows for that
     * language are kept (nfo or a richer epub may already have filled them); Open Library
     * enrichment fills the other configured languages.
     */
    private void saveBookMetadata(BookEntity book, EpubInfo info, String path) {
        if (info.title() == null && info.description() == null) {
            return;
        }
        String iso3Language = toIso3(info.language());
        List<MetadataEntity> existing = metadataRepository.findByBookEntityId(book.getId());
        boolean hasLanguage = existing.stream()
                .anyMatch(metadata -> iso3Language == null || iso3Language.equals(metadata.getLanguage()));
        if (hasLanguage && !existing.isEmpty()) {
            return;
        }
        metadataRepository.save(MetadataEntity.builder()
                .title(info.title())
                .description(info.description())
                .language(iso3Language)
                .bookEntity(book)
                .sourceUri(FILE_URI_SCHEME + path)
                .build());
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
