package app.ister.disk.events.detectsegments;

import app.ister.core.entity.DirectoryEntity;
import app.ister.core.entity.EpisodeEntity;
import app.ister.core.entity.MediaFileEntity;
import app.ister.core.entity.MediaFileSegmentEntity;
import app.ister.core.enums.EventType;
import app.ister.core.enums.SegmentType;
import app.ister.core.eventdata.DetectSegmentsData;
import app.ister.core.repository.DirectoryRepository;
import app.ister.core.repository.EpisodeRepository;
import app.ister.core.repository.MediaFileRepository;
import app.ister.core.repository.MediaFileSegmentRepository;
import app.ister.core.service.MediaFileEpisodeService;
import app.ister.core.service.MessageSender;
import app.ister.disk.events.detectsegments.SegmentDetectionChunkProcessor.EpisodeSlice;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class HandleDetectSegmentsTest {

    private static final UUID SEASON_ID = UUID.randomUUID();
    private static final UUID DIRECTORY_ID = UUID.randomUUID();
    private static final long INTRO_MS = 30_000;
    private static final long MIDDLE_MS = 320_000;
    private static final long OUTRO_MS = 25_000;
    private static final long EPISODE_MS = INTRO_MS + MIDDLE_MS + OUTRO_MS;

    @Mock private EpisodeRepository episodeRepository;
    @Mock private MediaFileRepository mediaFileRepository;
    @Mock private MediaFileSegmentRepository mediaFileSegmentRepository;
    @Mock private MediaFileEpisodeService mediaFileEpisodeService;
    @Mock private DirectoryRepository directoryRepository;
    @Mock private MessageSender messageSender;

    private HandleDetectSegments subject;
    private final List<EpisodeEntity> episodes = new java.util.ArrayList<>();
    private final List<MediaFileEntity> files = new java.util.ArrayList<>();
    private boolean pcmRead;

    /**
     * Three "episodes" of synthetic audio sharing a 30 s intro melody and a 25 s outro melody
     * around distinct middles, served straight from memory instead of ffmpeg.
     */
    @BeforeEach
    void setUp() {
        AudioPcmReader fakeReader = new AudioPcmReader() {
            @Override
            public short[] readMonoPcm(Path mediaFilePath, String dirOfFFmpeg, long offsetMs, long durationMs) {
                pcmRead = true;
                int episode = Integer.parseInt(mediaFilePath.getFileName().toString().replace(".wav", ""));
                return window(episodeAudio(episode), offsetMs, durationMs);
            }
        };
        SegmentDetectionChunkProcessor processor = new SegmentDetectionChunkProcessor(episodeRepository,
                mediaFileRepository, mediaFileSegmentRepository, mediaFileEpisodeService,
                directoryRepository, fakeReader);
        ReflectionTestUtils.setField(processor, "dirOfFFmpeg", "/usr/bin");
        subject = new HandleDetectSegments(processor, messageSender);
        ReflectionTestUtils.setField(subject, "chunkSize", 10);

        for (int i = 0; i < 3; i++) {
            MediaFileEntity file = MediaFileEntity.builder()
                    .path("/shows/show/" + i + ".wav")
                    .size(1)
                    .build();
            ReflectionTestUtils.setField(file, "id", UUID.randomUUID());
            ReflectionTestUtils.setField(file, "directoryEntityId", DIRECTORY_ID);
            file.setDurationInMilliseconds(EPISODE_MS);
            files.add(file);

            EpisodeEntity episode = EpisodeEntity.builder().number(i + 1).build();
            ReflectionTestUtils.setField(episode, "id", UUID.randomUUID());
            episodes.add(episode);
            lenient().when(mediaFileEpisodeService.filesForEpisode(episode.getId())).thenReturn(List.of(file));
            lenient().when(mediaFileEpisodeService.segmentFor(file.getId(), episode.getId())).thenReturn(Optional.empty());
        }
        lenient().when(episodeRepository.findBySeasonEntityIdOrderByNumberAsc(SEASON_ID)).thenReturn(episodes);
        lenient().when(mediaFileSegmentRepository.tryLockSeason(anyInt(), any())).thenReturn(true);
    }

    @Test
    void detectsTheSharedIntroAndOutroAndStampsTheVersion() {
        subject.handle(event());

        ArgumentCaptor<List<MediaFileSegmentEntity>> captor = ArgumentCaptor.captor();
        verify(mediaFileSegmentRepository, times(3)).saveAll(captor.capture());
        for (List<MediaFileSegmentEntity> rows : captor.getAllValues()) {
            MediaFileSegmentEntity intro = rows.stream().filter(r -> r.getType() == SegmentType.INTRO)
                    .findFirst().orElseThrow();
            assertEquals(0, intro.getStartInMilliseconds());
            assertTrue(Math.abs(intro.getEndInMilliseconds() - INTRO_MS) <= 2_000,
                    "intro end was " + intro.getEndInMilliseconds());
            MediaFileSegmentEntity outro = rows.stream().filter(r -> r.getType() == SegmentType.OUTRO)
                    .findFirst().orElseThrow();
            assertTrue(Math.abs(outro.getStartInMilliseconds() - (INTRO_MS + MIDDLE_MS)) <= 2_000,
                    "outro start was " + outro.getStartInMilliseconds());
            assertTrue(outro.getEndInMilliseconds() <= EPISODE_MS);
            // Single-episode files carry no episode disambiguation.
            rows.forEach(r -> assertEquals(null, r.getEpisodeEntityId()));
        }
        files.forEach(f -> assertEquals(SegmentDetectionChunkProcessor.DETECTOR_VERSION, f.getSegmentDetectorVersion()));
        verify(mediaFileRepository, times(3)).save(any());
        // Everything fit in one chunk, so no successor message.
        verify(messageSender, never()).sendDetectSegments(any(), anyString());
    }

    @Test
    void aChunkSmallerThanTheSeasonPublishesASuccessorForTheRest() {
        ReflectionTestUtils.setField(subject, "chunkSize", 2);
        when(directoryRepository.findById(DIRECTORY_ID))
                .thenReturn(Optional.of(DirectoryEntity.builder().name("disk1").build()));

        subject.handle(event());

        // Only the chunk's two episodes are detected and stamped; the third stays pending.
        verify(mediaFileSegmentRepository, times(2)).saveAll(any());
        assertEquals(SegmentDetectionChunkProcessor.DETECTOR_VERSION, files.get(0).getSegmentDetectorVersion());
        assertEquals(SegmentDetectionChunkProcessor.DETECTOR_VERSION, files.get(1).getSegmentDetectorVersion());
        assertEquals(null, files.get(2).getSegmentDetectorVersion());

        ArgumentCaptor<DetectSegmentsData> successor = ArgumentCaptor.captor();
        verify(messageSender).sendDetectSegments(successor.capture(), eq("disk1"));
        assertEquals(SEASON_ID, successor.getValue().getSeasonEntityUUID());
        assertEquals(DIRECTORY_ID, successor.getValue().getDirectoryEntityUUID());
        assertEquals(EventType.DETECT_SEGMENTS, successor.getValue().getEventType());
    }

    @Test
    void aFailedDecodeStillStampsTheVersionSoTheChainTerminates() {
        AudioPcmReader silentReader = new AudioPcmReader() {
            @Override
            public short[] readMonoPcm(Path mediaFilePath, String dirOfFFmpeg, long offsetMs, long durationMs) {
                return new short[0]; // What the reader returns when ffmpeg fails.
            }
        };
        SegmentDetectionChunkProcessor processor = new SegmentDetectionChunkProcessor(episodeRepository,
                mediaFileRepository, mediaFileSegmentRepository, mediaFileEpisodeService,
                directoryRepository, silentReader);
        ReflectionTestUtils.setField(processor, "dirOfFFmpeg", "/usr/bin");
        subject = new HandleDetectSegments(processor, messageSender);
        ReflectionTestUtils.setField(subject, "chunkSize", 10);

        subject.handle(event());

        files.forEach(f -> assertEquals(SegmentDetectionChunkProcessor.DETECTOR_VERSION, f.getSegmentDetectorVersion()));
        verify(messageSender, never()).sendDetectSegments(any(), anyString());
    }

    @Test
    void aSeasonAlreadyAtTheCurrentVersionDoesNoAudioWork() {
        files.forEach(f -> f.setSegmentDetectorVersion(SegmentDetectionChunkProcessor.DETECTOR_VERSION));

        subject.handle(event());

        assertFalse(pcmRead, "no PCM should be decoded for an already-detected season");
        verify(mediaFileSegmentRepository, never()).saveAll(any());
        verify(messageSender, never()).sendDetectSegments(any(), anyString());
    }

    @Test
    void aSeasonClaimedByAnotherTransactionIsSkippedEntirely() {
        when(mediaFileSegmentRepository.tryLockSeason(anyInt(), eq(SEASON_ID))).thenReturn(false);

        subject.handle(event());

        assertFalse(pcmRead, "the holder of the lock is already doing this season's audio work");
        verify(mediaFileSegmentRepository, never()).deleteAllByMediaFileEntityId(any());
        verify(mediaFileSegmentRepository, never()).saveAll(any());
        files.forEach(f -> assertEquals(null, f.getSegmentDetectorVersion()));
        // No successor either: the transaction that holds the season keeps the chain going.
        verify(messageSender, never()).sendDetectSegments(any(), anyString());
    }

    @Test
    void aSingleLocalEpisodeIsLeftPendingForALaterTrigger() {
        when(episodeRepository.findBySeasonEntityIdOrderByNumberAsc(SEASON_ID))
                .thenReturn(List.of(episodes.getFirst()));

        subject.handle(event());

        assertFalse(pcmRead);
        // Version must NOT be stamped: a later sibling's trigger should retry this episode.
        assertEquals(null, files.getFirst().getSegmentDetectorVersion());
        verify(mediaFileSegmentRepository, never()).saveAll(any());
        verify(messageSender, never()).sendDetectSegments(any(), anyString());
    }

    @Test
    void aChunkNeverSplitsAMultiEpisodeFile() {
        MediaFileEntity fileA = fileWithId();
        MediaFileEntity fileB = fileWithId();
        MediaFileEntity fileC = fileWithId();
        List<EpisodeSlice> pending = List.of(
                new EpisodeSlice(fileA, UUID.randomUUID(), false, 0, 1),
                new EpisodeSlice(fileB, UUID.randomUUID(), true, 0, 1),
                new EpisodeSlice(fileB, UUID.randomUUID(), true, 1, 2),
                new EpisodeSlice(fileC, UUID.randomUUID(), false, 0, 1));

        // A boundary inside fileB is stretched past its last slice…
        assertEquals(3, SegmentDetectionChunkProcessor.chunkOf(pending, 2).size());
        // …while boundaries on file edges are left alone.
        assertEquals(1, SegmentDetectionChunkProcessor.chunkOf(pending, 1).size());
        assertEquals(3, SegmentDetectionChunkProcessor.chunkOf(pending, 3).size());
        assertEquals(4, SegmentDetectionChunkProcessor.chunkOf(pending, 9).size());
    }

    @Test
    void acceptanceWindows() {
        assertTrue(SegmentDetectionChunkProcessor.acceptIntro(seg(0, 30_000), 40 * 60_000L));
        assertFalse(SegmentDetectionChunkProcessor.acceptIntro(seg(0, 10_000), 40 * 60_000L), "too short");
        assertFalse(SegmentDetectionChunkProcessor.acceptIntro(seg(0, 160_000), 40 * 60_000L), "too long");
        assertFalse(SegmentDetectionChunkProcessor.acceptIntro(seg(0, 100_000), 300_000), "over 25% of the episode");
        assertFalse(SegmentDetectionChunkProcessor.acceptIntro(seg(360_000, 390_000), 40 * 60_000L), "starts too late");
        assertFalse(SegmentDetectionChunkProcessor.acceptIntro(seg(267_000, 297_000), 300_000),
            "a run in the back half of a short episode is the outro, not the intro");

        assertTrue(SegmentDetectionChunkProcessor.acceptOutro(seg(200_000, 240_000), 240_000));
        assertFalse(SegmentDetectionChunkProcessor.acceptOutro(seg(200_000, 215_000), 240_000), "too short");
        assertFalse(SegmentDetectionChunkProcessor.acceptOutro(seg(0, 30_000), 240_000), "ends too early");
    }

    @Test
    void neighboursAreTheNearestEpisodes() {
        List<EpisodeSlice> slices = new java.util.ArrayList<>();
        for (int i = 0; i < 7; i++) {
            slices.add(new EpisodeSlice(mock(MediaFileEntity.class), UUID.randomUUID(), false, 0, 1));
        }
        List<EpisodeSlice> neighbours = SegmentDetectionChunkProcessor.neighboursOf(slices, slices.get(3));
        assertEquals(List.of(slices.get(2), slices.get(4), slices.get(1), slices.get(5)), neighbours);
        assertEquals(2, SegmentDetectionChunkProcessor.neighboursOf(slices.subList(0, 3), slices.get(0)).size());
    }

    private static MediaFileEntity fileWithId() {
        MediaFileEntity file = MediaFileEntity.builder().path("/x").size(1).build();
        ReflectionTestUtils.setField(file, "id", UUID.randomUUID());
        return file;
    }

    private static SegmentMatcher.Segment seg(long start, long end) {
        return new SegmentMatcher.Segment(start, end);
    }

    private static DetectSegmentsData event() {
        return DetectSegmentsData.builder()
                .eventType(EventType.DETECT_SEGMENTS)
                .seasonEntityUUID(SEASON_ID)
                .directoryEntityUUID(DIRECTORY_ID)
                .build();
    }

    private static short[] episodeAudio(int episode) {
        short[] intro = ChromaFingerprinterTest.melody(INTRO_MS, 1);
        short[] middle = ChromaFingerprinterTest.melody(MIDDLE_MS, 100 + episode);
        short[] outro = ChromaFingerprinterTest.melody(OUTRO_MS, 2);
        short[] all = new short[intro.length + middle.length + outro.length];
        System.arraycopy(intro, 0, all, 0, intro.length);
        System.arraycopy(middle, 0, all, intro.length, middle.length);
        System.arraycopy(outro, 0, all, intro.length + middle.length, outro.length);
        return all;
    }

    private static short[] window(short[] pcm, long offsetMs, long durationMs) {
        int from = (int) (offsetMs * AudioPcmReader.SAMPLE_RATE / 1000);
        int to = Math.min(pcm.length, from + (int) (durationMs * AudioPcmReader.SAMPLE_RATE / 1000));
        return java.util.Arrays.copyOfRange(pcm, from, to);
    }
}
