package app.ister.disk.events.comicfilefound;

import app.ister.core.status.ActivityContext;
import app.ister.core.status.ActivitySubjects;
import app.ister.core.Handle;
import app.ister.core.entity.BookEntity;
import app.ister.core.entity.DirectoryEntity;
import app.ister.core.entity.MediaFileEntity;
import app.ister.core.entity.MetadataEntity;
import app.ister.core.entity.SeriesEntity;
import app.ister.core.enums.EventType;
import app.ister.core.enums.ImageType;
import app.ister.core.enums.ReadingDirection;
import app.ister.core.enums.SearchEntityType;
import app.ister.core.eventdata.ComicFileFoundData;
import app.ister.core.eventdata.ImageFoundData;
import app.ister.core.repository.BookRepository;
import app.ister.core.repository.DirectoryRepository;
import app.ister.core.repository.ImageRepository;
import app.ister.core.repository.MediaFileRepository;
import app.ister.core.repository.MetadataRepository;
import app.ister.core.repository.SeriesRepository;
import app.ister.core.EventHandlingException;
import app.ister.core.service.MessageSender;
import app.ister.core.storage.CacheDirectoryResolver;
import app.ister.core.storage.LocalCopy;
import app.ister.core.storage.SourceUris;
import app.ister.core.service.ScannerHelperService;
import app.ister.core.service.ServerEventService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.Month;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Reads the contents of a scanned cbz/pdf comic volume: the page count lands on the
 * MediaFileEntity, embedded ComicInfo.xml (cbz) becomes a MetadataEntity of the volume and can
 * refine the filename-derived series position and title, and the cover — the first cbz page, or
 * PDF page 1 rendered via PDFBox — is extracted to the cache directory. Epub comic volumes go
 * through the regular EPUB_FILE_FOUND pipeline instead.
 */
@Slf4j
@Service
@Transactional
@RequiredArgsConstructor
public class HandleComicFileFound implements Handle<ComicFileFoundData> {

    private final DirectoryRepository directoryRepository;
    private final MediaFileRepository mediaFileRepository;
    private final MetadataRepository metadataRepository;
    private final BookRepository bookRepository;
    private final SeriesRepository seriesRepository;
    private final ImageRepository imageRepository;
    private final CbzParser cbzParser;
    private final PdfParser pdfParser;
    private final MessageSender messageSender;
    private final ServerEventService serverEventService;
    private final ScannerHelperService scannerHelperService;
    private final LocalCopy localCopy;
    private final CacheDirectoryResolver cacheDirectoryResolver;

    @Override
    public EventType handles() {
        return EventType.COMIC_FILE_FOUND;
    }

    @RabbitListener(queues = "#{@diskQueueNamingConfig.getComicFileFoundQueues()}")
    @Override
    public void listener(ComicFileFoundData comicFileFoundData) {
        Handle.super.listener(comicFileFoundData);
    }

    @Override
    public void handle(ComicFileFoundData messageData) {
        DirectoryEntity directoryEntity = directoryRepository.findById(messageData.getDirectoryEntityUUID()).orElseThrow();
        Optional<MediaFileEntity> mediaFile = mediaFileRepository.findById(messageData.getMediaFileEntityUUID());
        Optional<BookEntity> volume = bookRepository.findById(messageData.getBookEntityUUID());
        if (mediaFile.isEmpty() || volume.isEmpty()) {
            log.warn("ComicFileFound: media file or volume not found for path={} — skipping", messageData.getPath());
            return;
        }
        ActivityContext.report(ActivitySubjects.describe(volume.get(), ActivitySubjects.describe(directoryEntity))
                .withTitle(ActivitySubjects.fileName(messageData.getPath())));

        String lower = messageData.getPath().toLowerCase();
        if (!lower.endsWith(".cbz") && !lower.endsWith(".pdf")) {
            log.warn("ComicFileFound for unsupported extension: {}", messageData.getPath());
            return;
        }
        try (LocalCopy.Handle local = localCopy.of(directoryEntity, messageData.getPath())) {
            Path path = local.path();
            if (lower.endsWith(".cbz")) {
                handleCbz(volume.get(), mediaFile.get(), path, messageData.getPath());
            } else {
                handlePdf(volume.get(), mediaFile.get(), path, messageData.getPath());
            }
        } catch (IOException e) {
            throw new EventHandlingException("Cannot read comic " + messageData.getPath(), e);
        }
        serverEventService.createSearchIndexEvent(SearchEntityType.BOOK, volume.get().getId());
    }

