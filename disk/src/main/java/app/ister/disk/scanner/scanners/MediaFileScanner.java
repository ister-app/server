package app.ister.disk.scanner.scanners;

import app.ister.core.entity.*;
import app.ister.core.enums.EventType;
import app.ister.core.eventdata.DetectSegmentsData;
import app.ister.core.eventdata.MediaFileFoundData;
import app.ister.core.enums.StreamCodecType;
import app.ister.core.repository.MediaFileEpisodeRepository;
import app.ister.core.repository.MediaFileRepository;
import app.ister.core.repository.MediaFileStreamRepository;
import app.ister.core.service.MessageSender;
import app.ister.core.service.ScannerHelperService;
import app.ister.disk.events.detectsegments.SegmentDetectionChunkProcessor;
import app.ister.disk.events.mediafilefound.MediaFileFoundExtractSubtitles;
import app.ister.disk.scanner.PathObject;
import app.ister.disk.scanner.enums.DirType;
import app.ister.disk.scanner.enums.FileType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Component
@Slf4j
@Transactional(propagation = Propagation.REQUIRES_NEW)
@RequiredArgsConstructor
public class MediaFileScanner implements Scanner {
    private final ScannerHelperService scannerHelperService;
    private final MediaFileRepository mediaFileRepository;
    private final MediaFileEpisodeRepository mediaFileEpisodeRepository;
    private final MediaFileStreamRepository mediaFileStreamRepository;
    private final MessageSender messageSender;

    /**
     * Media files for which this run already requested a subtitle re-extract.
     * A permanently failing OCR would otherwise re-trigger the (heavy) full
     * re-analysis on every rescan; once per application run is enough.
     */
    private final Set<UUID> subtitleReextractRequested = ConcurrentHashMap.newKeySet();

    /** Same once-per-run guard for the crop-detection backfill. */
    private final Set<UUID> cropDetectRequested = ConcurrentHashMap.newKeySet();

    /** Same once-per-run guard for the intro/outro segment-detection backfill, per season. */
    private final Set<UUID> segmentDetectRequested = ConcurrentHashMap.newKeySet();

    /**
     * Escape hatch for very large libraries: the crop backfill re-analyzes
     * every pre-existing file once (heavy pass); disable to defer it.
     */
    @Value("${app.ister.server.crop-detect-backfill:true}")
    private boolean cropDetectBackfill;

    /**
     * Escape hatch like the crop one: the segment backfill fingerprints every
     * pre-existing episode file once (two short ffmpeg decodes per file).
     */
    @Value("${app.ister.server.segment-detect-backfill:true}")
    private boolean segmentDetectBackfill;

    @Override
    public boolean analyzable(Path path, boolean isRegularFile, long size) {
        PathObject pathObject = new PathObject(path.toString());
        return isRegularFile
                && List.of(DirType.EPISODE, DirType.MOVIE).contains(pathObject.getDirType())
                && pathObject.getFileType().equals(FileType.MEDIA);
    }

