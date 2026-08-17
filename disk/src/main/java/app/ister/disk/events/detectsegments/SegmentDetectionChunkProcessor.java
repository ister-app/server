package app.ister.disk.events.detectsegments;

import app.ister.core.entity.DirectoryEntity;
import app.ister.core.entity.EpisodeEntity;
import app.ister.core.entity.MediaFileEntity;
import app.ister.core.entity.MediaFileSegmentEntity;
import app.ister.core.enums.SegmentType;
import app.ister.core.repository.DirectoryRepository;
import app.ister.core.repository.EpisodeRepository;
import app.ister.core.repository.MediaFileRepository;
import app.ister.core.repository.MediaFileSegmentRepository;
import app.ister.core.service.MediaFileEpisodeService;
import app.ister.core.status.ActivityContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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
 * <p>Detects at most one chunk of episodes per call, in its own transaction, so that one RabbitMQ
 * message never runs longer than the broker's consumer timeout. Deliberately a separate bean from
 * {@link HandleDetectSegments}: the handler must publish the successor message only after this
 * transaction has committed, or a failing commit would leave a successor behind for work that was
 * never saved.
 *
 * <p>Fired once per analyzed episode file, so it must be (and is) idempotent: episodes whose file
 * already carries {@code segmentDetectorVersion == DETECTOR_VERSION} are only used as comparison
 * material, never re-detected. That idempotence is serial, not concurrent — the recompute is a
 * delete-then-insert, and two transactions doing it at once neither see nor cancel each other's
 * rows — so a season is claimed with a non-blocking advisory lock and unclaimed messages are
 * dropped. Only files in the event's directory are considered — a season spread over nodes only
 * pairs its local episodes (documented limitation).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SegmentDetectionChunkProcessor {

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
    private final DirectoryRepository directoryRepository;
    private final AudioPcmReader audioPcmReader;

    @Value("${app.ister.server.ffmpeg-dir}")
    private String dirOfFFmpeg;

    /**
     * One processed chunk. {@code remaining} counts the pending episodes left for a successor
     * message; {@code directoryName} routes that successor to the same directory queue (null when
     * nothing remains).
     */
    public record Chunk(int processed, int remaining, String directoryName) {
    }

    /** One episode's slice of a media file: the whole file, or its part of a multi-episode file. */
    record EpisodeSlice(MediaFileEntity mediaFile, UUID episodeId, boolean multiEpisode,
                        long sliceStartMs, long sliceEndMs) {
        long sliceLengthMs() {
            return sliceEndMs - sliceStartMs;
        }
    }

    @Transactional
    public Chunk process(UUID seasonEntityId, UUID directoryEntityId, int chunkSize) {
        if (!mediaFileSegmentRepository.tryLockSeason(
                MediaFileSegmentRepository.SEGMENT_DETECTION_LOCK_NAMESPACE, seasonEntityId)) {
            // Another transaction is detecting this season. Every analyzed episode file publishes a
            // DETECT_SEGMENTS for its season, so a re-analysis sweep delivers many messages for the
            // same season at once; letting them run side by side both duplicated the fingerprinting
            // and stored each segment once per consumer (their delete-then-insert cannot see each
            // other's rows). Drop this one: the holder's chunk chain finishes the season.
            log.debug("Season {} is already being detected, skipping this message", seasonEntityId);
            return new Chunk(0, 0, null);
        }
        episodeRepository.findBySeasonEntityIdOrderByNumberAsc(seasonEntityId).stream()
                .findFirst()
                .map(EpisodeEntity::getSeasonEntity)
                .filter(season -> season.getShowEntity() != null)
                .ifPresent(season -> ActivityContext.subject(
                        season.getShowEntity().getName() + " S" + season.getNumber()));
        List<EpisodeSlice> slices = localAnalyzedSlices(seasonEntityId, directoryEntityId);
        List<EpisodeSlice> pending = slices.stream()
                .filter(s -> needsDetection(s.mediaFile()))
                .toList();
        if (pending.isEmpty()) {
            return new Chunk(0, 0, null);
        }
        if (slices.size() < 2) {
            // Not enough material to compare yet; the trigger of a later episode retries.
            log.debug("Season {} has {} analyzable local episode(s), skipping segment detection",
                    seasonEntityId, slices.size());
            return new Chunk(0, 0, null);
        }
        List<EpisodeSlice> workSet = chunkOf(pending, chunkSize);
        long hopMs = ChromaFingerprinter.hopMillis(AudioPcmReader.SAMPLE_RATE);
        // Fingerprints are recomputed per event but shared within it, so neighbours of the chunk's
        // episodes are decoded once per message, not once per pairing.
        Map<UUID, ChromaFingerprinter.Fingerprint> introPrints = new HashMap<>();
        Map<UUID, ChromaFingerprinter.Fingerprint> outroPrints = new HashMap<>();
        Map<UUID, List<MediaFileSegmentEntity>> segmentsByFile = new LinkedHashMap<>();
        int minSupport = slices.size() == 2 ? 1 : 2;

        ActivityContext.step("fingerprint");
        for (EpisodeSlice slice : workSet) {
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
        for (EpisodeSlice slice : workSet) {
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

        int remaining = pending.size() - workSet.size();
        String directoryName = remaining > 0
                ? directoryRepository.findById(directoryEntityId).map(DirectoryEntity::getName).orElse(null)
                : null;
        return new Chunk(workSet.size(), remaining, directoryName);
    }

    /**
     * The first {@code chunkSize} pending slices, stretched so that slices of one multi-episode
     * file never straddle a chunk boundary: persisting stamps the detector version per file, so a
     * left-behind sibling slice would be filtered out of the successor's pending set and lose its
     * segments.
     */
    static List<EpisodeSlice> chunkOf(List<EpisodeSlice> pending, int chunkSize) {
        int end = Math.min(chunkSize, pending.size());
        while (end < pending.size()
                && pending.get(end).mediaFile().getId().equals(pending.get(end - 1).mediaFile().getId())) {
            end++;
        }
        return pending.subList(0, end);
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
    private List<EpisodeSlice> localAnalyzedSlices(UUID seasonEntityId, UUID directoryEntityId) {
        List<EpisodeSlice> slices = new ArrayList<>();
        for (EpisodeEntity episode : episodeRepository.findBySeasonEntityIdOrderByNumberAsc(seasonEntityId)) {
            mediaFileEpisodeService.filesForEpisode(episode.getId()).stream()
                    .filter(mf -> mf.getDirectoryEntityId().equals(directoryEntityId))
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
