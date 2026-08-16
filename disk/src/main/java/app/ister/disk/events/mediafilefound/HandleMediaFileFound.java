package app.ister.disk.events.mediafilefound;

import app.ister.core.entity.*;
import app.ister.core.enums.DirectoryType;
import app.ister.core.enums.StreamCodecType;
import app.ister.core.enums.EventType;
import app.ister.core.enums.ImageType;
import app.ister.core.eventdata.DetectSegmentsData;
import app.ister.core.eventdata.ImageFoundData;
import app.ister.core.eventdata.MediaFileFoundData;
import app.ister.core.repository.*;
import app.ister.core.service.MessageSender;
import app.ister.core.service.NodeService;
import app.ister.core.status.ActivityContext;
import app.ister.core.utils.AfterCommitPublisher;
import app.ister.core.Handle;
import com.github.kokorin.jaffree.process.JaffreeAbnormalExitException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

@Slf4j
@Service
@Transactional
public class HandleMediaFileFound implements Handle<MediaFileFoundData> {
    private final NodeService nodeService;
    private final DirectoryRepository directoryRepository;
    private final MediaFileRepository mediaFileRepository;
    private final EpisodeRepository episodeRepository;
    private final MovieRepository movieRepository;
    private final MediaFileStreamRepository mediaFileStreamRepository;
    private final ImageRepository imageRepository;
    private final MediaFileEpisodeRepository mediaFileEpisodeRepository;

    private final MediaFileFoundCheckForStreams mediaFileFoundCheckForStreams;
    private final MediaFileFoundCreateBackground mediaFileFoundCreateBackground;
    private final MediaFileFoundGetDuration mediaFileFoundGetDuration;
    private final MediaFileFoundExtractSubtitles mediaFileFoundExtractSubtitles;
    private final MediaFileFoundEpisodeBoundaries mediaFileFoundEpisodeBoundaries;
    private final MediaFileFoundDetectCrop mediaFileFoundDetectCrop;
    private final MessageSender messageSender;

    @Value("${app.ister.server.ffmpeg-dir}")
    private String dirOfFFmpeg;

    public HandleMediaFileFound(NodeService nodeService,
                                DirectoryRepository directoryRepository,
                                MediaFileRepository mediaFileRepository,
                                EpisodeRepository episodeRepository,
                                MovieRepository movieRepository,
                                MediaFileStreamRepository mediaFileStreamRepository,
                                ImageRepository imageRepository,
                                MediaFileEpisodeRepository mediaFileEpisodeRepository,
                                MediaFileFoundCheckForStreams mediaFileFoundCheckForStreams,
                                MediaFileFoundCreateBackground mediaFileFoundCreateBackground,
                                MediaFileFoundGetDuration mediaFileFoundGetDuration,
                                MediaFileFoundExtractSubtitles mediaFileFoundExtractSubtitles,
                                MediaFileFoundEpisodeBoundaries mediaFileFoundEpisodeBoundaries,
                                MediaFileFoundDetectCrop mediaFileFoundDetectCrop,
                                MessageSender messageSender) {
        this.nodeService = nodeService;
        this.directoryRepository = directoryRepository;
        this.mediaFileRepository = mediaFileRepository;
        this.episodeRepository = episodeRepository;
        this.movieRepository = movieRepository;
        this.mediaFileStreamRepository = mediaFileStreamRepository;
        this.imageRepository = imageRepository;
        this.mediaFileEpisodeRepository = mediaFileEpisodeRepository;
        this.mediaFileFoundCheckForStreams = mediaFileFoundCheckForStreams;
        this.mediaFileFoundCreateBackground = mediaFileFoundCreateBackground;
        this.mediaFileFoundGetDuration = mediaFileFoundGetDuration;
        this.mediaFileFoundExtractSubtitles = mediaFileFoundExtractSubtitles;
        this.mediaFileFoundEpisodeBoundaries = mediaFileFoundEpisodeBoundaries;
        this.mediaFileFoundDetectCrop = mediaFileFoundDetectCrop;
        this.messageSender = messageSender;
    }

    private static String getPathString(DirectoryEntity cacheDisk, Optional<EpisodeEntity> episodeEntity, Optional<MovieEntity> movieEntity) {
        String id = null;
        if (episodeEntity.isPresent()) {
            id = episodeEntity.get().getId().toString();
        } else if (movieEntity.isPresent()) {
            id = movieEntity.get().getId().toString();
        }
        return cacheDisk.getPath() + id + ".jpg";
    }

    @Override
    public EventType handles() {
        return EventType.MEDIA_FILE_FOUND;
    }