    @Override
    public Optional<BaseEntity> analyze(DirectoryEntity directoryEntity, Path path, boolean isRegularFile, long size) {
        PathObject pathObject = new PathObject(path.toString());
        List<EpisodeEntity> episodeEntities = List.of();
        Optional<MovieEntity> movieEntity = Optional.empty();
        UUID movieId = null;
        switch (pathObject.getDirType()) {
            case EPISODE -> episodeEntities = pathObject.getEpisodes().stream()
                    .map(episodeNumber -> scannerHelperService.getOrCreateEpisode(directoryEntity.getLibraryEntity(), pathObject.getName(), pathObject.getYear(), pathObject.getSeason(), episodeNumber))
                    .toList();
            case MOVIE -> {
                movieEntity = Optional.of(scannerHelperService.getOrCreateMovie(directoryEntity.getLibraryEntity(), pathObject.getName(), pathObject.getYear()));
                movieId = movieEntity.get().getId();
            }
            default -> throw new IllegalStateException("Only EPISODE or MOVIE is supported");
        }
        // The FK on the media file always points at the first episode of the file; the other
        // episodes of a multi-episode file are linked through MediaFileEpisodeEntity rows.
        Optional<EpisodeEntity> episodeEntity = episodeEntities.stream().findFirst();
        UUID episodeId = episodeEntity.map(BaseEntity::getId).orElse(null);
        List<UUID> episodeIds = episodeEntities.stream().map(BaseEntity::getId).toList();

        Optional<MediaFileEntity> mediaFile = mediaFileRepository.findByDirectoryEntityAndPath(directoryEntity, path.toString());
        if (mediaFile.isEmpty()) {
            MediaFileEntity entity = MediaFileEntity.builder()
                    .directoryEntityId(directoryEntity.getId())
                    .episodeEntity(episodeEntity.orElse(null))
                    .movieEntity(movieEntity.orElse(null))
                    .path(path.toString())
                    .size(size).build();
            mediaFileRepository.save(entity);
            createEpisodeLinks(entity, episodeIds);
            sendMediaFileFound(directoryEntity, path, episodeId, episodeIds.isEmpty() ? null : episodeIds, movieId);
        } else {
            maybeBackfill(directoryEntity, path, mediaFile.get(), episodeEntity, episodeId, episodeIds, movieId);
        }
        return Optional.ofNullable(episodeEntity.orElse(null));
    }

    /**
     * One backfill per file per rescan, in priority order, for files that predate a
     * feature: multi-episode links, crop detection, intro/outro segments, subtitle OCR.
     * Each branch is idempotent, so firing at most one of them per pass converges over
     * a few rescans without flooding the analyzer.
     */
    private void maybeBackfill(DirectoryEntity directoryEntity, Path path, MediaFileEntity mediaFile,
                               Optional<EpisodeEntity> episodeEntity, UUID episodeId, List<UUID> episodeIds,
                               UUID movieId) {
        if (episodeIds.size() > 1
                && mediaFileEpisodeRepository.findByMediaFileEntityIdOrderByPartNumber(mediaFile.getId()).isEmpty()) {
            // Backfill for files scanned before multi-episode support: the file row exists but the
            // range episodes and their link rows do not. Create them and re-analyze so the episode
            // boundaries get computed. Idempotent: the next rescan finds the link rows and skips this.
            log.info("Backfilling multi-episode links for {}", path);
            createEpisodeLinks(mediaFile, episodeIds);
            sendMediaFileFound(directoryEntity, path, episodeId, episodeIds, null);
        } else if (cropDetectBackfill
                && !cropDetectRequested.contains(mediaFile.getId())
                && needsCropDetect(mediaFile.getId())) {
            // Backfill for files analyzed before crop detection existed: null
            // crop columns on a video stream mean detection never ran (a
            // detected "no bars" is stored as the full frame). Re-analysis
            // writes the columns, so this fires at most once per file.
            cropDetectRequested.add(mediaFile.getId());
            log.info("Detecting baked-in black bars for {}", path);
            sendMediaFileFound(directoryEntity, path, episodeId, episodeIds.isEmpty() ? null : episodeIds, movieId);
        } else if (segmentDetectBackfill
                && episodeEntity.isPresent()
                && needsSegmentDetect(mediaFile)
                && segmentDetectRequested.add(episodeEntity.get().getSeasonEntity().getId())) {
            // Backfill for episode files analyzed before intro/outro detection existed: a null
            // (or outdated) detector version means detection never ran — "ran, found nothing"
            // stores the version too. No full re-analysis needed, just the detection event;
            // once per season per run, the handler covers its siblings.
            log.info("Detecting intro/outro segments for the season of {}", path);
            messageSender.sendDetectSegments(DetectSegmentsData.builder()
                    .eventType(EventType.DETECT_SEGMENTS)
                    .seasonEntityUUID(episodeEntity.get().getSeasonEntity().getId())
                    .directoryEntityUUID(directoryEntity.getId())
                    .build(), directoryEntity.getName());
        } else if (!subtitleReextractRequested.contains(mediaFile.getId())
                && needsSubtitleReextract(mediaFile.getId())) {
            // Backfill for files whose image subtitles (DVD/PGS bitmaps) never produced an
            // OCR'd SRT — scanned before OCR existed, or the OCR failed (e.g. an untagged
            // language before the fallback). Re-analysis re-runs extraction; idempotent
            // because stream rows are rewritten and existing SRTs on disk are skipped.
            subtitleReextractRequested.add(mediaFile.getId());
            log.info("Re-extracting image subtitles for {}", path);
            sendMediaFileFound(directoryEntity, path, episodeId, episodeIds.isEmpty() ? null : episodeIds, movieId);
        }
    }

