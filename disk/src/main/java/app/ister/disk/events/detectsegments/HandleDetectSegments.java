package app.ister.disk.events.detectsegments;

import app.ister.core.Handle;
import app.ister.core.entity.EpisodeEntity;
import app.ister.core.entity.MediaFileEntity;
import app.ister.core.entity.MediaFileSegmentEntity;
import app.ister.core.enums.EventType;
import app.ister.core.enums.SegmentType;
import app.ister.core.eventdata.DetectSegmentsData;
import app.ister.core.repository.EpisodeRepository;
import app.ister.core.repository.MediaFileRepository;
import app.ister.core.repository.MediaFileSegmentRepository;
import app.ister.core.service.MediaFileEpisodeService;
import app.ister.core.status.ActivityContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;

import static app.ister.disk.events.detectsegments.SegmentMatcher.Segment;

/**
 * Season-wide intro/outro detection: compares audio fingerprints of neighbouring episodes and
 * stores the shared runs as {@link MediaFileSegmentEntity} rows in absolute file time.
 *
 * <p>Fired once per analyzed episode file, so it must be (and is) idempotent: episodes whose file
 * already carries {@code segmentDetectorVersion == DETECTOR_VERSION} are only used as comparison
 * material, never re-detected. Only files in the event's directory are considered — a season
 * spread over nodes only pairs its local episodes (documented limitation).
 */
@Slf4j
@Service
@Transactional
@RequiredArgsConstructor
public class HandleDetectSegments implements Handle<DetectSegmentsData> {

    /** Bump to re-run detection on every file via the scanner's backfill. */
    // v2: near-silent frames are excluded from matching (v1 detected a bogus
    // whole-window outro on files with silent audio tracks).
    // v3: intro start capped to the first half of the episode (on short episodes
    // the whole file fits the intro window, and a shared *outro* run near the end
    // could win the longest-run contest and be accepted as the intro).
    public static final int DETECTOR_VERSION = 3;

    static final long INTRO_WINDOW_MS = 10 * 60_000L;
    static final long OUTRO_WINDOW_MS = 4 * 60_000L;
    static final long INTRO_MIN_MS = 15_000;
    static final long INTRO_MAX_MS = 150_000;
    /** An intro starts within the first 5 minutes; later shared audio is a mid-episode motif. */
    static final long INTRO_MAX_START_MS = 5 * 60_000L;
    static final long OUTRO_MIN_MS = 20_000;
    /** An outro's shared run ends near the end of the file — within this much of it. */
    static final long OUTRO_END_SLACK_MS = 60_000;

    /** Episodes compared against at most this many season neighbours (by episode order). */
    static final int MAX_NEIGHBOURS = 4;

    private final EpisodeRepository episodeRepository;
    private final MediaFileRepository mediaFileRepository;
    private final MediaFileSegmentRepository mediaFileSegmentRepository;
    private final MediaFileEpisodeService mediaFileEpisodeService;
    private final AudioPcmReader audioPcmReader;

    @Value("${app.ister.server.ffmpeg-dir}")
    private String dirOfFFmpeg;

    /** One episode's slice of a media file: the whole file, or its part of a multi-episode file. */
    record EpisodeSlice(MediaFileEntity mediaFile, UUID episodeId, boolean multiEpisode,
                        long sliceStartMs, long sliceEndMs) {
        long sliceLengthMs() {
            return sliceEndMs - sliceStartMs;
        }
    }

    @Override
    public EventType handles() {
        return EventType.DETECT_SEGMENTS;
    }

    @RabbitListener(queues = "#{@diskQueueNamingConfig.getDetectSegmentsQueues()}")
    @Override
    public void listener(DetectSegmentsData detectSegmentsData) {
        Handle.super.listener(detectSegmentsData);
    }

