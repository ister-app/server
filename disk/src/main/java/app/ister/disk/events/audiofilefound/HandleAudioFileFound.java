package app.ister.disk.events.audiofilefound;

import app.ister.core.entity.AlbumEntity;
import app.ister.core.entity.ChapterEntity;
import app.ister.core.entity.DirectoryEntity;
import app.ister.core.entity.MediaFileEntity;
import app.ister.core.entity.MetadataEntity;
import app.ister.core.entity.TrackEntity;
import app.ister.core.enums.DirectoryType;
import app.ister.core.enums.EventType;
import app.ister.core.enums.ImageType;
import app.ister.core.enums.SearchEntityType;
import app.ister.core.enums.SubtitleFormat;
import app.ister.core.eventdata.AudioFileFoundData;
import app.ister.core.eventdata.ImageFoundData;
import app.ister.core.eventdata.TranscodeRequestedData;
import app.ister.core.repository.AlbumRepository;
import app.ister.core.repository.ChapterRepository;
import app.ister.core.repository.DirectoryRepository;
import app.ister.core.repository.ImageRepository;
import app.ister.core.repository.MediaFileRepository;
import app.ister.core.repository.MediaFileStreamRepository;
import app.ister.core.repository.MetadataRepository;
import app.ister.core.repository.TrackRepository;
import app.ister.core.service.MessageSender;
import app.ister.core.service.ScannerHelperService;
import app.ister.core.service.ServerEventService;
import app.ister.core.Handle;
import app.ister.core.utils.Jaffree;
import app.ister.disk.events.mediafilefound.MediaFileFoundCheckForStreams;
import app.ister.disk.events.mediafilefound.MediaFileFoundGetDuration;
import com.github.kokorin.jaffree.ffprobe.Format;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.io.IOException;
import java.nio.file.Files;
import java.time.LocalDate;
import java.time.Month;
import java.util.List;
import java.util.Objects;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Comparator;
import java.util.Optional;
import java.util.UUID;

@Slf4j
@Service
@Transactional
public class HandleAudioFileFound implements Handle<AudioFileFoundData> {
    private static final String FILE_URI_SCHEME = "file://";

    private final DirectoryRepository directoryRepository;
    private final MediaFileRepository mediaFileRepository;
    private final MediaFileStreamRepository mediaFileStreamRepository;
    private final MetadataRepository metadataRepository;
    private final TrackRepository trackRepository;
    private final AlbumRepository albumRepository;
    private final ChapterRepository chapterRepository;
    private final ImageRepository imageRepository;
    private final ScannerHelperService scannerHelperService;
    private final MediaFileFoundGetDuration mediaFileFoundGetDuration;
    private final MediaFileFoundCheckForStreams mediaFileFoundCheckForStreams;
    private final AudioFileFoundExtractCoverArt audioFileFoundExtractCoverArt;
    private final MessageSender messageSender;
    private final ServerEventService serverEventService;
    private final Jaffree jaffree;

    @Value("${app.ister.server.ffmpeg-dir}")
    private String dirOfFFmpeg;

    @Value("${app.ister.server.tmp-dir}")
    private String tmpDir;

    public HandleAudioFileFound(DirectoryRepository directoryRepository,
                                MediaFileRepository mediaFileRepository,
                                MediaFileStreamRepository mediaFileStreamRepository,
                                MetadataRepository metadataRepository,
                                TrackRepository trackRepository,
                                AlbumRepository albumRepository,
                                ChapterRepository chapterRepository,
                                ImageRepository imageRepository,
                                ScannerHelperService scannerHelperService,
                                MediaFileFoundGetDuration mediaFileFoundGetDuration,
                                MediaFileFoundCheckForStreams mediaFileFoundCheckForStreams,
                                AudioFileFoundExtractCoverArt audioFileFoundExtractCoverArt,
                                MessageSender messageSender,
                                ServerEventService serverEventService,
                                Jaffree jaffree) {
        this.directoryRepository = directoryRepository;
        this.mediaFileRepository = mediaFileRepository;
        this.mediaFileStreamRepository = mediaFileStreamRepository;
        this.metadataRepository = metadataRepository;
        this.trackRepository = trackRepository;
        this.albumRepository = albumRepository;
        this.chapterRepository = chapterRepository;
        this.imageRepository = imageRepository;
        this.scannerHelperService = scannerHelperService;
        this.mediaFileFoundGetDuration = mediaFileFoundGetDuration;
        this.mediaFileFoundCheckForStreams = mediaFileFoundCheckForStreams;
        this.audioFileFoundExtractCoverArt = audioFileFoundExtractCoverArt;
        this.messageSender = messageSender;
        this.serverEventService = serverEventService;
        this.jaffree = jaffree;
    }

