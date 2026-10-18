package app.ister.disk.upload;

import app.ister.core.entity.DirectoryEntity;
import app.ister.core.entity.ImageEntity;
import app.ister.core.entity.MediaFileEntity;
import app.ister.core.entity.MediaFileEpisodeEntity;
import app.ister.core.enums.EventType;
import app.ister.core.enums.LibraryType;
import app.ister.core.eventdata.AudioFileFoundData;
import app.ister.core.eventdata.ComicFileFoundData;
import app.ister.core.eventdata.EpubFileFoundData;
import app.ister.core.eventdata.FileScanRequestedData;
import app.ister.core.eventdata.ImageFoundData;
import app.ister.core.eventdata.MediaFileFoundData;
import app.ister.core.repository.ImageRepository;
import app.ister.core.repository.MediaFileEpisodeRepository;
import app.ister.core.repository.MediaFileRepository;
import app.ister.core.service.MessageSender;
import app.ister.core.storage.LocalCopy;
import app.ister.core.storage.TmpStoreProvider;
import app.ister.core.utils.AfterCommitPublisher;
import app.ister.disk.scanner.GamePathObject;
import app.ister.disk.scanner.Scanners;
import app.ister.disk.scanner.enums.FileType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * Hands a file that just landed in a library to the pipeline that a scan would have fed it into.
 *
 * <p>A new file is one {@code FILE_SCAN_REQUESTED}: exactly what the scan walk publishes, so
 * everything downstream is the normal path. A REPLACED file cannot go that way: the scanners see a
 * row for the path and conclude there is nothing to do. Its analysis event is published directly
 * instead, after throwing away what was derived from the old bytes.
 */
@Slf4j
@Component
public class UploadedFilePublisher {

    private final Scanners scanners;
    private final MessageSender messageSender;
    private final MediaFileRepository mediaFileRepository;
    private final MediaFileEpisodeRepository mediaFileEpisodeRepository;
    private final ImageRepository imageRepository;
    private final LocalCopy localCopy;
    private final TmpStoreProvider tmpStoreProvider;
    private final String tmpDir;

    public UploadedFilePublisher(Scanners scanners, MessageSender messageSender, MediaFileRepository mediaFileRepository,
                                 MediaFileEpisodeRepository mediaFileEpisodeRepository,
                                 ImageRepository imageRepository, LocalCopy localCopy,
                                 TmpStoreProvider tmpStoreProvider,
                                 @Value("${app.ister.server.tmp-dir}") String tmpDir) {
        this.scanners = scanners;
        this.messageSender = messageSender;
        this.mediaFileRepository = mediaFileRepository;
        this.mediaFileEpisodeRepository = mediaFileEpisodeRepository;
        this.imageRepository = imageRepository;
        this.localCopy = localCopy;
        this.tmpStoreProvider = tmpStoreProvider;
        this.tmpDir = tmpDir;
    }

    /**
     * Call inside the transaction that marks the upload file completed; the events go out after its
     * commit. Returns false for a file whose event must wait for the whole session: the marker of a
     * folder-form game sizes its tree when scanned, so it is published by
     * {@link #publishGameMarkers} once every file of the session has landed. The other files of
     * such a tree need no event at all (no scanner takes them).
     */
    public boolean published(DirectoryEntity directory, String path, long size) {
        if (Scanners.libraryTypeOf(directory) == LibraryType.GAME) {
            FileType fileType = new GamePathObject(directory.getPath(), path, false).getFileType();
            if (fileType == FileType.GAME_MARKER) {
                return false;
            }
            if (fileType == FileType.GAME_TREE_FILE || scanners.analyzableBy(directory, path, true, size).isEmpty()) {
                return true;
            }
        }
        Optional<MediaFileEntity> mediaFile = mediaFileRepository.findByDirectoryEntityAndPath(directory, path);
        if (mediaFile.isPresent()) {
            replaced(directory, mediaFile.get(), size);
            return true;
        }
        Optional<ImageEntity> image = imageRepository.findByDirectoryEntityAndPath(directory, path);
        if (image.isPresent()) {
            ImageFoundData data = ImageFoundData.fromImageEntity(image.get());
            AfterCommitPublisher.publishAfterCommit(() -> messageSender.sendImageFound(data, directory.getName()));
            return true;
        }
        // New to the library (or a replaced nfo/subtitle, whose handlers re-read on every event).
        FileScanRequestedData data = FileScanRequestedData.builder()
                .eventType(EventType.FILE_SCAN_REQUESTED)
                .path(path)
                .regularFile(true)
                .size(size)
                .lastModified(java.time.Instant.now())
                .directoryEntityUUID(directory.getId())
                .build();
        AfterCommitPublisher.publishAfterCommit(() -> messageSender.sendFileScanRequested(data, directory.getName()));
        return true;
    }