    @Override
    public void handle(DetectSegmentsData data) {
        episodeRepository.findBySeasonEntityIdOrderByNumberAsc(data.getSeasonEntityUUID()).stream()
                .findFirst()
                .map(EpisodeEntity::getSeasonEntity)
                .filter(season -> season.getShowEntity() != null)
                .ifPresent(season -> ActivityContext.subject(
                        season.getShowEntity().getName() + " S" + season.getNumber()));
        List<EpisodeSlice> slices = localAnalyzedSlices(data);
        List<EpisodeSlice> pending = slices.stream()
                .filter(s -> needsDetection(s.mediaFile()))
                .toList();
        if (pending.isEmpty()) {
            return;
        }
        if (slices.size() < 2) {
            // Not enough material to compare yet; the trigger of a later episode retries.
            log.debug("Season {} has {} analyzable local episode(s), skipping segment detection",
                    data.getSeasonEntityUUID(), slices.size());
            return;
        }
        long hopMs = ChromaFingerprinter.hopMillis(AudioPcmReader.SAMPLE_RATE);
        // Fingerprints are recomputed per event but shared within it, so the common case —
        // one event per episode of a freshly scanned season, the last one doing all the work —
        // decodes each window once, not once per pairing.
        Map<UUID, ChromaFingerprinter.Fingerprint> introPrints = new HashMap<>();
        Map<UUID, ChromaFingerprinter.Fingerprint> outroPrints = new HashMap<>();
        Map<UUID, List<MediaFileSegmentEntity>> segmentsByFile = new LinkedHashMap<>();
        int minSupport = slices.size() == 2 ? 1 : 2;

        ActivityContext.step("fingerprint");
        for (EpisodeSlice slice : pending) {
            List<EpisodeSlice> neighbours = neighboursOf(slices, slice);
            List<Segment> introCandidates = new ArrayList<>();
            List<Segment> outroCandidates = new ArrayList<>();
            for (EpisodeSlice neighbour : neighbours) {
                SegmentMatcher.longestCommonRun(
                                introPrint(introPrints, slice), introPrint(introPrints, neighbour), hopMs)
                        .filter(run -> acceptIntro(run, slice.sliceLengthMs()))
                        .ifPresent(introCandidates::add);
                SegmentMatcher.longestCommonRun(
                                outroPrint(outroPrints, slice), outroPrint(outroPrints, neighbour), hopMs)
                        .filter(run -> acceptOutro(run, outroWindowLengthMs(slice)))
                        .ifPresent(outroCandidates::add);
            }
            List<MediaFileSegmentEntity> rows =
                    segmentsByFile.computeIfAbsent(slice.mediaFile().getId(), id -> new ArrayList<>());
            SegmentMatcher.aggregate(introCandidates, minSupport).ifPresent(intro ->
                    rows.add(row(slice, SegmentType.INTRO,
                            slice.sliceStartMs() + intro.startMs(), slice.sliceStartMs() + intro.endMs())));
            SegmentMatcher.aggregate(outroCandidates, minSupport).ifPresent(outro -> {
                long windowOffset = slice.sliceEndMs() - outroWindowLengthMs(slice);
                rows.add(row(slice, SegmentType.OUTRO,
                        windowOffset + outro.startMs(), windowOffset + outro.endMs()));
            });
        }

        ActivityContext.step("match");
        for (EpisodeSlice slice : pending) {
            MediaFileEntity mediaFile = slice.mediaFile();
            if (mediaFile.getSegmentDetectorVersion() != null
                    && mediaFile.getSegmentDetectorVersion() == DETECTOR_VERSION) {
                continue; // Multi-episode file already persisted for an earlier slice this run.
            }
            List<MediaFileSegmentEntity> rows = segmentsByFile.getOrDefault(mediaFile.getId(), List.of());
            mediaFileSegmentRepository.deleteAllByMediaFileEntityId(mediaFile.getId());
            mediaFileSegmentRepository.saveAll(rows);
            // Found or not: mark the version so this file is never re-detected until a re-analysis
            // resets it or the detector version is bumped.
            mediaFile.setSegmentDetectorVersion(DETECTOR_VERSION);
            mediaFileRepository.save(mediaFile);
            log.info("Segment detection for {}: {} segment(s)", mediaFile.getPath(), rows.size());
        }
    }

    /** True when detection (at the current version) never ran for the file. */
    static boolean needsDetection(MediaFileEntity mediaFile) {
        return mediaFile.getSegmentDetectorVersion() == null
                || mediaFile.getSegmentDetectorVersion() < DETECTOR_VERSION;
    }

    static boolean acceptIntro(Segment run, long sliceLengthMs) {
        long maxLength = Math.min(INTRO_MAX_MS, sliceLengthMs / 4);
        // The start cap is also relative: on episodes shorter than twice
        // INTRO_MAX_START the shared *outro* would otherwise qualify as an intro.
        long maxStart = Math.min(INTRO_MAX_START_MS, sliceLengthMs / 2);
        return run.lengthMs() >= INTRO_MIN_MS
                && run.lengthMs() <= maxLength
                && run.startMs() <= maxStart;
    }