    @Override
    public EventType handles() {
        return EventType.AUDIO_FILE_FOUND;
    }

    @RabbitListener(queues = "#{@diskQueueNamingConfig.getAudioFileFoundQueues()}", concurrency = "10")
    @Override
    public void listener(AudioFileFoundData audioFileFoundData) {
        Handle.super.listener(audioFileFoundData);
    }

    @Override
    public void handle(AudioFileFoundData messageData) {
        var directoryEntity = directoryRepository.findById(messageData.getDirectoryEntityUUID()).orElseThrow();
        Optional<MediaFileEntity> mediaFile = mediaFileRepository.findByDirectoryEntityAndPathForUpdate(directoryEntity, messageData.getPath());
        if (mediaFile.isEmpty()) {
            log.warn("AudioFileFound: media file entity not found for path={} directoryId={} — skipping analysis",
                    messageData.getPath(), messageData.getDirectoryEntityUUID());
            return;
        }
        mediaFile.ifPresent(entity -> {
            UUID entityId = entity.getId();
            UUID directoryId = directoryEntity.getId();
            // native bulk DELETE; clearAutomatically=true evicts all session entities afterwards
            mediaFileStreamRepository.deleteAllByMediaFileEntityId(entityId);
            // reload detached entities with fresh session references
            MediaFileEntity freshEntity = mediaFileRepository.findById(entityId).orElseThrow();
            DirectoryEntity freshDirectory = directoryRepository.findById(directoryId).orElseThrow();
            var checkResult = mediaFileFoundCheckForStreams.checkForStreams(freshEntity, dirOfFFmpeg);
            long duration = checkResult.durationInMilliseconds() > 0
                    ? checkResult.durationInMilliseconds()
                    : mediaFileFoundGetDuration.getDurationByDecodingFile(freshEntity.getPath());
            freshEntity.setDurationInMilliseconds(duration);
            mediaFileRepository.save(freshEntity);
            checkResult.streams().forEach(s -> mediaFileStreamRepository.upsert(
                    new MediaFileStreamRepository.StreamUpsert(
                            s.getCodecName(), s.getCodecType().name(), s.getHeight(), s.getLanguage(),
                            freshEntity.getId(), s.getPath(), s.getStreamIndex(), s.getTitle(), s.getWidth())));

            saveTrackMetadataFromTags(messageData.getTrackEntityUUID(), freshEntity);
            saveChapterMetadataFromTags(messageData.getChapterEntityUUID(), freshEntity);
            extractEmbeddedCoverArt(freshDirectory, freshEntity, checkResult.hasAttachedPic());
            deleteHlsCache(freshEntity.getId());
            requestPlaylistPreGenerationAfterCommit(freshEntity.getId(), freshDirectory.getName());
        });
    }

