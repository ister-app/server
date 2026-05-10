package app.ister.disk.events.audiofilefound;

import app.ister.core.entity.ArtistEntity;
import app.ister.core.entity.MediaFileEntity;
import app.ister.core.entity.MetadataEntity;
import app.ister.core.entity.TrackEntity;
import app.ister.core.enums.EventType;
import app.ister.core.eventdata.AudioFileFoundData;
import app.ister.core.repository.ArtistRepository;
import app.ister.core.repository.DirectoryRepository;
import app.ister.core.repository.MediaFileRepository;
import app.ister.core.repository.MediaFileStreamRepository;
import app.ister.core.repository.MetadataRepository;
import app.ister.core.repository.TrackRepository;
import app.ister.core.service.ScannerHelperService;
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

import java.io.IOException;
import java.nio.file.Files;
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
    private final DirectoryRepository directoryRepository;
    private final MediaFileRepository mediaFileRepository;
    private final MediaFileStreamRepository mediaFileStreamRepository;
    private final MetadataRepository metadataRepository;
    private final TrackRepository trackRepository;
    private final ArtistRepository artistRepository;
    private final ScannerHelperService scannerHelperService;
    private final MediaFileFoundGetDuration mediaFileFoundGetDuration;
    private final MediaFileFoundCheckForStreams mediaFileFoundCheckForStreams;
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
                                ArtistRepository artistRepository,
                                ScannerHelperService scannerHelperService,
                                MediaFileFoundGetDuration mediaFileFoundGetDuration,
                                MediaFileFoundCheckForStreams mediaFileFoundCheckForStreams,
                                Jaffree jaffree) {
        this.directoryRepository = directoryRepository;
        this.mediaFileRepository = mediaFileRepository;
        this.mediaFileStreamRepository = mediaFileStreamRepository;
        this.metadataRepository = metadataRepository;
        this.trackRepository = trackRepository;
        this.artistRepository = artistRepository;
        this.scannerHelperService = scannerHelperService;
        this.mediaFileFoundGetDuration = mediaFileFoundGetDuration;
        this.mediaFileFoundCheckForStreams = mediaFileFoundCheckForStreams;
        this.jaffree = jaffree;
    }

    @Override
    public EventType handles() {
        return EventType.AUDIO_FILE_FOUND;
    }

    @RabbitListener(queues = "#{@diskQueueNamingConfig.getAudioFileFoundQueues()}")
    @Override
    public void listener(AudioFileFoundData audioFileFoundData) {
        Handle.super.listener(audioFileFoundData);
    }

    @Override
    public Boolean handle(AudioFileFoundData messageData) {
        var directoryEntity = directoryRepository.findById(messageData.getDirectoryEntityUUID()).orElseThrow();
        Optional<MediaFileEntity> mediaFile = mediaFileRepository.findByDirectoryEntityAndPath(directoryEntity, messageData.getPath());
        if (mediaFile.isEmpty()) {
            log.warn("AudioFileFound: media file entity not found for path={} directoryId={} — skipping analysis",
                    messageData.getPath(), messageData.getDirectoryEntityUUID());
            return true;
        }
        mediaFile.ifPresent(entity -> {
            entity.setDurationInMilliseconds(mediaFileFoundGetDuration.getDuration(entity.getPath()));
            mediaFileRepository.save(entity);

            mediaFileStreamRepository.deleteAllByMediaFileEntityId(entity.getId());
            mediaFileStreamRepository.flush();
            var streams = mediaFileFoundCheckForStreams.checkForStreams(entity, dirOfFFmpeg);
            mediaFileStreamRepository.saveAll(streams);

            saveTrackMetadataFromTags(messageData.getTrackEntityUUID(), entity);

            // Invalidate any HLS cache that was generated before streams were stored.
            deleteHlsCache(entity.getId());
        });
        return true;
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

        correctArtistNameFromTags(track, format);
        String title = extractTitle(format, mediaFile.getPath());
        if (title == null) return;
        var existingMetadata = track.getMetadataEntities();
        if (existingMetadata != null) {
            metadataRepository.deleteAll(existingMetadata);
        }
        metadataRepository.save(MetadataEntity.builder()
                .title(title)
                .description(extractDescription(format))
                .trackEntity(track)
                .sourceUri("file://" + mediaFile.getPath())
                .build());
    }

    private UUID correctTrackNumberFromTags(MediaFileEntity mediaFile, TrackEntity currentTrack, Format format) {
        if (format == null) return currentTrack.getId();
        int tagTrackNumber = parseTrackNumberFromTag(format);
        if (tagTrackNumber <= 0) return currentTrack.getId();
        int tagDiscNumber = parseDiscNumberFromTag(format);
        if (tagDiscNumber <= 0) tagDiscNumber = currentTrack.getDiscNumber();
        if (currentTrack.getNumber() == tagTrackNumber && currentTrack.getDiscNumber() == tagDiscNumber) {
            return currentTrack.getId();
        }
        log.info("Correcting track number for {}: {}/{} → {}/{}",
                mediaFile.getPath(), currentTrack.getDiscNumber(), currentTrack.getNumber(),
                tagDiscNumber, tagTrackNumber);
        TrackEntity correctTrack = scannerHelperService.getOrCreateTrack(
                currentTrack.getArtistEntity(), currentTrack.getAlbumEntity(), tagTrackNumber, tagDiscNumber);
        mediaFile.setTrackEntity(correctTrack);
        mediaFileRepository.save(mediaFile);
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
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private void correctArtistNameFromTags(TrackEntity track, Format format) {
        if (format == null) return;
        String albumArtist = format.getTag("album_artist");
        if (albumArtist == null) albumArtist = format.getTag("ALBUM_ARTIST");
        if (albumArtist == null || albumArtist.isBlank()) return;
        ArtistEntity artist = track.getArtistEntity();
        if (albumArtist.equals(artist.getName())) return;
        boolean exists = artistRepository
                .findByLibraryEntityAndName(track.getAlbumEntity().getLibraryEntity(), albumArtist)
                .isPresent();
        if (!exists) {
            artist.setName(albumArtist);
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

    private static String titleFromFilename(String path) {
        String filename = Paths.get(path).getFileName().toString();
        // Strip extension
        int dot = filename.lastIndexOf('.');
        if (dot > 0) filename = filename.substring(0, dot);
        // Strip leading track number "01 - " or "1 - "
        filename = filename.replaceFirst("^\\d+\\s*-\\s*", "");
        return filename.isBlank() ? null : filename.strip();
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
