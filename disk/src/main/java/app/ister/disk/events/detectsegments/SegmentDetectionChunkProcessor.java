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
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

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
 *
 * <p>Two match strategies, both robust against sub-hop misalignment (see
 * {@link #PHASE_SHIFTS_SAMPLES}):
 * <ul>
 *   <li><b>Pairwise</b>: an episode against up to {@link #MAX_NEIGHBOURS} season neighbours,
 *       median-aggregated over at least two agreeing pairs. Pending episodes are ordered from the
 *       season's ends inward, so both ends of a season contribute confirmed intros early.</li>
 *   <li><b>Template</b>: once the season carries {@link #TEMPLATE_SUPPORT} confirmed intros, the
 *       remaining episodes are matched against those confirmed intros directly. A template is a
 *       known-good intro, so a single match down to {@link #TEMPLATE_INTRO_MIN_MS} suffices — that
 *       rescues episodes whose intro variant no neighbour shares — and matching a ~20 s template
 *       is far cheaper than aligning two whole windows. When the season's last chunk finishes,
 *       episodes that failed the pairwise stage get one template retry.</li>
 * </ul>
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
    // v4: quarter-hop phase sweep (a lag between two episodes that falls between
    // hash frames fragmented the shared run below the minimum), intro bounds
    // widened (min 15 s -> 10 s: many intros carry per-episode voice-over and only
    // ~13 s is identical; start cap 5 -> 8 min: long cold opens), and the
    // template stage + retry described on the class.
    public static final int DETECTOR_VERSION = 4;

    static final long INTRO_WINDOW_MS = 10 * 60_000L;
    static final long OUTRO_WINDOW_MS = 4 * 60_000L;
    static final long INTRO_MIN_MS = 10_000;
    static final long INTRO_MAX_MS = 150_000;
    /** An intro starts within the first 8 minutes; later shared audio is a mid-episode motif. */
    static final long INTRO_MAX_START_MS = 8 * 60_000L;
    static final long OUTRO_MIN_MS = 20_000;
    /** An outro's shared run ends near the end of the file — within this much of it. */
    static final long OUTRO_END_SLACK_MS = 60_000;

    /** Episodes compared against at most this many season neighbours (by episode order). */
    static final int MAX_NEIGHBOURS = 4;

    /**
     * The hash hop is 128 ms; when the true lag between two episodes falls between hops, every
     * frame pair looks at audio shifted by up to a half hop and the hashes drift apart. Each
     * comparison fingerprint is therefore computed at these sample offsets (quarter hops) and the
     * phase with the longest shared run wins, bounding the residual misalignment to an eighth of
     * a hop (16 ms).
     */
    static final int[] PHASE_SHIFTS_SAMPLES = {
            0, ChromaFingerprinter.HOP_SIZE / 4,
            ChromaFingerprinter.HOP_SIZE / 2,
            3 * ChromaFingerprinter.HOP_SIZE / 4};

    /** Template matching starts once the season has this many confirmed intros. */
    static final int TEMPLATE_SUPPORT = 3;

    /** How many confirmed intros are used as templates (first, middle, last of the season). */
    static final int TEMPLATE_COUNT = 3;

    /**
     * A template is a confirmed intro, so a single shorter match is trustworthy where the
     * pairwise stage demands {@link #INTRO_MIN_MS} over two agreeing neighbours.
     */
    static final long TEMPLATE_INTRO_MIN_MS = 8_000;

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
        List<EpisodeSlice> workSet = chunkOf(endsInward(pending), chunkSize);
        long hopMs = ChromaFingerprinter.hopMillis(AudioPcmReader.SAMPLE_RATE);
        // Fingerprints are recomputed per event but shared within it, so neighbours of the chunk's
        // episodes are decoded once per message, not once per pairing.
        Map<UUID, List<ChromaFingerprinter.Fingerprint>> introPrints = new HashMap<>();
        Map<UUID, List<ChromaFingerprinter.Fingerprint>> outroPrints = new HashMap<>();
        Map<UUID, List<MediaFileSegmentEntity>> segmentsByFile = new LinkedHashMap<>();
        int minSupport = slices.size() == 2 ? 1 : 2;
        List<ChromaFingerprinter.Fingerprint> templatePrints = introTemplatePrints(slices);

        ActivityContext.step("fingerprint");
        for (EpisodeSlice slice : workSet) {
            List<MediaFileSegmentEntity> rows =
                    segmentsByFile.computeIfAbsent(slice.mediaFile().getId(), id -> new ArrayList<>());
            Optional<Segment> intro = templatePrints.isEmpty()
                    ? pairwiseIntro(slice, slices, minSupport, introPrints, hopMs)
                    : templateIntro(slice, templatePrints, introPrints, hopMs);
            intro.ifPresent(run -> rows.add(row(slice, SegmentType.INTRO,
                    slice.sliceStartMs() + run.startMs(), slice.sliceStartMs() + run.endMs())));
            pairwiseOutro(slice, slices, minSupport, outroPrints, hopMs).ifPresent(outro -> {
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
        if (remaining == 0) {
            retryMissedIntros(slices, hopMs);
        }
        String directoryName = remaining > 0
                ? directoryRepository.findById(directoryEntityId).map(DirectoryEntity::getName).orElse(null)
                : null;
        return new Chunk(workSet.size(), remaining, directoryName);
    }

    /** Intro from up to {@link #MAX_NEIGHBOURS} neighbour pairings, median-aggregated. */
    private Optional<Segment> pairwiseIntro(EpisodeSlice slice, List<EpisodeSlice> slices, int minSupport,
                                            Map<UUID, List<ChromaFingerprinter.Fingerprint>> introPrints,
                                            long hopMs) {
        ChromaFingerprinter.Fingerprint own = introPrints(introPrints, slice).getFirst();
        List<Segment> candidates = new ArrayList<>();
        for (EpisodeSlice neighbour : neighboursOf(slices, slice)) {
            bestOverPhases(own, introPrints(introPrints, neighbour), hopMs)
                    .filter(run -> acceptIntro(run, slice.sliceLengthMs()))
                    .ifPresent(candidates::add);
        }
        return SegmentMatcher.aggregate(candidates, minSupport);
    }

    /** Outro from up to {@link #MAX_NEIGHBOURS} neighbour pairings, median-aggregated. */
    private Optional<Segment> pairwiseOutro(EpisodeSlice slice, List<EpisodeSlice> slices, int minSupport,
                                            Map<UUID, List<ChromaFingerprinter.Fingerprint>> outroPrints,
                                            long hopMs) {
        ChromaFingerprinter.Fingerprint own = outroPrints(outroPrints, slice).getFirst();
        List<Segment> candidates = new ArrayList<>();
        for (EpisodeSlice neighbour : neighboursOf(slices, slice)) {
            bestOverPhases(own, outroPrints(outroPrints, neighbour), hopMs)
                    .filter(run -> acceptOutro(run, outroWindowLengthMs(slice)))
                    .ifPresent(candidates::add);
        }
        return SegmentMatcher.aggregate(candidates, minSupport);
    }

    /** The slice's intro-window audio matched against every template phase; longest run wins. */
    private Optional<Segment> templateIntro(EpisodeSlice slice,
                                            List<ChromaFingerprinter.Fingerprint> templatePrints,
                                            Map<UUID, List<ChromaFingerprinter.Fingerprint>> introPrints,
                                            long hopMs) {
        ChromaFingerprinter.Fingerprint own = introPrints(introPrints, slice).getFirst();
        return bestOverPhases(own, templatePrints, hopMs)
                .filter(run -> acceptTemplateIntro(run, slice.sliceLengthMs()));
    }

    /**
     * The season's confirmed intros as template fingerprints, or empty while fewer than
     * {@link #TEMPLATE_SUPPORT} episodes carry one. Up to {@link #TEMPLATE_COUNT} intros spread
     * over the season (first, middle, last) are decoded, each at every phase shift, so a season
     * that switches intro variants contributes more than one variant.
     */
    private List<ChromaFingerprinter.Fingerprint> introTemplatePrints(List<EpisodeSlice> slices) {
        List<MediaFileSegmentEntity> intros = confirmedIntros(slices);
        if (intros.size() < TEMPLATE_SUPPORT) {
            return List.of();
        }
        List<ChromaFingerprinter.Fingerprint> prints = new ArrayList<>();
        for (MediaFileSegmentEntity intro : List.of(intros.getFirst(),
                intros.get(intros.size() / 2), intros.getLast())) {
            MediaFileEntity file = mediaFileRepository.findById(intro.getMediaFileEntityId()).orElse(null);
            if (file == null) {
                continue;
            }
            short[] pcm = audioPcmReader.readMonoPcm(Path.of(file.getPath()), dirOfFFmpeg,
                    intro.getStartInMilliseconds(),
                    intro.getEndInMilliseconds() - intro.getStartInMilliseconds());
            prints.addAll(phasePrints(pcm));
        }
        return prints;
    }

    /** The confirmed intro of every already-detected slice, in season order. */
    private List<MediaFileSegmentEntity> confirmedIntros(List<EpisodeSlice> slices) {
        List<UUID> detectedFileIds = slices.stream()
                .filter(s -> !needsDetection(s.mediaFile()))
                .map(s -> s.mediaFile().getId())
                .distinct()
                .toList();
        if (detectedFileIds.isEmpty()) {
            return List.of();
        }
        Map<UUID, List<MediaFileSegmentEntity>> byFile = new HashMap<>();
        mediaFileSegmentRepository.findByMediaFileEntityIdIn(detectedFileIds)
                .forEach(seg -> byFile.computeIfAbsent(seg.getMediaFileEntityId(),
                        id -> new ArrayList<>()).add(seg));
        List<MediaFileSegmentEntity> intros = new ArrayList<>();
        for (EpisodeSlice slice : slices) {
            if (!needsDetection(slice.mediaFile())) {
                introOf(byFile, slice).ifPresent(intros::add);
            }
        }
        return intros;
    }

    /**
     * Second pass over a finished season: episodes whose pairwise stage found no intro get one
     * template try. Runs inside the last chunk's transaction — template matching is cheap, and the
     * failures it re-decodes are a minority of the season.
     */
    private void retryMissedIntros(List<EpisodeSlice> slices, long hopMs) {
        List<ChromaFingerprinter.Fingerprint> templatePrints = introTemplatePrints(slices);
        if (templatePrints.isEmpty()) {
            return;
        }
        Map<UUID, List<MediaFileSegmentEntity>> byFile = new HashMap<>();
        mediaFileSegmentRepository.findByMediaFileEntityIdIn(slices.stream()
                        .map(s -> s.mediaFile().getId()).distinct().toList())
                .forEach(seg -> byFile.computeIfAbsent(seg.getMediaFileEntityId(),
                        id -> new ArrayList<>()).add(seg));
        Map<UUID, List<ChromaFingerprinter.Fingerprint>> introPrints = new HashMap<>();
        for (EpisodeSlice slice : slices) {
            if (introOf(byFile, slice).isPresent()) {
                continue;
            }
            templateIntro(slice, templatePrints, introPrints, hopMs).ifPresent(run -> {
                MediaFileSegmentEntity row = row(slice, SegmentType.INTRO,
                        slice.sliceStartMs() + run.startMs(), slice.sliceStartMs() + run.endMs());
                mediaFileSegmentRepository.save(row);
                log.info("Template retry found an intro for {}: {}..{} ms",
                        slice.mediaFile().getPath(), row.getStartInMilliseconds(),
                        row.getEndInMilliseconds());
            });
        }
    }

    private static Optional<MediaFileSegmentEntity> introOf(
            Map<UUID, List<MediaFileSegmentEntity>> byFile, EpisodeSlice slice) {
        return byFile.getOrDefault(slice.mediaFile().getId(), List.of()).stream()
                .filter(seg -> seg.getType() == SegmentType.INTRO)
                .filter(seg -> seg.getEpisodeEntityId() == null
                        || seg.getEpisodeEntityId().equals(slice.episodeId()))
                .findFirst();
    }

    /** The longest run over every phase variant of the other side. */
    private static Optional<Segment> bestOverPhases(ChromaFingerprinter.Fingerprint own,
                                                    List<ChromaFingerprinter.Fingerprint> others,
                                                    long hopMs) {
        Segment best = null;
        for (ChromaFingerprinter.Fingerprint other : others) {
            Segment run = SegmentMatcher.longestCommonRun(own, other, hopMs).orElse(null);
            if (run != null && (best == null || run.lengthMs() > best.lengthMs())) {
                best = run;
            }
        }
        return Optional.ofNullable(best);
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

    /**
     * Pending slices reordered from the season's ends inward (first, last, second,
     * second-to-last, …), so confirmed intros accumulate from both ends before the template stage
     * takes over — a season that switches intro variants midway then seeds templates from both
     * sides. Slices of one multi-episode file stay adjacent, for {@link #chunkOf}'s stretching.
     */
    static List<EpisodeSlice> endsInward(List<EpisodeSlice> pending) {
        List<List<EpisodeSlice>> groups = new ArrayList<>();
        for (EpisodeSlice slice : pending) {
            if (!groups.isEmpty() && groups.getLast().getFirst().mediaFile().getId()
                    .equals(slice.mediaFile().getId())) {
                groups.getLast().add(slice);
            } else {
                groups.add(new ArrayList<>(List.of(slice)));
            }
        }
        List<EpisodeSlice> ordered = new ArrayList<>(pending.size());
        int lo = 0;
        int hi = groups.size() - 1;
        while (lo <= hi) {
            ordered.addAll(groups.get(lo++));
            if (lo <= hi) {
                ordered.addAll(groups.get(hi--));
            }
        }
        return ordered;
    }

    /** True when detection (at the current version) never ran for the file. */
    static boolean needsDetection(MediaFileEntity mediaFile) {
        return mediaFile.getSegmentDetectorVersion() == null
                || mediaFile.getSegmentDetectorVersion() < DETECTOR_VERSION;
    }

    static boolean acceptIntro(Segment run, long sliceLengthMs) {
        return run.lengthMs() >= INTRO_MIN_MS && withinIntroBounds(run, sliceLengthMs);
    }

    static boolean acceptTemplateIntro(Segment run, long sliceLengthMs) {
        return run.lengthMs() >= TEMPLATE_INTRO_MIN_MS && withinIntroBounds(run, sliceLengthMs);
    }

    private static boolean withinIntroBounds(Segment run, long sliceLengthMs) {
        long maxLength = Math.min(INTRO_MAX_MS, sliceLengthMs / 4);
        // The start cap is also relative: on episodes shorter than twice
        // INTRO_MAX_START the shared *outro* would otherwise qualify as an intro.
        long maxStart = Math.min(INTRO_MAX_START_MS, sliceLengthMs / 2);
        return run.lengthMs() <= maxLength && run.startMs() <= maxStart;
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

    private List<ChromaFingerprinter.Fingerprint> introPrints(
            Map<UUID, List<ChromaFingerprinter.Fingerprint>> cache, EpisodeSlice slice) {
        return cache.computeIfAbsent(slice.episodeId(), id -> phasePrints(
                audioPcmReader.readMonoPcm(Path.of(slice.mediaFile().getPath()), dirOfFFmpeg,
                        slice.sliceStartMs(), Math.min(INTRO_WINDOW_MS, slice.sliceLengthMs()))));
    }

    private List<ChromaFingerprinter.Fingerprint> outroPrints(
            Map<UUID, List<ChromaFingerprinter.Fingerprint>> cache, EpisodeSlice slice) {
        return cache.computeIfAbsent(slice.episodeId(), id -> phasePrints(
                audioPcmReader.readMonoPcm(Path.of(slice.mediaFile().getPath()), dirOfFFmpeg,
                        slice.sliceEndMs() - outroWindowLengthMs(slice), outroWindowLengthMs(slice))));
    }

    private static long outroWindowLengthMs(EpisodeSlice slice) {
        return Math.min(OUTRO_WINDOW_MS, slice.sliceLengthMs());
    }

    /** One fingerprint per phase shift; the unshifted one first. Audio is decoded once. */
    static List<ChromaFingerprinter.Fingerprint> phasePrints(short[] pcm) {
        List<ChromaFingerprinter.Fingerprint> prints = new ArrayList<>(PHASE_SHIFTS_SAMPLES.length);
        for (int shift : PHASE_SHIFTS_SAMPLES) {
            short[] shifted = shift == 0 ? pcm
                    : Arrays.copyOfRange(pcm, Math.min(shift, pcm.length), pcm.length);
            prints.add(ChromaFingerprinter.fingerprint(shifted, AudioPcmReader.SAMPLE_RATE));
        }
        return prints;
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