    /**
     * Pre-generates the HLS playlists for this track so the first play is a cache hit
     * instead of a queue round-trip. preTranscode stays false: no FFmpeg passes are
     * started, only playlists are written (cheap: audio-only files skip all ffprobes).
     * Sent after commit so the transcoder sees the stream rows upserted above.
     */
    private void requestPlaylistPreGenerationAfterCommit(UUID mediaFileId, String directoryName) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    sendPlaylistPreGeneration(mediaFileId, directoryName);
                }
            });
        } else {
            sendPlaylistPreGeneration(mediaFileId, directoryName);
        }
    }

    private void sendPlaylistPreGeneration(UUID mediaFileId, String directoryName) {
        messageSender.sendTranscodeRequested(TranscodeRequestedData.builder()
                .eventType(EventType.TRANSCODE_REQUESTED)
                .mediaFileId(mediaFileId)
                .direct(true)
                .transcode(true)
                .subtitleFormat(SubtitleFormat.WEBVTT)
                .build(), directoryName);
    }

    private void saveTrackMetadataFromTags(UUID trackEntityUUID, MediaFileEntity mediaFile) {
        if (trackEntityUUID == null) return;
        Optional<TrackEntity> trackOpt = trackRepository.findById(trackEntityUUID);
        if (trackOpt.isEmpty()) return;

        TrackEntity track = trackOpt.get();
        var format = jaffree.getFFPROBE().setShowFormat(true).setInput(mediaFile.getPath()).execute().getFormat();

        UUID correctedTrackId = correctTrackNumberFromTags(mediaFile, track, format);
        if (!Objects.equals(correctedTrackId, track.getId())) {
            track = trackRepository.findById(correctedTrackId).orElse(track);
        }

        String title = extractTitle(format, mediaFile.getPath());
        if (title != null) {
            metadataRepository.deleteAll(metadataRepository.findByTrackEntityId(track.getId()));
            metadataRepository.save(MetadataEntity.builder()
                    .title(title)
                    .description(extractDescription(format))
                    .released(extractReleaseDate(format))
                    .genre(extractGenreTag(format))
                    .trackEntity(track)
                    .sourceUri(FILE_URI_SCHEME + mediaFile.getPath())
                    .build());
            serverEventService.createSearchIndexEvent(SearchEntityType.TRACK, track.getId());
        }

        saveAlbumMetadataFromTags(track, mediaFile, format);
    }

    /**
     * Audiobook chapters keep their path-derived number (the ordering is what matters); only the
     * chapter title is taken from the ID3 title tag, falling back to the filename.
     */
    private void saveChapterMetadataFromTags(UUID chapterEntityUUID, MediaFileEntity mediaFile) {
        if (chapterEntityUUID == null) return;
        Optional<ChapterEntity> chapterOpt = chapterRepository.findById(chapterEntityUUID);
        if (chapterOpt.isEmpty()) return;

        ChapterEntity chapter = chapterOpt.get();
        var format = jaffree.getFFPROBE().setShowFormat(true).setInput(mediaFile.getPath()).execute().getFormat();
        String title = extractTitle(format, mediaFile.getPath());
        if (title == null) return;

        metadataRepository.deleteAll(metadataRepository.findByChapterEntityId(chapter.getId()));
        metadataRepository.save(MetadataEntity.builder()
                .title(title)
                .chapterEntity(chapter)
                .sourceUri(FILE_URI_SCHEME + mediaFile.getPath())
                .build());
    }

    /**
     * Album name and release year identify the album ({@code AlbumEntity}'s unique key) and are derived
     * from the path by every scanner. Tag-derived values therefore land here, in metadata, and never on
     * the entity itself — correcting the entity would strand the cover and NFO scanners, which look the
     * album up by its path-derived name and year.
     */
    private void saveAlbumMetadataFromTags(TrackEntity track, MediaFileEntity mediaFile, Format format) {
        AlbumEntity album = track.getAlbumEntity();
        if (album == null) return;
        // Tracks of one album are consumed concurrently; without the lock two of them both see no
        // metadata and both insert a row.
        if (albumRepository.findByIdForUpdate(album.getId()).isEmpty()) return;
        if (!metadataRepository.findByAlbumEntityId(album.getId()).isEmpty()) return;

        String albumTitle = extractAlbumTitle(format);
        LocalDate released = extractReleaseDate(format);
        String genre = extractGenreTag(format);

        if (albumTitle == null && released == null && genre == null) return;

        metadataRepository.save(MetadataEntity.builder()
                .title(albumTitle)
                .released(released)
                .genre(genre)
                .albumEntity(album)
                .sourceUri(FILE_URI_SCHEME + mediaFile.getPath())
                .build());
        serverEventService.createSearchIndexEvent(SearchEntityType.ALBUM, album.getId());
    }

    private UUID correctTrackNumberFromTags(MediaFileEntity mediaFile, TrackEntity currentTrack, Format format) {
        int tagTrackNumber = format != null ? parseTrackNumberFromTag(format) : 0;

        if (tagTrackNumber <= 0 && currentTrack.getNumber() == 0) {
            return assignSequentialTrackNumber(mediaFile, currentTrack);
        }
        if (tagTrackNumber <= 0) return currentTrack.getId();

        int tagDiscNumber = parseDiscNumberFromTag(format);
        if (tagDiscNumber <= 0) tagDiscNumber = currentTrack.getDiscNumber();
        if (currentTrack.getNumber() == tagTrackNumber && currentTrack.getDiscNumber() == tagDiscNumber) {
            return currentTrack.getId();
        }
        log.info("Correcting track number for {}: {}/{} → {}/{}",
                mediaFile.getPath(), currentTrack.getDiscNumber(), currentTrack.getNumber(),
                tagDiscNumber, tagTrackNumber);
        return reassignTrackNumber(mediaFile, currentTrack, tagTrackNumber, tagDiscNumber);
    }

    private UUID assignSequentialTrackNumber(MediaFileEntity mediaFile, TrackEntity currentTrack) {
        UUID albumId = currentTrack.getAlbumEntity().getId();
        List<MediaFileEntity> albumFiles = mediaFileRepository.findByTrackEntity_AlbumEntityId(albumId);
        albumFiles.sort(Comparator.comparing(MediaFileEntity::getPath));
        int position = -1;
        for (int i = 0; i < albumFiles.size(); i++) {
            if (albumFiles.get(i).getId().equals(mediaFile.getId())) {
                position = i + 1;
                break;
            }
        }
        if (position <= 0) return currentTrack.getId();
        log.info("Assigning sequential track number {} for {}", position, mediaFile.getPath());
        return reassignTrackNumber(mediaFile, currentTrack, position, currentTrack.getDiscNumber());
    }

    private UUID reassignTrackNumber(MediaFileEntity mediaFile, TrackEntity currentTrack, int newNumber, int newDisc) {
        TrackEntity correctTrack = scannerHelperService.getOrCreateTrack(
                currentTrack.getPersonEntity(), currentTrack.getAlbumEntity(), newNumber, newDisc);
        mediaFile.setTrackEntity(correctTrack);
        mediaFileRepository.save(mediaFile);
        if (!mediaFileRepository.existsByTrackEntityId(currentTrack.getId())) {
            trackRepository.delete(currentTrack);
            serverEventService.createSearchDeleteEvent(SearchEntityType.TRACK, currentTrack.getId());
        }
        return correctTrack.getId();
    }

    private static int parseTrackNumberFromTag(Format format) {
        String tag = format.getTag("track");
        if (tag == null) tag = format.getTag("TRACK");
        if (tag == null) tag = format.getTag("tracknumber");
        if (tag == null) tag = format.getTag("TRACKNUMBER");
        return parseTagNumber(tag);
    }

    private static int parseDiscNumberFromTag(Format format) {
        String tag = format.getTag("disc");
        if (tag == null) tag = format.getTag("DISC");
        if (tag == null) tag = format.getTag("discnumber");
        if (tag == null) tag = format.getTag("DISCNUMBER");
        return parseTagNumber(tag);
    }

    private static int parseTagNumber(String tag) {
        if (tag == null || tag.isBlank()) return 0;
        // Handle "N/total" format
        int slash = tag.indexOf('/');
        String value = slash >= 0 ? tag.substring(0, slash).trim() : tag.trim();
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException _) {
            return 0;
        }
    }

    private String extractTitle(Format format, String path) {
        if (format != null) {
            String title = format.getTag("title");
            if (title == null) title = format.getTag("TITLE");
            if (title != null) return title;
        }
        return titleFromFilename(path);
    }

    private static String extractDescription(Format format) {
        if (format == null) return null;
        String desc = format.getTag("comment");
        return desc != null ? desc : format.getTag("COMMENT");
    }

    private static String extractAlbumTitle(Format format) {
        if (format == null) return null;
        String tag = format.getTag("album");
        if (tag == null) tag = format.getTag("ALBUM");
        return tag != null && !tag.isBlank() ? tag.strip() : null;
    }

    private static LocalDate extractReleaseDate(Format format) {
        if (format == null) return null;
        String tag = format.getTag("date");
        if (tag == null) tag = format.getTag("DATE");
        if (tag == null) tag = format.getTag("year");
        if (tag == null) tag = format.getTag("YEAR");
        if (tag == null || tag.isBlank()) return null;
        tag = tag.strip();
        try {
            return LocalDate.parse(tag);
        } catch (Exception _) {
            // Not a full ISO date; fall back to parsing just the year below.
        }
        try {
            String year = tag.length() >= 4 ? tag.substring(0, 4) : tag;
            return LocalDate.of(Integer.parseInt(year), Month.JANUARY, 1);
        } catch (Exception _) {
            // Tag holds no parseable year; give up and return null.
        }
        return null;
    }

    private static String extractGenreTag(Format format) {
        if (format == null) return null;
        String tag = format.getTag("genre");
        if (tag == null) tag = format.getTag("GENRE");
        return tag != null && !tag.isBlank() ? tag.strip() : null;
    }

    private static String titleFromFilename(String path) {
        String filename = Paths.get(path).getFileName().toString();
        // Strip extension
        int dot = filename.lastIndexOf('.');
        if (dot > 0) filename = filename.substring(0, dot);
        // Strip leading track number "01 - " or "1 - "
        filename = filename.replaceFirst("^\\d+\\s*-\\s*", "");
        return filename.isBlank() ? null : filename.strip();
    }

    private void extractEmbeddedCoverArt(DirectoryEntity libraryDir, MediaFileEntity mediaFile, boolean hasAttachedPic) {
        if (!hasAttachedPic) return;
        UUID albumId = null;
        UUID bookId = null;
        TrackEntity track = mediaFile.getTrackEntity();
        if (track != null && track.getAlbumEntity() != null) {
            albumId = track.getAlbumEntity().getId();
            if (!imageRepository.findByAlbumEntityId(albumId).isEmpty()) return;
        } else if (mediaFile.getChapterEntity() != null && mediaFile.getChapterEntity().getBookEntity() != null) {
            bookId = mediaFile.getChapterEntity().getBookEntity().getId();
            if (!imageRepository.findByBookEntityId(bookId).isEmpty()) return;
        } else {
            return;
        }

        List<DirectoryEntity> cacheDirs = directoryRepository
                .findByDirectoryTypeAndNodeEntity(DirectoryType.CACHE, libraryDir.getNodeEntity());
        if (cacheDirs.isEmpty()) return;
        DirectoryEntity cacheDir = cacheDirs.get(0);

        Path outputPath = albumId != null
                ? Paths.get(cacheDir.getPath(), "album-covers", albumId.toString(), "cover.jpg")
                : Paths.get(cacheDir.getPath(), "book-covers", bookId.toString(), "cover.jpg");
        try {
            audioFileFoundExtractCoverArt.extract(outputPath, mediaFile.getPath(), dirOfFFmpeg);
        } catch (Exception e) {
            log.warn("Failed to extract embedded cover art from {}: {}", mediaFile.getPath(), e.getMessage());
            return;
        }

        messageSender.sendImageFound(ImageFoundData.builder()
                .eventType(EventType.IMAGE_FOUND)
                .directoryEntityId(cacheDir.getId())
                .path(outputPath.toString())
                .imageType(ImageType.COVER)
                .sourceUri(FILE_URI_SCHEME + mediaFile.getPath())
                .albumEntityId(albumId)
                .bookEntityId(bookId)
                .build(), cacheDir.getName());
    }

    private void deleteHlsCache(UUID mediaFileId) {
        if (mediaFileId == null) return;
        Path dir = Paths.get(tmpDir, mediaFileId.toString());
        if (!Files.exists(dir)) return;
        try (var walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder())
                    .forEach(p -> {
                        try {
                            Files.deleteIfExists(p);
                        } catch (IOException e) {
                            log.warn("Could not delete HLS cache file {}: {}", p, e.getMessage());
                        }
                    });
        } catch (IOException e) {
            log.warn("Could not delete HLS cache for {}: {}", mediaFileId, e.getMessage());
        }
    }
}