    private void sendMediaFileFound(DirectoryEntity directoryEntity, Path path, UUID episodeId,
                                    List<UUID> episodeIds, UUID movieId) {
        messageSender.sendMediaFileFound(MediaFileFoundData.builder()
                .eventType(EventType.MEDIA_FILE_FOUND)
                .directoryEntityUUID(directoryEntity.getId())
                .episodeEntityUUID(episodeId)
                .episodeEntityUUIDs(episodeIds)
                .movieEntityUUID(movieId)
                .path(path.toString()).build(), directoryEntity.getName());
    }

    /**
     * True when the file has a video stream whose crop columns were never
     * written (detection predates the file's last analysis).
     */
    boolean needsCropDetect(UUID mediaFileId) {
        return mediaFileStreamRepository
                .findByMediaFileEntity_IdAndCodecType(mediaFileId, StreamCodecType.VIDEO).stream()
                .anyMatch(s -> s.getWidth() > 0 && s.getCropWidth() == null);
    }

    /**
     * True when intro/outro detection (at the current version) never ran for
     * this analyzed episode file. Unanalyzed files are left to the normal
     * MEDIA_FILE_FOUND flow, which triggers detection itself.
     */
    boolean needsSegmentDetect(MediaFileEntity mediaFile) {
        return mediaFile.getDurationInMilliseconds() > 0
                && (mediaFile.getSegmentDetectorVersion() == null
                || mediaFile.getSegmentDetectorVersion() < SegmentDetectionChunkProcessor.DETECTOR_VERSION);
    }

    /**
     * True when the file has an image-codec subtitle stream without an OCR'd
     * counterpart at the same stream index.
     */
    boolean needsSubtitleReextract(UUID mediaFileId) {
        var imageSubs = mediaFileStreamRepository
                .findByMediaFileEntity_IdAndCodecType(mediaFileId, StreamCodecType.SUBTITLE).stream()
                .filter(s -> s.getCodecName() != null
                        && MediaFileFoundExtractSubtitles.IMAGE_SUBTITLE_CODECS.contains(s.getCodecName().toLowerCase()))
                .toList();
        if (imageSubs.isEmpty()) {
            return false;
        }
        var extractedIndexes = mediaFileStreamRepository
                .findByMediaFileEntity_IdAndCodecType(mediaFileId, StreamCodecType.EXTERNAL_SUBTITLE).stream()
                .map(MediaFileStreamEntity::getStreamIndex)
                .collect(Collectors.toSet());
        return imageSubs.stream().anyMatch(s -> !extractedIndexes.contains(s.getStreamIndex()));
    }

    private void createEpisodeLinks(MediaFileEntity mediaFile, List<UUID> episodeIds) {
        if (episodeIds.size() < 2) {
            return;
        }
        List<MediaFileEpisodeEntity> links = new ArrayList<>();
        for (int part = 0; part < episodeIds.size(); part++) {
            links.add(MediaFileEpisodeEntity.builder()
                    .mediaFileEntityId(mediaFile.getId())
                    .episodeEntityId(episodeIds.get(part))
                    .partNumber(part)
                    .startInMilliseconds(0)
                    .durationInMilliseconds(0)
                    .build());
        }
        mediaFileEpisodeRepository.saveAll(links);
    }
}