    /** The deferred game markers of a finished session: one scan request each, after commit. */
    public void publishGameMarkers(DirectoryEntity directory, List<String> markerPaths) {
        for (String path : markerPaths) {
            FileScanRequestedData data = FileScanRequestedData.builder()
                    .eventType(EventType.FILE_SCAN_REQUESTED)
                    .path(path)
                    .regularFile(true)
                    .size(0)
                    .lastModified(java.time.Instant.now())
                    .directoryEntityUUID(directory.getId())
                    .build();
            AfterCommitPublisher.publishAfterCommit(() -> messageSender.sendFileScanRequested(data, directory.getName()));
        }
    }

    /** Whether a completed upload file is a game marker whose scan request waits for the session end. */
    public static boolean isDeferredGameMarker(DirectoryEntity directory, String path) {
        return Scanners.libraryTypeOf(directory) == LibraryType.GAME
                && new GamePathObject(directory.getPath(), path, false).getFileType() == FileType.GAME_MARKER;
    }

    private void replaced(DirectoryEntity directory, MediaFileEntity mediaFile, long size) {
        log.info("Re-analyzing replaced file {}", mediaFile.getPath());
        mediaFile.setSize(size);
        mediaFileRepository.save(mediaFile);
        dropDerivedState(mediaFile);

        String directoryName = directory.getName();
        Runnable publish = analysisEvent(directory, mediaFile, directoryName);
        AfterCommitPublisher.publishAfterCommit(publish);
    }

    private Runnable analysisEvent(DirectoryEntity directory, MediaFileEntity mediaFile, String directoryName) {
        String path = mediaFile.getPath();
        if (mediaFile.getTrackEntity() != null || mediaFile.getChapterEntity() != null) {
            AudioFileFoundData data = AudioFileFoundData.fromMediaFileEntity(mediaFile);
            return () -> messageSender.sendAudioFileFound(data, directoryName);
        }
        if (mediaFile.getBookEntity() != null) {
            UUID bookId = mediaFile.getBookEntity().getId();
            boolean comicArchive = directory.getLibraryEntity().getLibraryType() == LibraryType.COMIC
                    && !path.toLowerCase().endsWith(".epub");
            if (comicArchive) {
                ComicFileFoundData data = ComicFileFoundData.builder().eventType(EventType.COMIC_FILE_FOUND)
                        .directoryEntityUUID(directory.getId()).bookEntityUUID(bookId)
                        .mediaFileEntityUUID(mediaFile.getId()).path(path).build();
                return () -> messageSender.sendComicFileFound(data, directoryName);
            }
            EpubFileFoundData data = EpubFileFoundData.builder().eventType(EventType.EPUB_FILE_FOUND)
                    .directoryEntityUUID(directory.getId()).bookEntityUUID(bookId)
                    .mediaFileEntityUUID(mediaFile.getId()).path(path).build();
            return () -> messageSender.sendEpubFileFound(data, directoryName);
        }
        List<UUID> episodeIds = mediaFileEpisodeRepository.findByMediaFileEntityIdOrderByPartNumber(mediaFile.getId())
                .stream().map(MediaFileEpisodeEntity::getEpisodeEntityId).toList();
        MediaFileFoundData data = MediaFileFoundData.builder().eventType(EventType.MEDIA_FILE_FOUND)
                .directoryEntityUUID(directory.getId())
                .episodeEntityUUID(mediaFile.getEpisodeEntity() == null ? null : mediaFile.getEpisodeEntity().getId())
                .episodeEntityUUIDs(episodeIds.isEmpty() ? null : episodeIds)
                .movieEntityUUID(mediaFile.getMovieEntity() == null ? null : mediaFile.getMovieEntity().getId())
                .path(path).build();
        return () -> messageSender.sendMediaFileFound(data, directoryName);
    }

    /**
     * HLS segments and the cached S3 copy were cut from the old bytes; served next to the new
     * stream ids they would play the old file, or nothing. Best effort: whatever survives here is
     * also caught by the daily tmp cleanup.
     */
    private void dropDerivedState(MediaFileEntity mediaFile) {
        localCopy.evict(mediaFile.getPath());
        try {
            deleteRecursively(Path.of(tmpDir, mediaFile.getId().toString()));
            if (tmpStoreProvider.shared().isPresent()) {
                tmpStoreProvider.shared().get().deleteAll(mediaFile.getId());
            }
        } catch (IOException | RuntimeException e) {
            log.warn("Could not drop the transcode tmp of replaced file {}: {}", mediaFile.getPath(), e.getMessage());
        }
    }

    private static void deleteRecursively(Path dir) throws IOException {
        if (!Files.isDirectory(dir)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(dir)) {
            for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(p);
            }
        }
    }
}