    static boolean acceptOutro(Segment run, long windowLengthMs) {
        return run.lengthMs() >= OUTRO_MIN_MS
                && run.endMs() >= windowLengthMs - OUTRO_END_SLACK_MS;
    }

    /** Up to {@link #MAX_NEIGHBOURS} slices nearest to {@code slice} in episode order. */
    static List<EpisodeSlice> neighboursOf(List<EpisodeSlice> slices, EpisodeSlice slice) {
        int index = slices.indexOf(slice);
        List<EpisodeSlice> neighbours = new ArrayList<>();
        for (int distance = 1; neighbours.size() < MAX_NEIGHBOURS && distance < slices.size(); distance++) {
            if (index - distance >= 0) {
                neighbours.add(slices.get(index - distance));
            }
            if (neighbours.size() < MAX_NEIGHBOURS && index + distance < slices.size()) {
                neighbours.add(slices.get(index + distance));
            }
        }
        return neighbours;
    }

    /**
     * The season's episodes as analyzable slices, in episode order: one per episode with an
     * analyzed (duration > 0) media file in the event's directory.
     */
    private List<EpisodeSlice> localAnalyzedSlices(DetectSegmentsData data) {
        List<EpisodeSlice> slices = new ArrayList<>();
        for (EpisodeEntity episode : episodeRepository.findBySeasonEntityIdOrderByNumberAsc(data.getSeasonEntityUUID())) {
            mediaFileEpisodeService.filesForEpisode(episode.getId()).stream()
                    .filter(mf -> mf.getDirectoryEntityId().equals(data.getDirectoryEntityUUID()))
                    .filter(mf -> mf.getDurationInMilliseconds() > 0)
                    .findFirst()
                    .flatMap(mf -> sliceFor(mf, episode.getId()))
                    .ifPresent(slices::add);
        }
        return slices;
    }

    private Optional<EpisodeSlice> sliceFor(MediaFileEntity mediaFile, UUID episodeId) {
        return mediaFileEpisodeService.segmentFor(mediaFile.getId(), episodeId)
                .map(part -> part.getDurationInMilliseconds() > 0
                        ? Optional.of(new EpisodeSlice(mediaFile, episodeId, true,
                        part.getStartInMilliseconds(),
                        part.getStartInMilliseconds() + part.getDurationInMilliseconds()))
                        // Multi-episode file whose boundaries were never computed: skip it.
                        : Optional.<EpisodeSlice>empty())
                .orElseGet(() -> Optional.of(new EpisodeSlice(mediaFile, episodeId, false,
                        0, mediaFile.getDurationInMilliseconds())));
    }

    private ChromaFingerprinter.Fingerprint introPrint(
            Map<UUID, ChromaFingerprinter.Fingerprint> cache, EpisodeSlice slice) {
        return print(cache, slice, s -> audioPcmReader.readMonoPcm(
                Path.of(s.mediaFile().getPath()), dirOfFFmpeg,
                s.sliceStartMs(), Math.min(INTRO_WINDOW_MS, s.sliceLengthMs())));
    }

    private ChromaFingerprinter.Fingerprint outroPrint(
            Map<UUID, ChromaFingerprinter.Fingerprint> cache, EpisodeSlice slice) {
        return print(cache, slice, s -> audioPcmReader.readMonoPcm(
                Path.of(s.mediaFile().getPath()), dirOfFFmpeg,
                s.sliceEndMs() - outroWindowLengthMs(s), outroWindowLengthMs(s)));
    }

    private static long outroWindowLengthMs(EpisodeSlice slice) {
        return Math.min(OUTRO_WINDOW_MS, slice.sliceLengthMs());
    }

    private ChromaFingerprinter.Fingerprint print(Map<UUID, ChromaFingerprinter.Fingerprint> cache,
                                                  EpisodeSlice slice, Function<EpisodeSlice, short[]> pcm) {
        return cache.computeIfAbsent(slice.episodeId(),
                id -> ChromaFingerprinter.fingerprint(pcm.apply(slice), AudioPcmReader.SAMPLE_RATE));
    }

    /** A segment row in absolute file time; episodeEntityId only disambiguates multi-episode files. */
    private static MediaFileSegmentEntity row(EpisodeSlice slice, SegmentType type, long startMs, long endMs) {
        return MediaFileSegmentEntity.builder()
                .mediaFileEntityId(slice.mediaFile().getId())
                .episodeEntityId(slice.multiEpisode() ? slice.episodeId() : null)
                .type(type)
                .startInMilliseconds(startMs)
                .endInMilliseconds(endMs)
                .build();
    }
}