    @RabbitListener(queues = "#{@diskQueueNamingConfig.getMediaFileFoundQueues()}")
    @Override
    public void listener(app.ister.core.eventdata.MediaFileFoundData mediaFileFoundData) {
        Handle.super.listener(mediaFileFoundData);
    }

    /**
     * When the scanner find the media file it saves the data in the database.
     * The scanner is not analyzing the media file, because it can take a bit longer.
     * So this handler will analyze the media file.
     * - The duration of the file.
     * - And the containing streams (video, audio and subtitles streams).
     * - And will create a background image.
     */
    @Override
    public void handle(app.ister.core.eventdata.MediaFileFoundData mediaFileFoundData) {
        ActivityContext.subject(Path.of(mediaFileFoundData.getPath()).getFileName().toString());
        DirectoryEntity directoryEntity = directoryRepository.findById(mediaFileFoundData.getDirectoryEntityUUID())
                .orElseThrow(() -> new IllegalStateException("Directory not found: " + mediaFileFoundData.getDirectoryEntityUUID()));
        Optional<EpisodeEntity> episodeEntity = mediaFileFoundData.getEpisodeEntityUUID() != null ? episodeRepository.findById(mediaFileFoundData.getEpisodeEntityUUID()) : Optional.empty();
        Optional<MovieEntity> movieEntity = mediaFileFoundData.getMovieEntityUUID() != null ? movieRepository.findById(mediaFileFoundData.getMovieEntityUUID()) : Optional.empty();
        var mediaFile = checkMediaFile(directoryEntity, mediaFileFoundData.getPath());
        mediaFile.ifPresent(mediaFileEntity -> {
            var parts = updateEpisodeBoundaries(mediaFileEntity);
            ActivityContext.step("still");
            if (parts.size() >= 2) {
                // Multi-episode file: every contained episode gets its own background still,
                // taken at the midpoint of its own slice of the file.
                for (MediaFileEpisodeEntity part : parts) {
                    episodeRepository.findById(part.getEpisodeEntityId()).ifPresent(partEpisode ->
                            createBackgroundImage(Optional.of(partEpisode), Optional.empty(), mediaFileFoundData.getPath(),
                                    part.getStartInMilliseconds() + part.getDurationInMilliseconds() / 2));
                }
            } else {
                createBackgroundImage(episodeEntity, movieEntity, mediaFileFoundData.getPath(), mediaFileEntity.getDurationInMilliseconds() / 2);
            }
            // Intro/outro detection is season-wide (it compares sibling episodes), so it runs as
            // its own event once this file's analysis is committed — after commit, or the handler
            // would still see the old duration/detector version. Idempotent on the handler side,
            // so firing once per analyzed episode is fine.
            episodeEntity.ifPresent(episode -> {
                DetectSegmentsData detectSegmentsData = DetectSegmentsData.builder()
                        .eventType(EventType.DETECT_SEGMENTS)
                        .seasonEntityUUID(episode.getSeasonEntity().getId())
                        .directoryEntityUUID(directoryEntity.getId())
                        .build();
                AfterCommitPublisher.publishAfterCommit(() ->
                        messageSender.sendDetectSegments(detectSegmentsData, directoryEntity.getName()));
            });
        });
    }

    /**
     * For a multi-episode file (s04e06-e07.mkv): compute where each episode starts, preferring the
     * MKV chapter markers, and store the slices on the link rows. Idempotent on re-analysis.
     */
    private List<MediaFileEpisodeEntity> updateEpisodeBoundaries(MediaFileEntity mediaFileEntity) {
        List<MediaFileEpisodeEntity> parts = mediaFileEpisodeRepository.findByMediaFileEntityIdOrderByPartNumber(mediaFileEntity.getId());
        long duration = mediaFileEntity.getDurationInMilliseconds();
        if (parts.size() < 2 || duration <= 0) {
            return parts;
        }
        ActivityContext.step("boundaries");
        List<Long> starts = mediaFileFoundEpisodeBoundaries.boundaryStarts(mediaFileEntity.getPath(), dirOfFFmpeg, duration, parts.size());
        for (int i = 0; i < parts.size(); i++) {
            long end = i + 1 < parts.size() ? starts.get(i + 1) : duration;
            parts.get(i).setStartInMilliseconds(starts.get(i));
            parts.get(i).setDurationInMilliseconds(end - starts.get(i));
        }
        mediaFileEpisodeRepository.saveAll(parts);
        return parts;
    }