    private void handleCbz(BookEntity volume, MediaFileEntity mediaFile, Path path, String sourcePath) {
        List<String> pages = cbzParser.pages(path);
        if (!pages.isEmpty()) {
            mediaFile.setPageCount(pages.size());
            mediaFileRepository.save(mediaFile);
        }
        cbzParser.comicInfo(path).ifPresent(info -> applyComicInfo(volume, info, sourcePath));
        if (!pages.isEmpty()) {
            cbzParser.readEntry(path, pages.getFirst())
                    .ifPresent(bytes -> saveCover(volume, bytes,
                            extensionOf(pages.getFirst()), sourcePath));
        }
    }

    private void handlePdf(BookEntity volume, MediaFileEntity mediaFile, Path path, String sourcePath) {
        int pageCount = pdfParser.pageCount(path);
        if (pageCount > 0) {
            mediaFile.setPageCount(pageCount);
            mediaFileRepository.save(mediaFile);
        }
        // Rendering is failure-tolerant (native image without AWT): no cover is fine — a sibling
        // cbz/epub cover or a folder.jpg wins anyway.
        pdfParser.renderCoverJpeg(path)
                .ifPresent(bytes -> saveCover(volume, bytes, "jpg", sourcePath));
    }

    /**
     * Embedded metadata is authoritative over the filename: one row per source file (keyed on
     * sourceUri, idempotent re-parse), and Number/Title refine the volume's series position and
     * display title.
     */
    private void applyComicInfo(BookEntity volume, ComicInfoXml info, String path) {
        String sourceUri = SourceUris.of(path);
        MetadataEntity metadata = metadataRepository.findByBookEntityId(volume.getId()).stream()
                .filter(existing -> sourceUri.equals(existing.getSourceUri()))
                .findFirst()
                .orElseGet(() -> MetadataEntity.builder()
                        .bookEntity(volume)
                        .sourceUri(sourceUri)
                        .build());
        metadata.setTitle(info.title());
        metadata.setDescription(info.summary());
        metadata.setReleased(info.year() > 0 ? LocalDate.of(info.year(), Month.JANUARY, 1) : null);
        metadataRepository.save(metadata);

        boolean volumeChanged = false;
        if (info.number() != null && !info.number().equals(volume.getSeriesIndex())) {
            volume.setSeriesIndex(info.number());
            volumeChanged = true;
        }
        if (info.title() != null && !info.title().equals(volume.getTitle())) {
            volume.setTitle(info.title());
            volumeChanged = true;
        }
        if (volumeChanged) {
            bookRepository.save(volume);
        }
        scannerHelperService.refreshBookReleaseYear(volume);
        applyMangaTag(volume, info);
    }

    /**
     * An explicit Manga tag is authoritative for the whole series (idempotent on rescan, may
     * overwrite a weaker Wikidata-detected value); an absent tag leaves the default untouched so
     * the metadata enrichment can still fill it.
     */
    private void applyMangaTag(BookEntity volume, ComicInfoXml info) {
        SeriesEntity series = volume.getSeriesEntity();
        if (info.manga() == null || series == null) {
            return;
        }
        ReadingDirection detected = info.rightToLeft() ? ReadingDirection.RTL : ReadingDirection.LTR;
        if (series.getDefaultReadingDirection() != detected) {
            series.setDefaultReadingDirection(detected);
            seriesRepository.save(series);
        }
    }

    private void saveCover(BookEntity volume, byte[] coverBytes, String extension, String sourcePath) {
        UUID volumeId = volume.getId();
        if (!imageRepository.findByBookEntityId(volumeId).isEmpty()) {
            return;
        }
        DirectoryEntity cacheDir = cacheDirectoryResolver.forThisNodeIfAny().orElse(null);
        if (cacheDir == null) {
            return;
        }

        String storedPath;
        try {
            storedPath = cacheDirectoryResolver.storeFor(cacheDir).write(
                    "book-covers/" + volumeId + "/cover." + extension, coverBytes, "png".equals(extension) ? "image/png" : "image/jpeg");
        } catch (IOException e) {
            log.warn("Could not write comic cover for {}: {}", sourcePath, e.getMessage());
            return;
        }

        messageSender.sendImageFound(ImageFoundData.builder()
                .eventType(EventType.IMAGE_FOUND)
                .directoryEntityId(cacheDir.getId())
                .path(storedPath)
                .imageType(ImageType.COVER)
                .sourceUri(SourceUris.of(sourcePath))
                .bookEntityId(volumeId)
                .build(), cacheDir.getName());
    }

    private static String extensionOf(String entryName) {
        int dot = entryName.lastIndexOf('.');
        String ext = dot >= 0 ? entryName.substring(dot + 1).toLowerCase() : "jpg";
        return ext.equals("jpeg") ? "jpg" : ext;
    }
}
