package app.ister.disk.events.mediafilefound;

import app.ister.core.entity.*;
import app.ister.core.enums.StreamCodecType;
import app.ister.core.enums.EventType;
import app.ister.core.enums.ImageType;
import app.ister.core.eventdata.DetectSegmentsData;
import app.ister.core.eventdata.ImageFoundData;
import app.ister.core.eventdata.MediaFileFoundData;
import app.ister.core.eventdata.SubtitleExtractRequestedData;
import app.ister.disk.events.subtitleextract.SubtitleExtractor;
import app.ister.core.repository.*;
import app.ister.core.node.MediaFileInputResolver;
import app.ister.core.service.MessageSender;
import app.ister.core.storage.CacheDirectoryResolver;
import app.ister.core.storage.CacheStore;
import app.ister.core.storage.SourceUris;
import app.ister.core.status.ActivityContext;
import app.ister.core.status.ActivitySubjects;
import app.ister.core.utils.AfterCommitPublisher;
import app.ister.core.Handle;
import com.github.kokorin.jaffree.process.JaffreeAbnormalExitException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

@Slf4j
@Service
public class HandleMediaFileFound implements Handle<MediaFileFoundData> {
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
    private final MediaFileFoundEpisodeBoundaries mediaFileFoundEpisodeBoundaries;
    private final MediaFileFoundDetectCrop mediaFileFoundDetectCrop;
    private final MessageSender messageSender;
    private final MediaFileInputResolver inputResolver;
    private final CacheDirectoryResolver cacheDirectoryResolver;
    private final TransactionTemplate transactionTemplate;

    @Value("${app.ister.server.ffmpeg-dir}")
    private String dirOfFFmpeg;

    @Value("${app.ister.server.tmp-dir}")
    private String tmpDir;