    private Optional<MediaFileEntity> checkMediaFile(DirectoryEntity directoryEntity, String file) {
        Optional<MediaFileEntity> mediaFile = mediaFileRepository.findByDirectoryEntityAndPath(directoryEntity, file);
        mediaFile.ifPresent(mediaFileEntity -> {
            // Clear existing stream metadata so re-analysis on retry doesn't hit duplicate-key errors.
            mediaFileStreamRepository.deleteAllByMediaFileEntityId(mediaFileEntity.getId());
            mediaFileStreamRepository.flush();

            // Analyze media file streams; duration is derived from the same ffprobe call.
            ActivityContext.step("probe");
            var checkResult = mediaFileFoundCheckForStreams.checkForStreams(mediaFileEntity, dirOfFFmpeg);
            long duration = checkResult.durationInMilliseconds() > 0
                    ? checkResult.durationInMilliseconds()
                    : mediaFileFoundGetDuration.getDurationByDecodingFile(mediaFileEntity.getPath());
            mediaFileEntity.setDurationInMilliseconds(duration);
            mediaFileRepository.save(mediaFileEntity);

            var streams = checkResult.streams();
            ActivityContext.step("crop");
            detectAndSetCrop(mediaFileEntity, streams, duration);
            mediaFileStreamRepository.saveAll(streams);

            // Extract embedded subtitles to SRT files in the cache directory.
            ActivityContext.step("subtitles");
            NodeEntity cacheNode = nodeService.getOrCreateNodeEntityForThisNode();
            DirectoryEntity cacheDisk = directoryRepository.findByDirectoryTypeAndNodeEntity(DirectoryType.CACHE, cacheNode).stream().findFirst().orElseThrow();
            mediaFileStreamRepository.saveAll(mediaFileFoundExtractSubtitles.extractSubtitles(mediaFileEntity, streams, cacheDisk, dirOfFFmpeg));
        });
        return mediaFile;
    }

    /**
     * Detects baked-in black bars on the primary video stream and stores the
     * crop rect on the stream row (full frame = detected, no bars). Failure
     * leaves the columns null so the scanner's backfill retries later.
     */
    private void detectAndSetCrop(MediaFileEntity mediaFileEntity, List<MediaFileStreamEntity> streams, long duration) {
        streams.stream()
                .filter(s -> s.getCodecType() == StreamCodecType.VIDEO && s.getWidth() > 0 && s.getHeight() > 0)
                .findFirst()
                .ifPresent(video -> mediaFileFoundDetectCrop
                        .detectCrop(Path.of(mediaFileEntity.getPath()), dirOfFFmpeg, duration,
                                video.getWidth(), video.getHeight())
                        .ifPresent(crop -> {
                            video.setCropX(crop.x());
                            video.setCropY(crop.y());
                            video.setCropWidth(crop.w());
                            video.setCropHeight(crop.h());
                        }));
    }

    /**
     * Check if the given {@link EpisodeEntity} or {@link MovieEntity} has image entities if not:
     * Create background image for media file and save a reference to it in the database.
     */
    private void createBackgroundImage(Optional<EpisodeEntity> episodeEntity, Optional<MovieEntity> movieEntity, String mediaFilePath, long stillAtMilliseconds) {
        // Query the image repository directly instead of navigating the entities' LAZY
        // imagesEntities collection: this handler runs on a RabbitMQ listener thread with no
        // open-session-in-view, so lazy navigation would throw LazyInitializationException.
        boolean episodeNeedsBackground = episodeEntity.isPresent() && !imageRepository.existsByEpisodeEntityId(episodeEntity.get().getId());
        boolean movieNeedsBackground = movieEntity.isPresent() && !imageRepository.existsByMovieEntityId(movieEntity.get().getId());
        if (episodeNeedsBackground || movieNeedsBackground) {
            NodeEntity nodeEntity = nodeService.getOrCreateNodeEntityForThisNode();
            DirectoryEntity cacheDisk = directoryRepository.findByDirectoryTypeAndNodeEntity(DirectoryType.CACHE, nodeEntity).stream().findFirst().orElseThrow();
            String toPath = getPathString(cacheDisk, episodeEntity, movieEntity);
            try {
                mediaFileFoundCreateBackground.createBackground(Path.of(toPath), Path.of(mediaFilePath), dirOfFFmpeg, stillAtMilliseconds);
            } catch (JaffreeAbnormalExitException e) {
                log.error("Failed to create background image for {}: {}", mediaFilePath, e.getMessage());
                return;
            }

            ImageEntity imageEntity = ImageEntity.builder()
                    .directoryEntityId(cacheDisk.getId())
                    .path(toPath)
                    .sourceUri("file://" + mediaFilePath)
                    .type(ImageType.BACKGROUND)
                    .episodeEntityId(episodeEntity.map(EpisodeEntity::getId).orElse(null))
                    .movieEntityId(movieEntity.map(MovieEntity::getId).orElse(null))
                    .build();
            messageSender.sendImageFound(ImageFoundData.fromImageEntity(imageEntity), cacheDisk.getName());
        }
    }
}