    public HandleMediaFileFound(DirectoryRepository directoryRepository,
                                MediaFileRepository mediaFileRepository,
                                EpisodeRepository episodeRepository,
                                MovieRepository movieRepository,
                                MediaFileStreamRepository mediaFileStreamRepository,
                                ImageRepository imageRepository,
                                MediaFileEpisodeRepository mediaFileEpisodeRepository,
                                MediaFileFoundCheckForStreams mediaFileFoundCheckForStreams,
                                MediaFileFoundCreateBackground mediaFileFoundCreateBackground,
                                MediaFileFoundGetDuration mediaFileFoundGetDuration,
                                MediaFileFoundEpisodeBoundaries mediaFileFoundEpisodeBoundaries,
                                MediaFileFoundDetectCrop mediaFileFoundDetectCrop,
                                MessageSender messageSender,
                                MediaFileInputResolver inputResolver,
                                CacheDirectoryResolver cacheDirectoryResolver,
                                PlatformTransactionManager transactionManager) {
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
        this.mediaFileFoundEpisodeBoundaries = mediaFileFoundEpisodeBoundaries;
        this.mediaFileFoundDetectCrop = mediaFileFoundDetectCrop;
        this.messageSender = messageSender;
        this.inputResolver = inputResolver;
        this.cacheDirectoryResolver = cacheDirectoryResolver;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    private static String getPathString(Optional<EpisodeEntity> episodeEntity, Optional<MovieEntity> movieEntity) {
        String id = null;
        if (episodeEntity.isPresent()) {
            id = episodeEntity.get().getId().toString();
        } else if (movieEntity.isPresent()) {
            id = movieEntity.get().getId().toString();
        }
        return id + ".jpg";
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
     * When the scanner finds a media file it only saves the row; analysing the file (duration,
     * streams, crop, episode boundaries, a background still) is this handler's job.
     * <p>
     * All of that shells out to ffprobe/ffmpeg and on a slow disk takes minutes per file, so it
     * runs <em>outside</em> any transaction: holding a connection "idle in transaction" that
     * long starved the pool for every other handler and bloated postgres. Only the persisting of
     * the result is one short transaction; the events that need those rows go out after commit.
     */
    @Override
    public void handle(app.ister.core.eventdata.MediaFileFoundData mediaFileFoundData) {
        String fileName = ActivitySubjects.fileName(mediaFileFoundData.getPath());
        ActivityContext.subject(fileName);
        DirectoryEntity directoryEntity = directoryRepository.findById(mediaFileFoundData.getDirectoryEntityUUID())
                .orElseThrow(() -> new IllegalStateException("Directory not found: " + mediaFileFoundData.getDirectoryEntityUUID()));
        Optional<EpisodeEntity> episodeEntity = mediaFileFoundData.getEpisodeEntityUUID() != null ? episodeRepository.findById(mediaFileFoundData.getEpisodeEntityUUID()) : Optional.empty();
        Optional<MovieEntity> movieEntity = mediaFileFoundData.getMovieEntityUUID() != null ? movieRepository.findById(mediaFileFoundData.getMovieEntityUUID()) : Optional.empty();
        ActivitySubjects.Subject subject = ActivitySubjects.describe(directoryEntity).withTitle(fileName);
        if (episodeEntity.isPresent()) {
            subject = ActivitySubjects.describe(episodeEntity.get(), subject)
                    .withTitle(ActivitySubjects.episodeCode(episodeEntity.get()) + " · " + fileName);
        } else if (movieEntity.isPresent()) {
            subject = ActivitySubjects.describe(movieEntity.get(), subject);
        }
        ActivityContext.report(subject);
        Optional<MediaFileEntity> mediaFile = mediaFileRepository.findByDirectoryEntityAndPath(directoryEntity, mediaFileFoundData.getPath());
        mediaFile.ifPresent(mediaFileEntity -> {
            Analysis analysis = analyze(mediaFileEntity);
            transactionTemplate.executeWithoutResult(status -> persist(directoryEntity, mediaFileEntity, analysis));
            ActivityContext.step("still");
            if (analysis.parts().size() >= 2) {
                // Multi-episode file: every contained episode gets its own background still,
                // taken at the midpoint of its own slice of the file.
                for (MediaFileEpisodeEntity part : analysis.parts()) {
                    episodeRepository.findById(part.getEpisodeEntityId()).ifPresent(partEpisode ->
                            createBackgroundImage(Optional.of(partEpisode), Optional.empty(), mediaFileEntity,
                                    part.getStartInMilliseconds() + part.getDurationInMilliseconds() / 2));
                }
            } else {
                createBackgroundImage(episodeEntity, movieEntity, mediaFileEntity, mediaFileEntity.getDurationInMilliseconds() / 2);
            }
            // Intro/outro detection is season-wide (it compares sibling episodes) and must see the
            // committed duration/detector version, hence after the persist. Idempotent on the
            // handler side, so firing once per analyzed episode is fine.
            episodeEntity.ifPresent(episode -> {
                DetectSegmentsData detectSegmentsData = DetectSegmentsData.builder()
                        .eventType(EventType.DETECT_SEGMENTS)
                        .seasonEntityUUID(episode.getSeasonEntity().getId())
                        .directoryEntityUUID(directoryEntity.getId())
                        .build();
                messageSender.sendDetectSegments(detectSegmentsData, directoryEntity.getName());
            });
        });
    }

    /** What the ffprobe/ffmpeg passes found; nothing here has touched the database yet. */
    record Analysis(List<MediaFileStreamEntity> streams, List<MediaFileEpisodeEntity> parts) {
    }

    /** The slow part: probe the streams and duration, detect the crop, locate episode boundaries. */
    private Analysis analyze(MediaFileEntity mediaFileEntity) {
        ActivityContext.step("probe");
        String input = inputResolver.resolve(mediaFileEntity);
        var checkResult = mediaFileFoundCheckForStreams.checkForStreams(mediaFileEntity, input, dirOfFFmpeg);
        long duration = checkResult.durationInMilliseconds() > 0
                ? checkResult.durationInMilliseconds()
                : mediaFileFoundGetDuration.getDurationByDecodingFile(input);
        mediaFileEntity.setDurationInMilliseconds(duration);

        var streams = checkResult.streams();
        ActivityContext.step("crop");
        detectAndSetCrop(mediaFileEntity, streams, duration);

        return new Analysis(streams, locateEpisodeBoundaries(mediaFileEntity, duration));
    }

    /** The short transaction: replace the stream rows, store duration, crop and boundaries. */
    private void persist(DirectoryEntity directoryEntity, MediaFileEntity mediaFileEntity, Analysis analysis) {
        // Clear existing stream metadata so re-analysis on retry doesn't hit duplicate-key errors.
        mediaFileStreamRepository.deleteAllByMediaFileEntityId(mediaFileEntity.getId());
        mediaFileStreamRepository.flush();
        mediaFileRepository.save(mediaFileEntity);
        mediaFileStreamRepository.saveAll(analysis.streams());
        if (analysis.parts().size() >= 2) {
            mediaFileEpisodeRepository.saveAll(analysis.parts());
        }
        // Embedded subtitles become SRTs in their own event, one per stream: extraction and
        // OCR take minutes and a helper node may do them. After commit, or the handler would
        // not find the rows.
        analysis.streams().stream()
                .filter(SubtitleExtractor::isExtractable)
                .forEach(stream -> {
                    SubtitleExtractRequestedData data = SubtitleExtractRequestedData.builder()
                            .eventType(EventType.SUBTITLE_EXTRACT_REQUESTED)
                            .mediaFileEntityUUID(mediaFileEntity.getId())
                            .directoryEntityUUID(directoryEntity.getId())
                            .subtitleStreamEntityUUID(stream.getId())
                            .build();
                    AfterCommitPublisher.publishAfterCommit(() ->
                            messageSender.sendSubtitleExtractRequested(data, directoryEntity.getName()));
                });
    }

    /**
     * For a multi-episode file (s04e06-e07.mkv): compute where each episode starts, preferring the
     * MKV chapter markers, and put the slices on the link rows (saved by {@link #persist}).
     * Idempotent on re-analysis.
     */
    private List<MediaFileEpisodeEntity> locateEpisodeBoundaries(MediaFileEntity mediaFileEntity, long duration) {
        List<MediaFileEpisodeEntity> parts = mediaFileEpisodeRepository.findByMediaFileEntityIdOrderByPartNumber(mediaFileEntity.getId());
        if (parts.size() < 2 || duration <= 0) {
            return parts;
        }
        ActivityContext.step("boundaries");
        List<Long> starts = mediaFileFoundEpisodeBoundaries.boundaryStarts(inputResolver.resolve(mediaFileEntity), dirOfFFmpeg, duration, parts.size());
        for (int i = 0; i < parts.size(); i++) {
            long end = i + 1 < parts.size() ? starts.get(i + 1) : duration;
            parts.get(i).setStartInMilliseconds(starts.get(i));
            parts.get(i).setDurationInMilliseconds(end - starts.get(i));
        }
        return parts;
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
                        .detectCrop(inputResolver.resolve(mediaFileEntity), dirOfFFmpeg, duration,
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
    private void createBackgroundImage(Optional<EpisodeEntity> episodeEntity, Optional<MovieEntity> movieEntity, MediaFileEntity mediaFileEntity, long stillAtMilliseconds) {
        String mediaFilePath = mediaFileEntity.getPath();
        // Query the image repository directly instead of navigating the entities' LAZY
        // imagesEntities collection: this handler runs on a RabbitMQ listener thread with no
        // open-session-in-view, so lazy navigation would throw LazyInitializationException.
        boolean episodeNeedsBackground = episodeEntity.isPresent() && !imageRepository.existsByEpisodeEntityId(episodeEntity.get().getId());
        boolean movieNeedsBackground = movieEntity.isPresent() && !imageRepository.existsByMovieEntityId(movieEntity.get().getId());
        if (episodeNeedsBackground || movieNeedsBackground) {
            CacheStore cacheStore = cacheDirectoryResolver.store();
            DirectoryEntity cacheDisk = cacheStore.directory();
            String relativeKey = getPathString(episodeEntity, movieEntity);
            String toPath;
            try {
                // ffmpeg writes a local file; the store moves it into place (or uploads it).
                Path still = Files.createTempFile(Path.of(tmpDir), "still-", ".jpg");
                mediaFileFoundCreateBackground.createBackground(still, inputResolver.resolve(mediaFileEntity), dirOfFFmpeg, stillAtMilliseconds);
                toPath = cacheStore.write(relativeKey, still, "image/jpeg");
            } catch (JaffreeAbnormalExitException | IOException e) {
                log.error("Failed to create background image for {}: {}", mediaFilePath, e.getMessage());
                return;
            }

            ImageEntity imageEntity = ImageEntity.builder()
                    .directoryEntityId(cacheDisk.getId())
                    .path(toPath)
                    .sourceUri(SourceUris.of(mediaFilePath))
                    .type(ImageType.BACKGROUND)
                    .episodeEntityId(episodeEntity.map(EpisodeEntity::getId).orElse(null))
                    .movieEntityId(movieEntity.map(MovieEntity::getId).orElse(null))
                    .build();
            messageSender.sendImageFound(ImageFoundData.fromImageEntity(imageEntity), cacheDisk.getName());
        }
    }
}
