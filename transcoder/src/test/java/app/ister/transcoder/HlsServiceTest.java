package app.ister.transcoder;

import app.ister.core.entity.DirectoryEntity;
import app.ister.core.entity.MediaFileEntity;
import app.ister.core.entity.MediaFileStreamEntity;
import app.ister.core.entity.NodeEntity;
import app.ister.core.enums.DirectoryType;
import app.ister.core.enums.EventType;
import app.ister.core.enums.StreamCodecType;
import app.ister.core.enums.SubtitleFormat;
import app.ister.core.eventdata.TranscodePassRequestedData;
import app.ister.core.repository.MediaFileRepository;
import app.ister.core.repository.MediaFileStreamRepository;
import app.ister.core.service.MessageSender;
import app.ister.core.utils.Jaffree;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import com.github.kokorin.jaffree.ffmpeg.FFmpeg;
import com.github.kokorin.jaffree.ffmpeg.FFmpegResultFuture;
import com.github.kokorin.jaffree.ffmpeg.Input;
import com.github.kokorin.jaffree.ffmpeg.Output;
import com.github.kokorin.jaffree.process.Stopper;

import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.mockito.Mockito.RETURNS_SELF;

@ExtendWith(MockitoExtension.class)
class HlsServiceTest {

    private static final String LOCAL_NODE_NAME = "test-node";

    @Mock private Jaffree jaffree;
    @Mock private FfprobeService ffprobeService;
    @Mock private MediaFileRepository mediaFileRepository;
    @Mock private MediaFileStreamRepository mediaFileStreamRepository;
    @Mock private MessageSender messageSender;
    @Mock private RemoteNodeClient remoteNodeClient;
    @Mock private NodeTokenManager nodeTokenManager;
    @Mock private org.springframework.amqp.core.AmqpAdmin amqpAdmin;
    // With a mocked manager the TransactionTemplate executes its callback inline.
    @Mock private org.springframework.transaction.PlatformTransactionManager transactionManager;

    @TempDir
    Path tempDir;

    private HlsPlaylistBuilder playlistBuilder;
    private HlsSubtitleService subtitleService;
    private HlsTranscodeService transcodeService;
    private HlsService hlsService;

    @BeforeEach
    void setUp() {
        playlistBuilder = new HlsPlaylistBuilder();
        subtitleService = new HlsSubtitleService(jaffree, ffprobeService);
        transcodeService = new HlsTranscodeService(jaffree, ffprobeService);
        ReflectionTestUtils.setField(transcodeService, "tmpDir", tempDir.toString());
        ReflectionTestUtils.setField(transcodeService, "segmentTimeoutMs", 5000L);
        ReflectionTestUtils.setField(transcodeService, "maxConcurrentFiles", 10);
        ReflectionTestUtils.setField(transcodeService, "maxConcurrentPasses", 4);
        ReflectionTestUtils.setField(transcodeService, "maxBackgroundFiles", 10);
        ReflectionTestUtils.setField(transcodeService, "maxBackgroundPasses", 4);
        ReflectionTestUtils.setField(transcodeService, "segmentStabilityMs", 200L);
        ReflectionTestUtils.setField(transcodeService, "passTimeoutMultiplier", 4.0);
        ReflectionTestUtils.setField(transcodeService, "passTimeoutMinSeconds", 1800L);
        ReflectionTestUtils.setField(transcodeService, "cacheRetentionHours", 2L);
        transcodeService.init();
        ReflectionTestUtils.setField(transcodeService, "concurrentFileSlots", new Semaphore(10));
        // By default the transcode queue for the media file's directory exists.
        lenient().when(amqpAdmin.getQueueProperties(anyString())).thenReturn(new java.util.Properties());
        hlsService = new HlsService(playlistBuilder, subtitleService, transcodeService,
                mediaFileRepository, mediaFileStreamRepository, messageSender,
                remoteNodeClient, nodeTokenManager, amqpAdmin, transactionManager);
        ReflectionTestUtils.setField(hlsService, "tmpDir", tempDir.toString());
        ReflectionTestUtils.setField(hlsService, "localNodeName", LOCAL_NODE_NAME);
        ReflectionTestUtils.setField(hlsService, "uploadDrainTimeoutMs", 5000L);
    }

    @AfterEach
    void tearDown() throws InterruptedException {
        // Pass threads must not outlive the test: a lingering pass writes segments/done markers
        // into the @TempDir while JUnit deletes it, failing the cleanup of this or a later test.
        transcodeService.transcodeExecutor.shutdown();
        if (!transcodeService.transcodeExecutor.awaitTermination(10, TimeUnit.SECONDS)) {
            transcodeService.transcodeExecutor.shutdownNow();
            assertTrue(transcodeService.transcodeExecutor.awaitTermination(5, TimeUnit.SECONDS),
                    "transcode pass threads did not terminate");
        }
    }

    private static final Stopper NOOP_STOPPER = new Stopper() {
        @Override public void graceStop() { /* no-op: test double never runs a real process */ }
        @Override public void forceStop() { /* no-op: test double never runs a real process */ }
        @Override public void setProcess(Process process) { /* no-op: test double never runs a real process */ }
    };

    private static FFmpegResultFuture completedFFmpegFuture() {
        return new FFmpegResultFuture(CompletableFuture.completedFuture(null), NOOP_STOPPER);
    }

    /**
     * Completes shortly after being returned. An instantly-completed future can run its
     * completion (which hands the background-pass budget to the next dropped pass) before the
     * other passes even registered themselves for resume, killing the hand-off chain — a race
     * that cannot happen with real multi-minute ffmpeg passes, but that flaked this suite on
     * slow CI runners.
     */
    private static FFmpegResultFuture delayedFFmpegFuture() {
        CompletableFuture<FFmpegResult> future = new CompletableFuture<>();
        CompletableFuture.delayedExecutor(250, TimeUnit.MILLISECONDS).execute(() -> future.complete(null));
        return new FFmpegResultFuture(future, NOOP_STOPPER);
    }

    private static void writeIfAbsent(Path file, String content) throws IOException {
        try {
            Files.writeString(file, content, StandardOpenOption.CREATE_NEW);
        } catch (FileAlreadyExistsException _) {
            // another pass already wrote it — leave it untouched
        }
    }

    private static FFmpegResultFuture failedFFmpegFuture(Throwable cause) {
        return new FFmpegResultFuture(CompletableFuture.failedFuture(cause), NOOP_STOPPER);
    }

    /** The FFmpeg arguments of the single input the service handed to FFmpeg. */
    private static List<String> inputArgsOf(FFmpeg ffmpegMock) {
        ArgumentCaptor<Input> captor = ArgumentCaptor.forClass(Input.class);
        verify(ffmpegMock).addInput(captor.capture());
        return captor.getValue().buildArguments();
    }

    /** The FFmpeg arguments of the single output the service handed to FFmpeg. */
    private static List<String> outputArgsOf(FFmpeg ffmpegMock) {
        ArgumentCaptor<Output> captor = ArgumentCaptor.forClass(Output.class);
        verify(ffmpegMock).addOutput(captor.capture());
        return captor.getValue().buildArguments();
    }

    private static void assertContainsSequence(List<String> args, String... expected) {
        assertTrue(Collections.indexOfSubList(args, List.of(expected)) >= 0,
                () -> args + " should contain " + List.of(expected));
    }

    // ========== Master playlist ==========

    @Test
    void getMasterPlaylistDirectOnly() throws IOException {
        UUID id = UUID.randomUUID();
        MediaFileStreamEntity videoStream = videoStream(0, 1920, 1080);
        MediaFileStreamEntity audioStream = audioStream(1, "eng", "English");
        MediaFileEntity mediaFile = mediaFileEntity("/test/video.mkv", videoStream, audioStream);

        when(mediaFileRepository.findById(id)).thenReturn(Optional.of(mediaFile));
        when(ffprobeService.getKeyframes("/test/video.mkv")).thenReturn(List.of(0.0, 5.0));
        when(ffprobeService.getTotalDuration("/test/video.mkv")).thenReturn(10.0);

        hlsService.generateAllPlaylists(id, true, false, SubtitleFormat.WEBVTT);
        String master = hlsService.getMasterPlaylist(id, true, false, SubtitleFormat.WEBVTT);

        assertTrue(master.contains("#EXTM3U"));
        assertTrue(master.contains("stream_video_copy.m3u8"));
        assertFalse(master.contains("stream_video_720p.m3u8"));
        assertFalse(master.contains("stream_video_480p.m3u8"));
        assertTrue(master.contains("audio-copy"));
    }

    @Test
    void getMasterPlaylistTranscodeOnly() throws IOException {
        UUID id = UUID.randomUUID();
        MediaFileStreamEntity videoStream = videoStream(0, 1920, 1080);
        MediaFileStreamEntity audioStream = audioStream(1, "eng", "English");
        MediaFileEntity mediaFile = mediaFileEntity("/test/video.mkv", videoStream, audioStream);

        when(mediaFileRepository.findById(id)).thenReturn(Optional.of(mediaFile));
        when(ffprobeService.getKeyframes("/test/video.mkv")).thenReturn(List.of(0.0, 5.0));
        when(ffprobeService.getTotalDuration("/test/video.mkv")).thenReturn(10.0);

        hlsService.generateAllPlaylists(id, false, true, SubtitleFormat.WEBVTT);
        String master = hlsService.getMasterPlaylist(id, false, true, SubtitleFormat.WEBVTT);

        assertFalse(master.contains("stream_video_copy.m3u8"));
        assertTrue(master.contains("stream_video_720p.m3u8"));
        assertTrue(master.contains("stream_video_480p.m3u8"));
        // Both transcoded variants use the same audio group (192k)
        assertTrue(master.contains("audio-192k"));
        assertFalse(master.contains("audio-64k"));
    }

    @Test
    void getMasterPlaylistBothDirectAndTranscode() throws IOException {
        UUID id = UUID.randomUUID();
        MediaFileStreamEntity videoStream = videoStream(0, 1920, 1080);
        MediaFileStreamEntity audioStream = audioStream(1, "eng", "English");
        MediaFileEntity mediaFile = mediaFileEntity("/test/video.mkv", videoStream, audioStream);

        when(mediaFileRepository.findById(id)).thenReturn(Optional.of(mediaFile));
        when(ffprobeService.getKeyframes("/test/video.mkv")).thenReturn(List.of(0.0, 5.0));
        when(ffprobeService.getTotalDuration("/test/video.mkv")).thenReturn(10.0);

        hlsService.generateAllPlaylists(id, true, true, SubtitleFormat.WEBVTT);
        String master = hlsService.getMasterPlaylist(id, true, true, SubtitleFormat.WEBVTT);

        assertTrue(master.contains("stream_video_copy.m3u8"));
        assertTrue(master.contains("stream_video_720p.m3u8"));
        assertTrue(master.contains("stream_video_480p.m3u8"));
    }

    @Test
    void getMasterPlaylistIncludesSubtitleTracks() throws IOException {
        UUID id = UUID.randomUUID();
        UUID subtitleId = UUID.randomUUID();
        MediaFileStreamEntity videoStream = videoStream(0, 1920, 1080);
        MediaFileStreamEntity audioStream = audioStream(1, "eng", "English");
        MediaFileStreamEntity subtitleStream = subtitleStream(subtitleId, 2, "nld", "Nederlands");
        MediaFileEntity mediaFile = mediaFileEntity("/test/video.mkv", videoStream, audioStream, subtitleStream);

        when(mediaFileRepository.findById(id)).thenReturn(Optional.of(mediaFile));
        when(ffprobeService.getKeyframes("/test/video.mkv")).thenReturn(List.of(0.0, 5.0));
        when(ffprobeService.getTotalDuration("/test/video.mkv")).thenReturn(10.0);

        hlsService.generateAllPlaylists(id, true, false, SubtitleFormat.WEBVTT);
        String master = hlsService.getMasterPlaylist(id, true, false, SubtitleFormat.WEBVTT);

        assertTrue(master.contains("SUBTITLES=\"subs\""));
        assertTrue(master.contains("TYPE=SUBTITLES"));
        assertTrue(master.contains("stream_sub_" + subtitleId));
    }

    @Test
    void getMasterPlaylistIsCached() throws IOException {
        UUID id = UUID.randomUUID();
        MediaFileStreamEntity videoStream = videoStream(0, 1920, 1080);
        MediaFileStreamEntity audioStream = audioStream(1, "eng", "English");
        MediaFileEntity mediaFile = mediaFileEntity("/test/video.mkv", videoStream, audioStream);

        when(mediaFileRepository.findById(id)).thenReturn(Optional.of(mediaFile));
        when(ffprobeService.getKeyframes(any())).thenReturn(List.of(0.0, 5.0));
        when(ffprobeService.getTotalDuration(any())).thenReturn(10.0);

        hlsService.generateAllPlaylists(id, true, false, SubtitleFormat.WEBVTT);
        hlsService.getMasterPlaylist(id, true, false, SubtitleFormat.WEBVTT);
        hlsService.getMasterPlaylist(id, true, false, SubtitleFormat.WEBVTT);

        // ffprobe should only be called once (generateAllPlaylists call; subsequent calls hit cache)
        verify(ffprobeService, times(1)).getKeyframes(any());
    }

    // ========== Stream playlists ==========

    @Test
    void getStreamPlaylistForVideo720pUsesKeyframes() throws IOException {
        UUID id = UUID.randomUUID();
        when(mediaFileRepository.findById(id)).thenReturn(Optional.of(mediaFileEntity("/test/video.mkv")));
        when(ffprobeService.getKeyframes("/test/video.mkv")).thenReturn(List.of(0.0, 5.0, 10.0));
        when(ffprobeService.getTotalDuration("/test/video.mkv")).thenReturn(15.0);

        String playlist = hlsService.getStreamPlaylist(id, "stream_video_720p.m3u8");

        assertTrue(playlist.contains("seg_video_720p_00000.ts"));
        assertTrue(playlist.contains("seg_video_720p_00001.ts"));
        assertTrue(playlist.contains("seg_video_720p_00002.ts"));
        assertTrue(playlist.contains("#EXT-X-ENDLIST"));
    }

    @Test
    void getStreamPlaylistForAudioCopyReturnsSingleSegment() throws IOException {
        UUID id = UUID.randomUUID();
        when(mediaFileRepository.findById(id)).thenReturn(Optional.of(mediaFileEntity("/test/video.mkv")));
        when(ffprobeService.getTotalDuration("/test/video.mkv")).thenReturn(60.0);
        when(ffprobeService.getKeyframes("/test/video.mkv")).thenReturn(List.of(0.0));

        String playlist = hlsService.getStreamPlaylist(id, "stream_audio_1_copy.m3u8");

        assertTrue(playlist.contains("seg_audio_0.000000_60.000000_1_copy.ts"));
        assertTrue(playlist.contains("#EXT-X-ENDLIST"));
    }

    @Test
    void getStreamPlaylistForAudio192kUsesKeyframes() throws IOException {
        UUID id = UUID.randomUUID();
        when(mediaFileRepository.findById(id)).thenReturn(Optional.of(mediaFileEntity("/test/video.mkv")));
        when(ffprobeService.getKeyframes("/test/video.mkv")).thenReturn(List.of(0.0, 4.0));
        when(ffprobeService.getTotalDuration("/test/video.mkv")).thenReturn(8.0);

        String playlist = hlsService.getStreamPlaylist(id, "stream_audio_1_192k.m3u8");

        assertTrue(playlist.contains("seg_audio_1_192k_00000.ts"));
        assertTrue(playlist.contains("seg_audio_1_192k_00001.ts"));
    }

    @Test
    void getStreamPlaylistUnknownFilenameThrows() {
        UUID id = UUID.randomUUID();
        when(mediaFileRepository.findById(id)).thenReturn(Optional.of(mediaFileEntity("/test/video.mkv")));
        when(ffprobeService.getKeyframes(any())).thenReturn(List.of(0.0, 5.0));
        when(ffprobeService.getTotalDuration(any())).thenReturn(10.0);

        assertThrows(IllegalArgumentException.class,
                () -> hlsService.getStreamPlaylist(id, "stream_unknown_foo.m3u8"));
    }

    @Test
    void getStreamPlaylistIsCached() throws IOException {
        UUID id = UUID.randomUUID();
        when(mediaFileRepository.findById(id)).thenReturn(Optional.of(mediaFileEntity("/test/video.mkv")));
        when(ffprobeService.getKeyframes(any())).thenReturn(List.of(0.0, 5.0));
        when(ffprobeService.getTotalDuration(any())).thenReturn(10.0);

        hlsService.getStreamPlaylist(id, "stream_video_720p.m3u8");
        hlsService.getStreamPlaylist(id, "stream_video_720p.m3u8");

        verify(ffprobeService, times(1)).getKeyframes(any());
    }

    // ========== Subtitle segment (SRT to WebVTT) ==========

    @Test
    void getSubtitleSegmentConvertsBasicSrt() throws IOException {
        UUID mediaFileId = UUID.randomUUID();
        UUID subtitleId = UUID.randomUUID();
        Path srtFile = tempDir.resolve("sub.srt");
        Files.writeString(srtFile, "1\n00:00:01,000 --> 00:00:03,000\nHello World\n\n");

        MediaFileStreamEntity subtitleStream = MediaFileStreamEntity.builder()
                .codecType(StreamCodecType.EXTERNAL_SUBTITLE)
                .codecName("srt")
                .path(srtFile.toString())
                .width(0).height(0)
                .build();
        ReflectionTestUtils.setField(subtitleStream, "id", subtitleId);

        when(mediaFileStreamRepository.findById(subtitleId)).thenReturn(Optional.of(subtitleStream));
        when(mediaFileRepository.findById(mediaFileId)).thenReturn(Optional.of(mediaFileEntity("/test/video.mkv")));
        when(ffprobeService.getKeyframes("/test/video.mkv")).thenReturn(List.of(0.0));
        when(ffprobeService.getTotalDuration("/test/video.mkv")).thenReturn(10.0);

        String segmentFilename = String.format("seg_sub_%s_00000.vtt", subtitleId);
        String vtt = hlsService.getSubtitleSegment(mediaFileId, segmentFilename);

        assertTrue(vtt.startsWith("WEBVTT"));
        // Timestamps are shifted by SUBTITLE_OFFSET_MS (1480 ms)
        assertTrue(vtt.contains("00:00:02.480 --> 00:00:04.480"));
        assertTrue(vtt.contains("Hello World"));
    }

    @Test
    void getSubtitleSegmentExcludesEntriesOutsideWindow() throws IOException {
        UUID mediaFileId = UUID.randomUUID();
        UUID subtitleId = UUID.randomUUID();
        Path srtFile = tempDir.resolve("sub.srt");
        Files.writeString(srtFile, """
                1
                00:00:01,000 --> 00:00:03,000
                Inside

                2
                00:00:20,000 --> 00:00:25,000
                Outside

                """);

        MediaFileStreamEntity subtitleStream = MediaFileStreamEntity.builder()
                .codecType(StreamCodecType.EXTERNAL_SUBTITLE)
                .codecName("srt")
                .path(srtFile.toString())
                .width(0).height(0)
                .build();
        ReflectionTestUtils.setField(subtitleStream, "id", subtitleId);

        when(mediaFileStreamRepository.findById(subtitleId)).thenReturn(Optional.of(subtitleStream));
        when(mediaFileRepository.findById(mediaFileId)).thenReturn(Optional.of(mediaFileEntity("/test/video.mkv")));
        // Two segments: 0-10s and 10-30s; "Inside" is in segment 0, "Outside" is in segment 1
        when(ffprobeService.getKeyframes("/test/video.mkv")).thenReturn(List.of(0.0, 10.0));
        when(ffprobeService.getTotalDuration("/test/video.mkv")).thenReturn(30.0);

        String segmentFilename = String.format("seg_sub_%s_00000.vtt", subtitleId);
        String vtt = hlsService.getSubtitleSegment(mediaFileId, segmentFilename);

        assertTrue(vtt.contains("Inside"));
        assertFalse(vtt.contains("Outside"));
    }

    @Test
    void getSubtitleSegmentFormatsVttTimestampsCorrectly() throws IOException {
        UUID mediaFileId = UUID.randomUUID();
        UUID subtitleId = UUID.randomUUID();
        Path srtFile = tempDir.resolve("sub.srt");
        // 1h2m3s,456ms
        Files.writeString(srtFile, "1\n01:02:03,456 --> 01:02:05,789\nText\n\n");

        MediaFileStreamEntity subtitleStream = MediaFileStreamEntity.builder()
                .codecType(StreamCodecType.EXTERNAL_SUBTITLE)
                .codecName("srt")
                .path(srtFile.toString())
                .width(0).height(0)
                .build();
        ReflectionTestUtils.setField(subtitleStream, "id", subtitleId);

        when(mediaFileStreamRepository.findById(subtitleId)).thenReturn(Optional.of(subtitleStream));
        when(mediaFileRepository.findById(mediaFileId)).thenReturn(Optional.of(mediaFileEntity("/test/video.mkv")));
        when(ffprobeService.getKeyframes("/test/video.mkv")).thenReturn(List.of(0.0));
        when(ffprobeService.getTotalDuration("/test/video.mkv")).thenReturn(4000.0);

        String segmentFilename = String.format("seg_sub_%s_00000.vtt", subtitleId);
        String vtt = hlsService.getSubtitleSegment(mediaFileId, segmentFilename);

        // Timestamps are shifted by SUBTITLE_OFFSET_MS (1480 ms)
        assertTrue(vtt.contains("01:02:04.936 --> 01:02:07.269"));
    }

    // ========== VOD playlist format ==========

    @Test
    void vodPlaylistHasCorrectTargetDuration() throws IOException {
        UUID id = UUID.randomUUID();
        when(mediaFileRepository.findById(id)).thenReturn(Optional.of(mediaFileEntity("/test/video.mkv")));
        // Segments: 0-7s (7s), 7-10s (3s) → max=7 → target=7
        when(ffprobeService.getKeyframes("/test/video.mkv")).thenReturn(List.of(0.0, 7.0));
        when(ffprobeService.getTotalDuration("/test/video.mkv")).thenReturn(10.0);

        String playlist = hlsService.getStreamPlaylist(id, "stream_video_720p.m3u8");

        assertTrue(playlist.contains("#EXT-X-TARGETDURATION:7"));
    }

    @Test
    void vodPlaylistThrowsWhenNoKeyframes() {
        UUID id = UUID.randomUUID();
        when(mediaFileRepository.findById(id)).thenReturn(Optional.of(mediaFileEntity("/test/video.mkv")));
        when(ffprobeService.getKeyframes(any())).thenReturn(List.of());
        when(ffprobeService.getTotalDuration(any())).thenReturn(10.0);

        assertThrows(IllegalStateException.class,
                () -> hlsService.getStreamPlaylist(id, "stream_video_720p.m3u8"));
    }

    // ========== Background pass management ==========

    @Test
    void twoSegmentsOfSameQualityStartOnlyOnePass() throws Exception {
        UUID id = UUID.randomUUID();
        Path cacheDir = tempDir.resolve(id.toString());
        Files.createDirectories(cacheDir);

        when(ffprobeService.getKeyframes("/test/video.mkv")).thenReturn(List.of(0.0, 5.0, 10.0));

        FFmpeg ffmpegMock = mock(FFmpeg.class, RETURNS_SELF);
        when(jaffree.getFFMPEG()).thenReturn(ffmpegMock);

        CountDownLatch passStarted = new CountDownLatch(1);
        CountDownLatch releasePass = new CountDownLatch(1);

        doAnswer(inv -> {
            passStarted.countDown();
            releasePass.await(5, TimeUnit.SECONDS);
            Files.writeString(cacheDir.resolve("seg_video_720p_00000.ts"), "video-data-0");
            Files.writeString(cacheDir.resolve("seg_video_720p_00001.ts"), "video-data-1");
            return completedFFmpegFuture();
        }).when(ffmpegMock).executeAsync();

        // Start the pass explicitly (as the event handler would do)
        String generationKey = id + "_video_720p";
        transcodeService.ensurePassStarted(generationKey,
                () -> transcodeService.startVideoPass("/test/video.mkv", cacheDir, VideoQuality.Q720P));
        passStarted.await(3, TimeUnit.SECONDS);

        ExecutorService pool = Executors.newFixedThreadPool(2);

        Future<Path> f1 = pool.submit(() -> hlsService.getVideoSegment(id, "seg_video_720p_00000.ts"));
        Future<Path> f2 = pool.submit(() -> hlsService.getVideoSegment(id, "seg_video_720p_00001.ts"));

        releasePass.countDown();

        f1.get(5, TimeUnit.SECONDS);
        f2.get(5, TimeUnit.SECONDS);
        pool.shutdown();

        // Only ONE FFmpeg pass started for both segments of the same quality
        verify(ffmpegMock, times(1)).executeAsync();
    }

    @Test
    void generationLocksAreBoundedByQualityCount() throws Exception {
        UUID id = UUID.randomUUID();
        Path cacheDir = tempDir.resolve(id.toString());
        Files.createDirectories(cacheDir);

        when(ffprobeService.getKeyframes("/test/video.mkv")).thenReturn(List.of(0.0, 5.0));

        FFmpeg ffmpegMock = mock(FFmpeg.class, RETURNS_SELF);
        when(jaffree.getFFMPEG()).thenReturn(ffmpegMock);

        doAnswer(inv -> {
            for (int i = 0; i < 2; i++) {
                Files.writeString(cacheDir.resolve(String.format("seg_video_720p_%05d.ts", i)), "data");
            }
            return completedFFmpegFuture();
        }).when(ffmpegMock).executeAsync();

        // Start the pass explicitly (as the event handler would do)
        transcodeService.ensurePassStarted(id + "_video_720p",
                () -> transcodeService.startVideoPass("/test/video.mkv", cacheDir, VideoQuality.Q720P));

        for (int i = 0; i < 2; i++) {
            hlsService.getVideoSegment(id, String.format("seg_video_720p_%05d.ts", i));
        }

        @SuppressWarnings("unchecked")
        ConcurrentHashMap<String, Object> locks =
                (ConcurrentHashMap<String, Object>) ReflectionTestUtils.getField(transcodeService, "generationLocks");

        assertNotNull(locks);
        // One lock per quality level (not one per segment)
        assertEquals(1, locks.size(), "generationLocks should have one entry per quality, not per segment");
    }

    @Test
    void audioAndVideoPassesAreIndependent() throws Exception {
        UUID id = UUID.randomUUID();
        Path cacheDir = tempDir.resolve(id.toString());
        Files.createDirectories(cacheDir);

        when(ffprobeService.getKeyframes("/test/video.mkv")).thenReturn(List.of(0.0, 5.0));

        FFmpeg ffmpegMock = mock(FFmpeg.class, RETURNS_SELF);
        when(jaffree.getFFMPEG()).thenReturn(ffmpegMock);

        AtomicInteger callCount = new AtomicInteger(0);
        doAnswer(inv -> {
            int n = callCount.incrementAndGet();
            if (n == 1) {
                Files.writeString(cacheDir.resolve("seg_video_720p_00000.ts"), "video-data");
            } else {
                Files.writeString(cacheDir.resolve("seg_audio_1_192k_00000.ts"), "audio-data");
            }
            return completedFFmpegFuture();
        }).when(ffmpegMock).executeAsync();

        // Start passes explicitly (as the event handler would do)
        transcodeService.ensurePassStarted(id + "_video_720p",
                () -> transcodeService.startVideoPass("/test/video.mkv", cacheDir, VideoQuality.Q720P));
        transcodeService.ensurePassStarted(id + "_audio_1_192k",
                () -> transcodeService.startAudioPass("/test/video.mkv", cacheDir, 1, AudioQuality.Q192K, "aac"));

        Path videoResult = hlsService.getVideoSegment(id, "seg_video_720p_00000.ts");
        Path audioResult = hlsService.getAudioSegment(id, "seg_audio_1_192k_00000.ts");

        verify(ffmpegMock, times(2)).executeAsync();
        assertTrue(videoResult.toString().contains("seg_video_720p_00000.ts"));
        assertTrue(audioResult.toString().contains("seg_audio_1_192k_00000.ts"));
    }

    // ========== Audio COPY segment ==========

    @Test
    void getAudioSegmentCopyModeGeneratesOnDemand() throws Exception {
        UUID id = UUID.randomUUID();
        Path cacheDir = tempDir.resolve(id.toString());
        Files.createDirectories(cacheDir);

        when(mediaFileRepository.findById(id)).thenReturn(Optional.of(mediaFileEntity("/test/video.mkv")));

        FFmpeg ffmpegMock = mock(FFmpeg.class, RETURNS_SELF);
        when(jaffree.getFFMPEG()).thenReturn(ffmpegMock);

        String segFilename = "seg_audio_0.000000_60.000000_1_copy.ts";
        doAnswer(inv -> {
            Files.writeString(cacheDir.resolve(segFilename), "audio-copy-data");
            return completedFFmpegFuture();
        }).when(ffmpegMock).executeAsync();

        Path result = hlsService.getAudioSegment(id, segFilename);

        assertNotNull(result);
        assertTrue(result.toString().endsWith(segFilename));
    }

    @Test
    void getAudioSegmentCopyModeIsCached() throws Exception {
        UUID id = UUID.randomUUID();
        Path cacheDir = tempDir.resolve(id.toString());
        Files.createDirectories(cacheDir);

        when(mediaFileRepository.findById(id)).thenReturn(Optional.of(mediaFileEntity("/test/video.mkv")));

        FFmpeg ffmpegMock = mock(FFmpeg.class, RETURNS_SELF);
        when(jaffree.getFFMPEG()).thenReturn(ffmpegMock);

        String segFilename = "seg_audio_0.000000_60.000000_1_copy.ts";
        doAnswer(inv -> {
            Files.writeString(cacheDir.resolve(segFilename), "audio-copy-data");
            return completedFFmpegFuture();
        }).when(ffmpegMock).executeAsync();

        hlsService.getAudioSegment(id, segFilename);
        hlsService.getAudioSegment(id, segFilename);

        // FFmpeg should only be called once (second call hits cache)
        verify(ffmpegMock, times(1)).executeAsync();
    }

    // ========== SRT subtitle ==========

    @Test
    void getSrtSubtitleExternalReturnsOffsetFile() throws Exception {
        UUID mediaFileId = UUID.randomUUID();
        UUID subtitleId = UUID.randomUUID();
        // Pre-create the cache dir (getSrtSubtitle for external subtitles relies on the dir existing)
        Files.createDirectories(tempDir.resolve(mediaFileId.toString()));

        Path srtFile = tempDir.resolve("sub.srt");
        Files.writeString(srtFile, "1\n00:00:01,000 --> 00:00:03,000\nHello\n\n");

        MediaFileStreamEntity subtitleStream = MediaFileStreamEntity.builder()
                .codecType(StreamCodecType.EXTERNAL_SUBTITLE)
                .codecName("srt")
                .path(srtFile.toString())
                .width(0).height(0)
                .build();
        ReflectionTestUtils.setField(subtitleStream, "id", subtitleId);

        when(mediaFileStreamRepository.findById(subtitleId)).thenReturn(Optional.of(subtitleStream));

        Path result = hlsService.getSrtSubtitle(mediaFileId, "sub_" + subtitleId + ".srt");

        assertNotNull(result);
        assertTrue(result.toString().endsWith("_offset.srt"));
        String content = Files.readString(result);
        // Timestamps shifted by 1480ms
        assertTrue(content.contains("00:00:02,480 --> 00:00:04,480"));
        assertTrue(content.contains("Hello"));
    }

    @Test
    void getSrtSubtitleExternalIsCached() throws Exception {
        UUID mediaFileId = UUID.randomUUID();
        UUID subtitleId = UUID.randomUUID();
        Files.createDirectories(tempDir.resolve(mediaFileId.toString()));

        Path srtFile = tempDir.resolve("sub.srt");
        Files.writeString(srtFile, "1\n00:00:01,000 --> 00:00:03,000\nHello\n\n");

        MediaFileStreamEntity subtitleStream = MediaFileStreamEntity.builder()
                .codecType(StreamCodecType.EXTERNAL_SUBTITLE)
                .codecName("srt")
                .path(srtFile.toString())
                .width(0).height(0)
                .build();
        ReflectionTestUtils.setField(subtitleStream, "id", subtitleId);

        when(mediaFileStreamRepository.findById(subtitleId)).thenReturn(Optional.of(subtitleStream));

        String filename = "sub_" + subtitleId + ".srt";
        hlsService.getSrtSubtitle(mediaFileId, filename);
        Path second = hlsService.getSrtSubtitle(mediaFileId, filename);

        // Offset file should already exist on second call — no need to re-generate
        assertNotNull(second);
        // streamTokenService not involved; just verify no additional interactions with subtitle service
        verify(mediaFileStreamRepository, times(2)).findById(subtitleId);
    }

    // ========== Subtitle segment caching ==========

    @Test
    void getSubtitleSegmentIsCached() throws IOException {
        UUID mediaFileId = UUID.randomUUID();
        UUID subtitleId = UUID.randomUUID();
        Path srtFile = tempDir.resolve("sub.srt");
        Files.writeString(srtFile, "1\n00:00:01,000 --> 00:00:03,000\nHello\n\n");

        MediaFileStreamEntity subtitleStream = MediaFileStreamEntity.builder()
                .codecType(StreamCodecType.EXTERNAL_SUBTITLE)
                .codecName("srt")
                .path(srtFile.toString())
                .width(0).height(0)
                .build();
        ReflectionTestUtils.setField(subtitleStream, "id", subtitleId);

        when(mediaFileStreamRepository.findById(subtitleId)).thenReturn(Optional.of(subtitleStream));
        when(mediaFileRepository.findById(mediaFileId)).thenReturn(Optional.of(mediaFileEntity("/test/video.mkv")));
        when(ffprobeService.getKeyframes("/test/video.mkv")).thenReturn(List.of(0.0));
        when(ffprobeService.getTotalDuration("/test/video.mkv")).thenReturn(10.0);

        String segFilename = "seg_sub_" + subtitleId + "_00000.vtt";
        String first = hlsService.getSubtitleSegment(mediaFileId, segFilename);
        String second = hlsService.getSubtitleSegment(mediaFileId, segFilename);

        assertEquals(first, second);
        // ffprobe is only called once: subtitle generation on first call, cache hit on second
        verify(ffprobeService, times(1)).getKeyframes(any());
    }

    // ========== Hardware decoding ==========

    @Test
    void vaapiModeStartsVideoPassNormally() throws Exception {
        UUID id = UUID.randomUUID();
        Path cacheDir = tempDir.resolve(id.toString());
        Files.createDirectories(cacheDir);

        ReflectionTestUtils.setField(transcodeService, "hwaccelProperty", "vaapi");

        when(ffprobeService.getKeyframes("/test/video.mkv")).thenReturn(List.of(0.0, 5.0));

        FFmpeg ffmpegMock = mock(FFmpeg.class, RETURNS_SELF);
        when(jaffree.getFFMPEG()).thenReturn(ffmpegMock);

        doAnswer(inv -> {
            Files.writeString(cacheDir.resolve("seg_video_720p_00000.ts"), "video-data");
            return completedFFmpegFuture();
        }).when(ffmpegMock).executeAsync();

        transcodeService.ensurePassStarted(id + "_video_720p",
                () -> transcodeService.startVideoPass("/test/video.mkv", cacheDir, VideoQuality.Q720P));
        Path result = hlsService.getVideoSegment(id, "seg_video_720p_00000.ts");

        assertNotNull(result);
        verify(ffmpegMock, times(1)).executeAsync();
        // -vaapi_device must precede -i, and the VAAPI encoder + hwupload filter must be used.
        assertContainsSequence(inputArgsOf(ffmpegMock), "-vaapi_device", "/dev/dri/renderD128");
        List<String> outputArgs = outputArgsOf(ffmpegMock);
        assertContainsSequence(outputArgs, "-c:v", "h264_vaapi");
        assertContainsSequence(outputArgs, "-vf", "scale=1280:720,format=nv12,hwupload");
    }

    @Test
    void nvdecModeStartsVideoPassNormally() throws Exception {
        UUID id = UUID.randomUUID();
        Path cacheDir = tempDir.resolve(id.toString());
        Files.createDirectories(cacheDir);

        ReflectionTestUtils.setField(transcodeService, "hwaccelProperty", "nvdec");

        when(ffprobeService.getKeyframes("/test/video.mkv")).thenReturn(List.of(0.0, 5.0));

        FFmpeg ffmpegMock = mock(FFmpeg.class, RETURNS_SELF);
        when(jaffree.getFFMPEG()).thenReturn(ffmpegMock);

        doAnswer(inv -> {
            Files.writeString(cacheDir.resolve("seg_video_720p_00000.ts"), "video-data");
            return completedFFmpegFuture();
        }).when(ffmpegMock).executeAsync();

        transcodeService.ensurePassStarted(id + "_video_720p",
                () -> transcodeService.startVideoPass("/test/video.mkv", cacheDir, VideoQuality.Q720P));
        Path result = hlsService.getVideoSegment(id, "seg_video_720p_00000.ts");

        assertNotNull(result);
        verify(ffmpegMock, times(1)).executeAsync();
        // CUDA decode is requested on the input; the NVENC encoder and cuda scaler on the output.
        assertContainsSequence(inputArgsOf(ffmpegMock), "-hwaccel", "cuda");
        assertContainsSequence(inputArgsOf(ffmpegMock), "-hwaccel_output_format", "cuda");
        List<String> outputArgs = outputArgsOf(ffmpegMock);
        assertContainsSequence(outputArgs, "-c:v", "h264_nvenc");
        assertContainsSequence(outputArgs, "-vf", "scale_cuda=1280:720:format=nv12");
        assertContainsSequence(outputArgs, "-preset", "fast");
    }

    @Test
    void hwaccelDoesNotAffectAudioPass() throws Exception {
        UUID id = UUID.randomUUID();
        Path cacheDir = tempDir.resolve(id.toString());
        Files.createDirectories(cacheDir);

        ReflectionTestUtils.setField(transcodeService, "hwaccelProperty", "vaapi");

        when(ffprobeService.getKeyframes("/test/video.mkv")).thenReturn(List.of(0.0, 5.0));

        FFmpeg ffmpegMock = mock(FFmpeg.class, RETURNS_SELF);
        when(jaffree.getFFMPEG()).thenReturn(ffmpegMock);

        doAnswer(inv -> {
            Files.writeString(cacheDir.resolve("seg_audio_1_192k_00000.ts"), "audio-data");
            return completedFFmpegFuture();
        }).when(ffmpegMock).executeAsync();

        transcodeService.ensurePassStarted(id + "_audio_1_192k",
                () -> transcodeService.startAudioPass("/test/video.mkv", cacheDir, 1, AudioQuality.Q192K, "aac"));
        Path result = hlsService.getAudioSegment(id, "seg_audio_1_192k_00000.ts");

        assertNotNull(result);
        verify(ffmpegMock, times(1)).executeAsync();
        // An audio-only pass never touches the video hardware, whatever the hwaccel setting is.
        assertFalse(inputArgsOf(ffmpegMock).contains("-vaapi_device"));
    }

    // ========== Concurrent file limit ==========

    @Test
    void multiplePassesForSameFileUseOnlyOneSlot() throws Exception {
        // With max=1, two quality passes for the same file should both succeed
        // because they share a single slot
        ReflectionTestUtils.setField(transcodeService, "concurrentFileSlots", new Semaphore(1));

        UUID id = UUID.randomUUID();
        Path cacheDir = tempDir.resolve(id.toString());
        Files.createDirectories(cacheDir);

        when(ffprobeService.getKeyframes("/test/video.mkv")).thenReturn(List.of(0.0, 5.0));

        FFmpeg ffmpegMock = mock(FFmpeg.class, RETURNS_SELF);
        when(jaffree.getFFMPEG()).thenReturn(ffmpegMock);

        // Both passes run this answer concurrently; CREATE_NEW prevents the second pass from
        // truncating a segment the first pass wrote while the test thread is reading its size.
        doAnswer(inv -> {
            writeIfAbsent(cacheDir.resolve("seg_video_720p_00000.ts"), "video-data");
            writeIfAbsent(cacheDir.resolve("seg_audio_1_192k_00000.ts"), "audio-data");
            return completedFFmpegFuture();
        }).when(ffmpegMock).executeAsync();

        transcodeService.ensurePassStarted(id + "_video_720p",
                () -> transcodeService.startVideoPass("/test/video.mkv", cacheDir, VideoQuality.Q720P));
        transcodeService.ensurePassStarted(id + "_audio_1_192k",
                () -> transcodeService.startAudioPass("/test/video.mkv", cacheDir, 1, AudioQuality.Q192K, "aac"));

        Path video = hlsService.getVideoSegment(id, "seg_video_720p_00000.ts");
        Path audio = hlsService.getAudioSegment(id, "seg_audio_1_192k_00000.ts");

        assertNotNull(video);
        assertNotNull(audio);
    }

    @Test
    void secondFileWaitsWhenLimitReached() throws Exception {
        // With max=1: file 1 holds the slot; file 2 must wait until file 1 is done
        ReflectionTestUtils.setField(transcodeService, "concurrentFileSlots", new Semaphore(1));
        ReflectionTestUtils.setField(transcodeService, "segmentTimeoutMs", 10000L);

        UUID id1 = UUID.randomUUID();
        UUID id2 = UUID.randomUUID();
        Path cacheDir1 = tempDir.resolve(id1.toString());
        Path cacheDir2 = tempDir.resolve(id2.toString());
        Files.createDirectories(cacheDir1);
        Files.createDirectories(cacheDir2);

        when(ffprobeService.getKeyframes(any())).thenReturn(List.of(0.0, 5.0));

        FFmpeg ffmpegMock = mock(FFmpeg.class, RETURNS_SELF);
        when(jaffree.getFFMPEG()).thenReturn(ffmpegMock);

        CountDownLatch file1PassStarted = new CountDownLatch(1);
        CountDownLatch releaseFile1 = new CountDownLatch(1);
        AtomicBoolean file2RanWhileFile1Active = new AtomicBoolean(false);
        AtomicInteger passOrder = new AtomicInteger(0);

        doAnswer(inv -> {
            int order = passOrder.incrementAndGet();
            if (order == 1) {
                // File 1's pass: signal started, then wait
                file1PassStarted.countDown();
                releaseFile1.await(5, TimeUnit.SECONDS);
                Files.writeString(cacheDir1.resolve("seg_video_720p_00000.ts"), "data1");
            } else {
                // File 2's pass: check that file 1's slot has been released
                file2RanWhileFile1Active.set(file1PassStarted.getCount() == 0 && releaseFile1.getCount() > 0);
                Files.writeString(cacheDir2.resolve("seg_video_720p_00000.ts"), "data2");
            }
            return completedFFmpegFuture();
        }).when(ffmpegMock).executeAsync();

        ExecutorService pool = Executors.newFixedThreadPool(4);

        // Start passes explicitly (as the event handler would do)
        pool.submit(() -> transcodeService.ensurePassStarted(id1 + "_video_720p",
                () -> transcodeService.startVideoPass("/test/video1.mkv", cacheDir1, VideoQuality.Q720P)));
        file1PassStarted.await(3, TimeUnit.SECONDS);

        Future<Path> f1 = pool.submit(() -> hlsService.getVideoSegment(id1, "seg_video_720p_00000.ts"));

        // File 2 starts while file 1 holds the slot — it should be queued.
        // Call ensurePassStarted directly (it returns immediately after registering the CF in
        // activeGenerations) so that isPassActive(id2) is true before getVideoSegment checks it.
        transcodeService.ensurePassStarted(id2 + "_video_720p",
                () -> transcodeService.startVideoPass("/test/video2.mkv", cacheDir2, VideoQuality.Q720P));
        Future<Path> f2 = pool.submit(() -> hlsService.getVideoSegment(id2, "seg_video_720p_00000.ts"));

        // Give file 2 time to queue up (wait until the pass is registered), then release file 1
        long waitUntil = System.currentTimeMillis() + 5_000;
        while (!transcodeService.isPassActive(id2 + "_video_720p") && System.currentTimeMillis() < waitUntil) {
            Thread.yield();
        }
        releaseFile1.countDown();

        f1.get(5, TimeUnit.SECONDS);
        f2.get(5, TimeUnit.SECONDS);
        pool.shutdown();

        // File 2 must NOT have run while file 1 was still active
        assertFalse(file2RanWhileFile1Active.get(), "File 2 should have waited for file 1's slot to be released");
        verify(ffmpegMock, times(2)).executeAsync();
    }

    @Test
    void slotIsReleasedAfterAllPassesComplete() throws Exception {
        ReflectionTestUtils.setField(transcodeService, "concurrentFileSlots", new Semaphore(1));

        UUID id = UUID.randomUUID();
        Path cacheDir = tempDir.resolve(id.toString());
        Files.createDirectories(cacheDir);

        when(ffprobeService.getKeyframes("/test/video.mkv")).thenReturn(List.of(0.0, 5.0));

        FFmpeg ffmpegMock = mock(FFmpeg.class, RETURNS_SELF);
        when(jaffree.getFFMPEG()).thenReturn(ffmpegMock);

        doAnswer(inv -> {
            Files.writeString(cacheDir.resolve("seg_video_720p_00000.ts"), "data");
            return completedFFmpegFuture();
        }).when(ffmpegMock).executeAsync();

        transcodeService.ensurePassStarted(id + "_video_720p",
                () -> transcodeService.startVideoPass("/test/video.mkv", cacheDir, VideoQuality.Q720P));
        hlsService.getVideoSegment(id, "seg_video_720p_00000.ts");

        // Wait until the pass future completes
        CompletableFuture<Void> passFuture = transcodeService.getActiveFuture(id + "_video_720p");
        if (passFuture != null) passFuture.get(5, TimeUnit.SECONDS);

        // After all passes for file are done, the slot must be back (1 available).
        // The slot is released just after the pass future completes, so poll with a deadline.
        Semaphore slots = (Semaphore) ReflectionTestUtils.getField(transcodeService, "concurrentFileSlots");
        assertNotNull(slots);
        await().atMost(5, TimeUnit.SECONDS)
                .untilAsserted(() -> assertEquals(1, slots.availablePermits(),
                        "Slot should be released after all passes complete"));
    }

    @Test
    void failedPassIsRestartedOnNextRequest() throws Exception {
        UUID id = UUID.randomUUID();
        Path cacheDir = tempDir.resolve(id.toString());
        Files.createDirectories(cacheDir);

        when(ffprobeService.getKeyframes("/test/video.mkv")).thenReturn(List.of(0.0, 5.0));

        FFmpeg ffmpegMock = mock(FFmpeg.class, RETURNS_SELF);
        when(jaffree.getFFMPEG()).thenReturn(ffmpegMock);

        AtomicInteger callCount = new AtomicInteger(0);
        doAnswer(inv -> {
            if (callCount.incrementAndGet() == 1) {
                // The production code surfaces this as IllegalStateException("FFmpeg failed: ...")
                return failedFFmpegFuture(new RuntimeException("Simulated FFmpeg crash"));
            }
            Files.writeString(cacheDir.resolve("seg_video_720p_00000.ts"), "data");
            return completedFFmpegFuture();
        }).when(ffmpegMock).executeAsync();

        // First pass: fails
        String generationKey = id + "_video_720p";
        transcodeService.ensurePassStarted(generationKey,
                () -> transcodeService.startVideoPass("/test/video.mkv", cacheDir, VideoQuality.Q720P));
        assertThrows(IOException.class, () -> hlsService.getVideoSegment(id, "seg_video_720p_00000.ts"));

        // Second pass: restarts because first was exceptionally completed
        transcodeService.ensurePassStarted(generationKey,
                () -> transcodeService.startVideoPass("/test/video.mkv", cacheDir, VideoQuality.Q720P));
        Path result = hlsService.getVideoSegment(id, "seg_video_720p_00000.ts");
        assertNotNull(result);
        verify(ffmpegMock, times(2)).executeAsync();
    }

    // ========== startAllPasses ==========

    @Test
    void startAllPassesTranscodeOnlyStartsFourPasses() throws Exception {
        UUID id = UUID.randomUUID();
        Path cacheDir = tempDir.resolve(id.toString());
        Files.createDirectories(cacheDir);

        MediaFileStreamEntity audioStream = audioStream(1, "eng", "English");
        MediaFileEntity mediaFile = mediaFileEntity("/test/video.mkv", videoStream(0, 1920, 1080), audioStream);

        when(mediaFileRepository.findById(id)).thenReturn(Optional.of(mediaFile));
        when(ffprobeService.getKeyframes("/test/video.mkv")).thenReturn(List.of(0.0, 5.0));

        FFmpeg ffmpegMock = mock(FFmpeg.class, RETURNS_SELF);
        when(jaffree.getFFMPEG()).thenReturn(ffmpegMock);

        CountDownLatch allDone = new CountDownLatch(4);
        doAnswer(inv -> {
            allDone.countDown();
            return completedFFmpegFuture();
        }).when(ffmpegMock).executeAsync();

        hlsService.startAllPasses(id, false, true);
        assertTrue(allDone.await(5, TimeUnit.SECONDS), "All 4 passes should have started");

        // 720p + 480p video, 192k + 64k audio
        verify(ffmpegMock, times(4)).executeAsync();
    }

    @Test
    void startAllPassesDirectOnlyStartsOneCopyVideoPass() throws Exception {
        UUID id = UUID.randomUUID();
        Path cacheDir = tempDir.resolve(id.toString());
        Files.createDirectories(cacheDir);

        MediaFileStreamEntity audioStream = audioStream(1, "eng", "English");
        MediaFileEntity mediaFile = mediaFileEntity("/test/video.mkv", videoStream(0, 1920, 1080), audioStream);

        when(mediaFileRepository.findById(id)).thenReturn(Optional.of(mediaFile));
        when(ffprobeService.getKeyframes("/test/video.mkv")).thenReturn(List.of(0.0, 5.0));

        FFmpeg ffmpegMock = mock(FFmpeg.class, RETURNS_SELF);
        when(jaffree.getFFMPEG()).thenReturn(ffmpegMock);

        CountDownLatch copyDone = new CountDownLatch(1);
        doAnswer(inv -> {
            Files.writeString(cacheDir.resolve("seg_video_copy_00000.ts"), "data");
            copyDone.countDown();
            return completedFFmpegFuture();
        }).when(ffmpegMock).executeAsync();

        hlsService.startAllPasses(id, true, false);
        assertTrue(copyDone.await(5, TimeUnit.SECONDS));

        // Only COPY video pass — COPY audio is generated on-demand, not via a background pass
        verify(ffmpegMock, times(1)).executeAsync();
    }

    @Test
    void startAllPassesSkipsAlreadyActivePass() throws Exception {
        UUID id = UUID.randomUUID();
        Path cacheDir = tempDir.resolve(id.toString());
        Files.createDirectories(cacheDir);

        MediaFileStreamEntity audioStream = audioStream(1, "eng", "English");
        MediaFileEntity mediaFile = mediaFileEntity("/test/video.mkv", videoStream(0, 1920, 1080), audioStream);

        when(mediaFileRepository.findById(id)).thenReturn(Optional.of(mediaFile));
        when(ffprobeService.getKeyframes("/test/video.mkv")).thenReturn(List.of(0.0, 5.0));

        FFmpeg ffmpegMock = mock(FFmpeg.class, RETURNS_SELF);
        when(jaffree.getFFMPEG()).thenReturn(ffmpegMock);

        // Latch to confirm 720p pass is inside execute() before startAllPasses runs
        CountDownLatch pass720pInExecute = new CountDownLatch(1);
        CountDownLatch hold720p = new CountDownLatch(1);
        CountDownLatch allDone = new CountDownLatch(4); // 1 manual 720p + 3 from startAllPasses
        doAnswer(inv -> {
            pass720pInExecute.countDown(); // Signal that we are inside execute()
            hold720p.await(5, TimeUnit.SECONDS);
            Files.writeString(cacheDir.resolve("seg_video_720p_00000.ts"), "data");
            Files.writeString(cacheDir.resolve("seg_video_480p_00000.ts"), "data");
            Files.writeString(cacheDir.resolve("seg_audio_1_192k_00000.ts"), "data");
            Files.writeString(cacheDir.resolve("seg_audio_1_64k_00000.ts"), "data");
            allDone.countDown();
            return completedFFmpegFuture();
        }).when(ffmpegMock).executeAsync();

        // Start 720p pass manually and wait until it is confirmed running
        transcodeService.ensurePassStarted(id + "_video_720p",
                () -> transcodeService.startVideoPass("/test/video.mkv", cacheDir, VideoQuality.Q720P));
        assertTrue(pass720pInExecute.await(3, TimeUnit.SECONDS), "720p pass should be running");

        // 720p is now confirmed active inside execute(); startAllPasses must skip it
        hlsService.startAllPasses(id, false, true);
        hold720p.countDown();
        assertTrue(allDone.await(5, TimeUnit.SECONDS), "All passes should complete");

        // 1 (manual 720p) + 3 (480p + 192k + 64k from startAllPasses) = 4 total; 720p NOT restarted
        verify(ffmpegMock, times(4)).executeAsync();
    }

    @Test
    void startAllPassesWithTwoAudioStreamsStartsSixPasses() throws Exception {
        UUID id = UUID.randomUUID();
        Path cacheDir = tempDir.resolve(id.toString());
        Files.createDirectories(cacheDir);

        MediaFileStreamEntity audio1 = audioStream(1, "eng", "English");
        MediaFileStreamEntity audio2 = audioStream(2, "nld", "Dutch");
        MediaFileEntity mediaFile = mediaFileEntity("/test/video.mkv", videoStream(0, 1920, 1080), audio1, audio2);

        when(mediaFileRepository.findById(id)).thenReturn(Optional.of(mediaFile));
        when(ffprobeService.getKeyframes("/test/video.mkv")).thenReturn(List.of(0.0, 5.0));

        FFmpeg ffmpegMock = mock(FFmpeg.class, RETURNS_SELF);
        when(jaffree.getFFMPEG()).thenReturn(ffmpegMock);

        CountDownLatch allDone = new CountDownLatch(6);
        doAnswer(inv -> {
            allDone.countDown();
            return completedFFmpegFuture();
        }).when(ffmpegMock).executeAsync();

        hlsService.startAllPasses(id, false, true);
        assertTrue(allDone.await(5, TimeUnit.SECONDS), "All 6 passes should have started");

        // 720p + 480p video, 192k×2 + 64k×2 audio
        verify(ffmpegMock, times(6)).executeAsync();
    }

    @Test
    void preTranscodeFinishesAllPassesEvenWhenOnlyOneMayRunAtATime() throws Exception {
        // Only one background pass fits at a time, so five of the six passes are dropped at
        // request time. Each finished pass must pull in the next one, or the file would need
        // six pre-transcode cycles to complete.
        ReflectionTestUtils.setField(transcodeService, "backgroundPassBudget", new Semaphore(1));
        hlsService.registerBackgroundPassResume();

        UUID id = UUID.randomUUID();
        Path cacheDir = tempDir.resolve(id.toString());
        Files.createDirectories(cacheDir);

        MediaFileStreamEntity audio1 = audioStream(1, "eng", "English");
        MediaFileStreamEntity audio2 = audioStream(2, "nld", "Dutch");
        MediaFileEntity mediaFile = mediaFileEntity("/test/video.mkv", videoStream(0, 1920, 1080), audio1, audio2);

        when(mediaFileRepository.findById(id)).thenReturn(Optional.of(mediaFile));
        when(ffprobeService.getKeyframes("/test/video.mkv")).thenReturn(List.of(0.0, 5.0));

        FFmpeg ffmpegMock = mock(FFmpeg.class, RETURNS_SELF);
        when(jaffree.getFFMPEG()).thenReturn(ffmpegMock);

        CountDownLatch allDone = new CountDownLatch(6);
        doAnswer(inv -> {
            allDone.countDown();
            // Delayed on purpose — see delayedFFmpegFuture: an instant completion can hand the
            // budget over before the other passes registered for resume, stranding the chain.
            return delayedFFmpegFuture();
        }).when(ffmpegMock).executeAsync();

        hlsService.startAllPasses(id, false, true);

        assertTrue(allDone.await(30, TimeUnit.SECONDS), "All 6 passes should have run despite the budget of 1");
        verify(ffmpegMock, times(6)).executeAsync();
    }

    @Test
    void preTranscodeOnlyTranscodesThePreferredLanguagesAt192k() throws Exception {
        // The case this filter exists for: an episode with seven audio streams used to cost
        // 2 video + 7 x 2 audio = 16 passes. With the users' languages known it is 4.
        UUID id = UUID.randomUUID();
        Files.createDirectories(tempDir.resolve(id.toString()));

        MediaFileEntity mediaFile = mediaFileEntity("/test/video.mkv",
                videoStream(0, 1920, 1080),
                audioStream(1, "eng", "English"), audioStream(2, "nld", "Dutch"),
                audioStream(3, "fra", "French"), audioStream(4, "deu", "German"),
                audioStream(5, "spa", "Spanish"), audioStream(6, "ita", "Italian"),
                audioStream(7, "pol", "Polish"));

        when(mediaFileRepository.findById(id)).thenReturn(Optional.of(mediaFile));
        when(ffprobeService.getKeyframes("/test/video.mkv")).thenReturn(List.of(0.0, 5.0));

        FFmpeg ffmpegMock = mock(FFmpeg.class, RETURNS_SELF);
        when(jaffree.getFFMPEG()).thenReturn(ffmpegMock);

        CountDownLatch allDone = new CountDownLatch(4);
        doAnswer(inv -> {
            allDone.countDown();
            return completedFFmpegFuture();
        }).when(ffmpegMock).executeAsync();

        hlsService.startAllPasses(id, false, true, PassFilter.preTranscode(List.of("nl", "en"), null));

        assertTrue(allDone.await(5, TimeUnit.SECONDS), "720p, 480p and the two preferred audio streams");
        // 720p + 480p + audio_1_192k (eng) + audio_2_192k (nld). No 64k: no master playlist points at it.
        verify(ffmpegMock, times(4)).executeAsync();
    }

    @Test
    void preTranscodeFallsBackToTheFirstAudioStreamWhenNoLanguageMatches() throws Exception {
        UUID id = UUID.randomUUID();
        Files.createDirectories(tempDir.resolve(id.toString()));

        MediaFileEntity mediaFile = mediaFileEntity("/test/video.mkv", videoStream(0, 1920, 1080),
                audioStream(1, "jpn", "Japanese"), audioStream(2, "kor", "Korean"));

        when(mediaFileRepository.findById(id)).thenReturn(Optional.of(mediaFile));
        when(ffprobeService.getKeyframes("/test/video.mkv")).thenReturn(List.of(0.0, 5.0));

        FFmpeg ffmpegMock = mock(FFmpeg.class, RETURNS_SELF);
        when(jaffree.getFFMPEG()).thenReturn(ffmpegMock);

        CountDownLatch allDone = new CountDownLatch(3);
        doAnswer(inv -> {
            allDone.countDown();
            return completedFFmpegFuture();
        }).when(ffmpegMock).executeAsync();

        hlsService.startAllPasses(id, false, true, PassFilter.preTranscode(List.of("nl", "en"), null));

        assertTrue(allDone.await(5, TimeUnit.SECONDS), "a warm file must not be mute");
        // 720p + 480p + the first audio stream at 192k
        verify(ffmpegMock, times(3)).executeAsync();
    }

    @Test
    void preTranscodeHonoursTheUsersVideoQualityCap() throws Exception {
        UUID id = UUID.randomUUID();
        Files.createDirectories(tempDir.resolve(id.toString()));

        MediaFileEntity mediaFile = mediaFileEntity("/test/video.mkv", videoStream(0, 1920, 1080),
                audioStream(1, "nld", "Dutch"));

        when(mediaFileRepository.findById(id)).thenReturn(Optional.of(mediaFile));
        when(ffprobeService.getKeyframes("/test/video.mkv")).thenReturn(List.of(0.0, 5.0));

        FFmpeg ffmpegMock = mock(FFmpeg.class, RETURNS_SELF);
        when(jaffree.getFFMPEG()).thenReturn(ffmpegMock);

        CountDownLatch allDone = new CountDownLatch(2);
        doAnswer(inv -> {
            allDone.countDown();
            return completedFFmpegFuture();
        }).when(ffmpegMock).executeAsync();

        hlsService.startAllPasses(id, false, true, PassFilter.preTranscode(List.of("nl"), 480));

        assertTrue(allDone.await(5, TimeUnit.SECONDS));
        // 480p only (720p exceeds the cap) + the Dutch audio stream at 192k
        verify(ffmpegMock, times(2)).executeAsync();
    }

    // ========== generateAllPlaylists edge cases ==========

    @Test
    void generateAllPlaylistsIsNoOpWhenAlreadyCached() throws IOException {
        UUID id = UUID.randomUUID();
        MediaFileStreamEntity videoStream = videoStream(0, 1920, 1080);
        MediaFileStreamEntity audioStream = audioStream(1, "eng", "English");
        MediaFileEntity mediaFile = mediaFileEntity("/test/video.mkv", videoStream, audioStream);

        when(mediaFileRepository.findById(id)).thenReturn(Optional.of(mediaFile));
        when(ffprobeService.getKeyframes("/test/video.mkv")).thenReturn(List.of(0.0, 5.0));
        when(ffprobeService.getTotalDuration("/test/video.mkv")).thenReturn(10.0);

        // First call generates the cache
        hlsService.generateAllPlaylists(id, true, false, SubtitleFormat.WEBVTT);
        // Second call should not invoke ffprobe again
        hlsService.generateAllPlaylists(id, true, false, SubtitleFormat.WEBVTT);

        verify(ffprobeService, times(1)).getKeyframes(any());
    }

    @Test
    void generateAllPlaylistsSkipsWhenNoStreamEntries() throws IOException {
        UUID id = UUID.randomUUID();
        // Media file with no streams → master playlist has no EXT-X-MEDIA or EXT-X-STREAM-INF
        MediaFileEntity mediaFile = mediaFileEntity("/test/video.mkv");
        when(mediaFileRepository.findById(id)).thenReturn(Optional.of(mediaFile));

        // Should not throw, but should not write a cache file either
        hlsService.generateAllPlaylists(id, true, false, SubtitleFormat.WEBVTT);

        Path cacheFile = tempDir.resolve(id.toString()).resolve("master_d1_t0_sWEBVTT.m3u8");
        assertFalse(cacheFile.toFile().exists(), "Master playlist should not be cached when content has no streams");
    }

    @Test
    void generateAllPlaylistsUploadsToRemoteNode() throws IOException {
        UUID id = UUID.randomUUID();
        MediaFileStreamEntity videoStream = videoStream(0, 1920, 1080);
        MediaFileStreamEntity audioStream = audioStream(1, "eng", "English");
        // Build a media file entity on a REMOTE node (different from LOCAL_NODE_NAME)
        NodeEntity remoteNode = NodeEntity.builder().name("remote-node").url("http://remote:8080").build();
        DirectoryEntity directory = DirectoryEntity.builder()
                .name("remote-dir")
                .path("/remote")
                .directoryType(DirectoryType.LIBRARY)
                .nodeEntity(remoteNode)
                .build();
        MediaFileEntity remoteFile = MediaFileEntity.builder()
                .path("/remote/video.mkv")
                .size(0)
                .directoryEntityId(UUID.randomUUID())
                .mediaFileStreamEntity(List.of(videoStream, audioStream))
                .build();
        remoteFile.setDirectoryEntity(directory);
        ReflectionTestUtils.setField(remoteFile, "id", id);

        when(mediaFileRepository.findById(id)).thenReturn(Optional.of(remoteFile));
        when(nodeTokenManager.getDownloadToken()).thenReturn("node-token-123");
        String remoteUrl = "http://remote:8080/mediaFile/" + id + "/download?token=node-token-123";
        when(ffprobeService.getKeyframes(remoteUrl)).thenReturn(List.of(0.0, 5.0));
        when(ffprobeService.getTotalDuration(remoteUrl)).thenReturn(10.0);

        hlsService.generateAllPlaylists(id, true, false, SubtitleFormat.WEBVTT);

        verify(remoteNodeClient, atLeastOnce()).uploadFile(eq("http://remote:8080"), eq(id), any(Path.class));
    }

    // ========== getMasterPlaylist edge cases ==========

    @Test
    void getMasterPlaylistThrowsWhenMediaHasNoStreams() {
        UUID id = UUID.randomUUID();
        MediaFileEntity mediaFile = mediaFileEntity("/test/video.mkv");
        mediaFile.setDirectoryEntity(DirectoryEntity.builder().name("dir").path("/").directoryType(DirectoryType.LIBRARY)
                .nodeEntity(NodeEntity.builder().name(LOCAL_NODE_NAME).url("http://localhost").build()).build());
        when(mediaFileRepository.findById(id)).thenReturn(Optional.of(mediaFile));

        assertThrows(IOException.class,
                () -> hlsService.getMasterPlaylist(id, true, false, SubtitleFormat.WEBVTT));
    }

    @Test
    void getMasterPlaylistTimeoutThrowsIOException() {
        UUID id = UUID.randomUUID();
        ReflectionTestUtils.setField(hlsService, "masterPlaylistTimeoutMs", 100L);
        MediaFileStreamEntity videoStream = videoStream(0, 1920, 1080);
        MediaFileStreamEntity audioStream = audioStream(1, "eng", "English");
        MediaFileEntity mediaFile = mediaFileEntity("/test/video.mkv", videoStream, audioStream);
        when(mediaFileRepository.findById(id)).thenReturn(Optional.of(mediaFile));

        // Do not call generateAllPlaylists, so the cache file never appears
        assertThrows(IOException.class,
                () -> hlsService.getMasterPlaylist(id, true, false, SubtitleFormat.WEBVTT));
        // Sent once on cache miss, re-sent once after the first wait times out
        verify(messageSender, times(2)).sendTranscodeRequested(any(), any());
    }

    @Test
    void getMasterPlaylistFailsFastWhenNoTranscodeQueueExists() {
        UUID id = UUID.randomUUID();
        // A long timeout: the point is that we never start waiting for it.
        ReflectionTestUtils.setField(hlsService, "masterPlaylistTimeoutMs", 60_000L);
        MediaFileEntity mediaFile = mediaFileEntity("/test/video.mkv", videoStream(0, 1920, 1080));
        when(mediaFileRepository.findById(id)).thenReturn(Optional.of(mediaFile));
        // No node declared a transcode queue for this directory, so the event would be dropped.
        when(amqpAdmin.getQueueProperties(anyString())).thenReturn(null);

        assertThrows(IOException.class,
                () -> hlsService.getMasterPlaylist(id, true, false, SubtitleFormat.WEBVTT));
        verify(messageSender, never()).sendTranscodeRequested(any(), any());
    }

    @Test
    void getMasterPlaylistDeletesAndRegeneratesStaleCache() throws IOException {
        UUID id = UUID.randomUUID();
        ReflectionTestUtils.setField(hlsService, "masterPlaylistTimeoutMs", 100L);
        MediaFileStreamEntity videoStream = videoStream(0, 1920, 1080);
        MediaFileStreamEntity audioStream = audioStream(1, "eng", "English");
        MediaFileEntity mediaFile = mediaFileEntity("/test/video.mkv", videoStream, audioStream);
        when(mediaFileRepository.findById(id)).thenReturn(Optional.of(mediaFile));

        // Write a stale cache file (no #EXT-X-MEDIA or #EXT-X-STREAM-INF)
        Path cacheDir = tempDir.resolve(id.toString());
        Files.createDirectories(cacheDir);
        String cacheFilename = "master_d1_t0_sWEBVTT.m3u8";
        Path cacheFile = cacheDir.resolve(cacheFilename);
        Files.writeString(cacheFile, "#EXTM3U\n# empty/stale\n");

        // Should delete stale file and send TRANSCODE_REQUESTED, then timeout
        assertThrows(IOException.class,
                () -> hlsService.getMasterPlaylist(id, true, false, SubtitleFormat.WEBVTT));

        assertFalse(Files.exists(cacheFile), "Stale cache file should be deleted");
        // Sent once on cache miss, re-sent once after the first wait times out
        verify(messageSender, times(2)).sendTranscodeRequested(any(), any());
    }

    // ========== getVideoSegment sends TRANSCODE_PASS_REQUESTED ==========

    @Test
    void getVideoSegmentSendsTranscodePassRequestedWhenNoActivePass() throws Exception {
        UUID id = UUID.randomUUID();
        Path cacheDir = tempDir.resolve(id.toString());
        Files.createDirectories(cacheDir);
        Path segFile = cacheDir.resolve("seg_video_720p_00000.ts");

        MediaFileStreamEntity videoStream = videoStream(0, 1920, 1080);
        MediaFileEntity mediaFile = mediaFileEntity("/test/video.mkv", videoStream);
        when(mediaFileRepository.findById(id)).thenReturn(Optional.of(mediaFile));

        // Write the segment after a short delay to simulate FFmpeg producing it externally
        CompletableFuture.delayedExecutor(200, TimeUnit.MILLISECONDS).execute(() -> {
            try { Files.writeString(segFile, "segment-data"); } catch (IOException _) { /* test writer failure surfaces as a missing segment, asserted below */ }
        });

        // No pass is active — getVideoSegment must send TRANSCODE_PASS_REQUESTED
        Path result = hlsService.getVideoSegment(id, "seg_video_720p_00000.ts");

        assertNotNull(result);
        verify(messageSender).sendTranscodePassRequested(any(), any());
    }

    // ========== getMasterPlaylist wait path ==========

    @Test
    void getMasterPlaylistWaitsForFileToAppear() throws Exception {
        UUID id = UUID.randomUUID();
        Path cacheDir = tempDir.resolve(id.toString());
        Files.createDirectories(cacheDir);
        Path cacheFile = cacheDir.resolve("master_d1_t0_sWEBVTT.m3u8");
        String masterContent = "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=3000000\nstream.m3u8\n";

        MediaFileStreamEntity videoStream = videoStream(0, 1920, 1080);
        MediaFileEntity mediaFile = mediaFileEntity("/test/video.mkv", videoStream);
        when(mediaFileRepository.findById(id)).thenReturn(Optional.of(mediaFile));

        ReflectionTestUtils.setField(hlsService, "masterPlaylistTimeoutMs", 2000L);

        CompletableFuture.delayedExecutor(200, TimeUnit.MILLISECONDS).execute(() -> {
            try { Files.writeString(cacheFile, masterContent); } catch (IOException _) { /* test writer failure surfaces as a missing segment, asserted below */ }
        });

        String result = hlsService.getMasterPlaylist(id, true, false, SubtitleFormat.WEBVTT);

        assertEquals(masterContent, result);
        verify(messageSender).sendTranscodeRequested(any(), any());
    }

    // ========== startPass ==========

    @Test
    void startPassVideoCategoryStartsPass() throws Exception {
        UUID id = UUID.randomUUID();
        MediaFileEntity mediaFile = mediaFileEntity("/test/video.mkv");
        when(mediaFileRepository.findById(id)).thenReturn(Optional.of(mediaFile));
        lenient().when(ffprobeService.getKeyframes("/test/video.mkv")).thenReturn(List.of(0.0));
        FFmpeg ffmpegMock = mock(FFmpeg.class, RETURNS_SELF);
        lenient().when(jaffree.getFFMPEG()).thenReturn(ffmpegMock);
        lenient().when(ffmpegMock.executeAsync()).thenReturn(completedFFmpegFuture());

        TranscodePassRequestedData data = TranscodePassRequestedData.builder()
                .eventType(EventType.TRANSCODE_PASS_REQUESTED)
                .mediaFileId(id)
                .passKey(id + "_video_720p")
                .passCategory("video")
                .qualityLabel("720p")
                .build();

        hlsService.startPass(data);

        verify(mediaFileRepository).findById(id);
        // Await the async pass so it cannot race @TempDir cleanup
        CompletableFuture<Void> passFuture = transcodeService.getActiveFuture(id + "_video_720p");
        if (passFuture != null) passFuture.get(5, TimeUnit.SECONDS);
    }

    @Test
    void startPassAudioCategoryStartsPass() throws Exception {
        UUID id = UUID.randomUUID();
        MediaFileEntity mediaFile = mediaFileEntity("/test/video.mkv");
        when(mediaFileRepository.findById(id)).thenReturn(Optional.of(mediaFile));
        lenient().when(ffprobeService.getKeyframes("/test/video.mkv")).thenReturn(List.of(0.0));
        FFmpeg ffmpegMock = mock(FFmpeg.class, RETURNS_SELF);
        lenient().when(jaffree.getFFMPEG()).thenReturn(ffmpegMock);
        lenient().when(ffmpegMock.executeAsync()).thenReturn(completedFFmpegFuture());

        TranscodePassRequestedData data = TranscodePassRequestedData.builder()
                .eventType(EventType.TRANSCODE_PASS_REQUESTED)
                .mediaFileId(id)
                .passKey(id + "_audio_1_192k")
                .passCategory("audio")
                .qualityLabel("192k")
                .audioStreamIndex(1)
                .build();

        hlsService.startPass(data);

        verify(mediaFileRepository).findById(id);
        // Await the async pass so it cannot race @TempDir cleanup
        CompletableFuture<Void> passFuture = transcodeService.getActiveFuture(id + "_audio_1_192k");
        if (passFuture != null) passFuture.get(5, TimeUnit.SECONDS);
    }

    // ========== startPass remote path ==========

    private MediaFileEntity remoteMediaFileEntity(UUID id) {
        NodeEntity remoteNode = NodeEntity.builder().name("remote-node").url("http://remote:8080").build();
        DirectoryEntity directory = DirectoryEntity.builder()
                .name("remote-dir").path("/remote")
                .directoryType(DirectoryType.LIBRARY).nodeEntity(remoteNode).build();
        MediaFileEntity entity = MediaFileEntity.builder()
                .path("/remote/video.mkv").size(0)
                .directoryEntityId(UUID.randomUUID())
                .mediaFileStreamEntity(List.of()).build();
        entity.setDirectoryEntity(directory);
        ReflectionTestUtils.setField(entity, "id", id);
        return entity;
    }

    @Test
    void startPassRemoteFileWatcherExitsOnMissingCacheDir() throws Exception {
        UUID id = UUID.randomUUID();
        String passKey = id + "_video_720p";

        HlsTranscodeService mockTs = mock(HlsTranscodeService.class);
        ReflectionTestUtils.setField(hlsService, "transcodeService", mockTs);
        when(mockTs.getActiveFuture(passKey)).thenReturn(CompletableFuture.completedFuture(null));
        when(nodeTokenManager.getDownloadToken()).thenReturn("test-token");
        when(mediaFileRepository.findById(id)).thenReturn(Optional.of(remoteMediaFileEntity(id)));

        ExecutorService syncExecutor = Executors.newSingleThreadExecutor();
        ReflectionTestUtils.setField(hlsService, "watcherExecutor", syncExecutor);

        TranscodePassRequestedData data = TranscodePassRequestedData.builder()
                .eventType(EventType.TRANSCODE_PASS_REQUESTED)
                .mediaFileId(id).passKey(passKey)
                .passCategory("video").qualityLabel("720p").build();

        hlsService.startPass(data);
        syncExecutor.shutdown();
        assertTrue(syncExecutor.awaitTermination(5, TimeUnit.SECONDS));

        // cacheDir doesn't exist → scanAndUploadBatch exits via NoSuchFileException → no uploads
        verify(remoteNodeClient, never()).uploadFile(any(), any(), any());
    }

    @Test
    void startPassRemoteWatcherUploadsStableSegments() throws Exception {
        UUID id = UUID.randomUUID();
        String passKey = id + "_video_720p";

        HlsTranscodeService mockTs = mock(HlsTranscodeService.class);
        ReflectionTestUtils.setField(hlsService, "transcodeService", mockTs);
        when(mockTs.getActiveFuture(passKey)).thenReturn(CompletableFuture.completedFuture(null));
        when(nodeTokenManager.getDownloadToken()).thenReturn("test-token");
        when(mediaFileRepository.findById(id)).thenReturn(Optional.of(remoteMediaFileEntity(id)));

        Path cacheDir = tempDir.resolve(id.toString());
        Files.createDirectories(cacheDir);
        Path segFile = cacheDir.resolve("seg_video_720p_00000.ts");
        Files.writeString(segFile, "ts-data");
        when(mockTs.stableSegmentOrNull(segFile)).thenReturn(segFile);

        ExecutorService syncExecutor = Executors.newSingleThreadExecutor();
        ReflectionTestUtils.setField(hlsService, "watcherExecutor", syncExecutor);

        TranscodePassRequestedData data = TranscodePassRequestedData.builder()
                .eventType(EventType.TRANSCODE_PASS_REQUESTED)
                .mediaFileId(id).passKey(passKey)
                .passCategory("video").qualityLabel("720p").build();

        hlsService.startPass(data);
        syncExecutor.shutdown();
        assertTrue(syncExecutor.awaitTermination(5, TimeUnit.SECONDS));

        verify(remoteNodeClient).uploadFile("http://remote:8080", id, segFile);
    }

    @Test
    void startPassRemoteWatcherHandlesUploadIOException() throws Exception {
        UUID id = UUID.randomUUID();
        String passKey = id + "_video_720p";

        HlsTranscodeService mockTs = mock(HlsTranscodeService.class);
        ReflectionTestUtils.setField(hlsService, "transcodeService", mockTs);
        when(mockTs.getActiveFuture(passKey)).thenReturn(CompletableFuture.completedFuture(null));
        when(nodeTokenManager.getDownloadToken()).thenReturn("test-token");
        when(mediaFileRepository.findById(id)).thenReturn(Optional.of(remoteMediaFileEntity(id)));

        Path cacheDir = tempDir.resolve(id.toString());
        Files.createDirectories(cacheDir);
        Path segFile = cacheDir.resolve("seg_video_720p_00000.ts");
        Files.writeString(segFile, "ts-data");
        when(mockTs.stableSegmentOrNull(segFile)).thenReturn(segFile);
        // First upload throws, second succeeds → loop terminates after second iteration
        doThrow(new IOException("upload failed")).doNothing()
                .when(remoteNodeClient).uploadFile(any(), any(), eq(segFile));

        ExecutorService syncExecutor = Executors.newSingleThreadExecutor();
        ReflectionTestUtils.setField(hlsService, "watcherExecutor", syncExecutor);

        TranscodePassRequestedData data = TranscodePassRequestedData.builder()
                .eventType(EventType.TRANSCODE_PASS_REQUESTED)
                .mediaFileId(id).passKey(passKey)
                .passCategory("video").qualityLabel("720p").build();

        hlsService.startPass(data);
        syncExecutor.shutdown();
        assertTrue(syncExecutor.awaitTermination(5, TimeUnit.SECONDS));

        verify(remoteNodeClient, times(2)).uploadFile(any(), any(), eq(segFile));
    }

    // ========== getAudioSegment transcoded path ==========

    @Test
    void getAudioSegmentTranscodedSendsTranscodePassRequested() throws Exception {
        UUID id = UUID.randomUUID();
        Path cacheDir = tempDir.resolve(id.toString());
        Files.createDirectories(cacheDir);
        Path segFile = cacheDir.resolve("seg_audio_1_192k_00000.ts");

        MediaFileStreamEntity audio = audioStream(1, "eng", "English");
        MediaFileEntity mediaFile = mediaFileEntity("/test/video.mkv", audio);
        when(mediaFileRepository.findById(id)).thenReturn(Optional.of(mediaFile));

        CompletableFuture.delayedExecutor(200, TimeUnit.MILLISECONDS).execute(() -> {
            try { Files.writeString(segFile, "audio-segment-data"); } catch (IOException _) { /* test writer failure surfaces as a missing segment, asserted below */ }
        });

        Path result = hlsService.getAudioSegment(id, "seg_audio_1_192k_00000.ts");

        assertNotNull(result);
        verify(messageSender).sendTranscodePassRequested(any(), any());
    }

    // ========== Pre-transcoded cache-hit (done marker) ==========

    @Test
    void getVideoSegmentServesCompletedPassFromDiskWithoutRestartingPass() throws Exception {
        // A file the pre-transcoder finished: done marker + segments on disk, but no in-memory
        // pass record (e.g. after a restart). Playback must serve from cache, not re-encode.
        UUID id = UUID.randomUUID();
        Path cacheDir = tempDir.resolve(id.toString());
        Files.createDirectories(cacheDir);
        Path segFile = cacheDir.resolve("seg_video_720p_00000.ts");
        Files.writeString(segFile, "video-segment-data");
        Files.writeString(cacheDir.resolve("done_seg_video_720p_"), "");

        Path result = hlsService.getVideoSegment(id, "seg_video_720p_00000.ts");

        assertEquals(segFile, result);
        verify(messageSender, never()).sendTranscodePassRequested(any(), any());
        verify(mediaFileRepository, never()).findById(any());
        verify(jaffree, never()).getFFMPEG();
    }

    @Test
    void getAudioSegmentServesCompletedPassFromDiskWithoutRestartingPass() throws Exception {
        UUID id = UUID.randomUUID();
        Path cacheDir = tempDir.resolve(id.toString());
        Files.createDirectories(cacheDir);
        Path segFile = cacheDir.resolve("seg_audio_1_192k_00000.ts");
        Files.writeString(segFile, "audio-segment-data");
        Files.writeString(cacheDir.resolve("done_seg_audio_1_192k_"), "");

        Path result = hlsService.getAudioSegment(id, "seg_audio_1_192k_00000.ts");

        assertEquals(segFile, result);
        verify(messageSender, never()).sendTranscodePassRequested(any(), any());
        verify(mediaFileRepository, never()).findById(any());
        verify(jaffree, never()).getFFMPEG();
    }

    @Test
    void getVideoSegmentWithDoneMarkerButMissingSegmentStillRequestsPass() throws Exception {
        // Done marker present but the requested segment is gone: not a cache-hit — the pass
        // must be (re)requested so the missing segment is regenerated.
        UUID id = UUID.randomUUID();
        Path cacheDir = tempDir.resolve(id.toString());
        Files.createDirectories(cacheDir);
        Files.writeString(cacheDir.resolve("done_seg_video_720p_"), "");
        Path segFile = cacheDir.resolve("seg_video_720p_00000.ts");

        MediaFileStreamEntity video = videoStream(0, 1920, 1080);
        MediaFileEntity mediaFile = mediaFileEntity("/test/video.mkv", video);
        when(mediaFileRepository.findById(id)).thenReturn(Optional.of(mediaFile));

        CompletableFuture.delayedExecutor(200, TimeUnit.MILLISECONDS).execute(() -> {
            try { Files.writeString(segFile, "video-segment-data"); } catch (IOException _) { /* surfaces as a missing segment below */ }
        });

        Path result = hlsService.getVideoSegment(id, "seg_video_720p_00000.ts");

        assertNotNull(result);
        verify(messageSender).sendTranscodePassRequested(any(), any());
    }

    // ========== getSrtSubtitle embedded path ==========

    @Test
    void getSrtSubtitleEmbeddedExtractsFromMedia() throws Exception {
        UUID mediaFileId = UUID.randomUUID();
        UUID subtitleId = UUID.randomUUID();
        Path cacheDir = tempDir.resolve(mediaFileId.toString());
        Files.createDirectories(cacheDir);

        MediaFileStreamEntity subtitleStream = MediaFileStreamEntity.builder()
                .codecType(StreamCodecType.SUBTITLE)
                .codecName("subrip")
                .streamIndex(2)
                .path("")
                .width(0).height(0)
                .build();
        ReflectionTestUtils.setField(subtitleStream, "id", subtitleId);

        MediaFileEntity mediaFile = mediaFileEntity("/test/video.mkv");
        when(mediaFileRepository.findById(mediaFileId)).thenReturn(Optional.of(mediaFile));
        when(mediaFileStreamRepository.findById(subtitleId)).thenReturn(Optional.of(subtitleStream));

        FFmpeg ffmpegMock = mock(FFmpeg.class, RETURNS_SELF);
        when(jaffree.getFFMPEG()).thenReturn(ffmpegMock);
        doAnswer(inv -> {
            Files.writeString(cacheDir.resolve("sub_" + subtitleId + ".srt"),
                    "1\n00:00:01,000 --> 00:00:03,000\nHello\n\n");
            return null;
        }).when(ffmpegMock).execute();

        Path result = hlsService.getSrtSubtitle(mediaFileId, "sub_" + subtitleId + ".srt");

        assertNotNull(result);
        assertTrue(result.toString().endsWith("_offset.srt"));
    }

    // ========== generateAllPlaylists SRT subtitle format ==========

    @Test
    void generateAllPlaylistsWithSrtSubtitleFormat() throws IOException {
        UUID id = UUID.randomUUID();
        UUID subtitleId = UUID.randomUUID();
        MediaFileStreamEntity video = videoStream(0, 1920, 1080);
        MediaFileStreamEntity audio = audioStream(1, "eng", "English");
        MediaFileStreamEntity sub = subtitleStream(subtitleId, 2, "eng", "English");
        MediaFileEntity mediaFile = mediaFileEntity("/test/video.mkv", video, audio, sub);
        when(mediaFileRepository.findById(id)).thenReturn(Optional.of(mediaFile));
        when(ffprobeService.getKeyframes("/test/video.mkv")).thenReturn(List.of(0.0, 5.0));
        when(ffprobeService.getTotalDuration("/test/video.mkv")).thenReturn(10.0);

        hlsService.generateAllPlaylists(id, true, false, SubtitleFormat.SRT);

        Path subPlaylist = tempDir.resolve(id.toString()).resolve("stream_sub_" + subtitleId + "_srt.m3u8");
        assertTrue(Files.exists(subPlaylist));
        assertTrue(Files.readString(subPlaylist).contains("sub_" + subtitleId + ".srt"));
    }

    // ========== getSubtitleSegment with embedded subtitle ==========

    @Test
    void getSubtitleSegmentWithEmbeddedSubtitleExtractsViafFmpeg() throws IOException {
        UUID mediaFileId = UUID.randomUUID();
        UUID subtitleId = UUID.randomUUID();
        Path cacheDir = tempDir.resolve(mediaFileId.toString());
        Files.createDirectories(cacheDir);

        MediaFileStreamEntity subtitleStream = MediaFileStreamEntity.builder()
                .codecType(StreamCodecType.SUBTITLE)
                .codecName("subrip")
                .streamIndex(2)
                .path("")
                .width(0).height(0)
                .build();
        ReflectionTestUtils.setField(subtitleStream, "id", subtitleId);

        when(mediaFileStreamRepository.findById(subtitleId)).thenReturn(Optional.of(subtitleStream));
        when(mediaFileRepository.findById(mediaFileId)).thenReturn(Optional.of(mediaFileEntity("/test/video.mkv")));
        when(ffprobeService.getKeyframes("/test/video.mkv")).thenReturn(List.of(0.0));
        when(ffprobeService.getTotalDuration("/test/video.mkv")).thenReturn(10.0);

        FFmpeg ffmpegMock = mock(FFmpeg.class, RETURNS_SELF);
        when(jaffree.getFFMPEG()).thenReturn(ffmpegMock);
        doAnswer(inv -> {
            Files.writeString(cacheDir.resolve("sub_" + subtitleId + ".srt"),
                    "1\n00:00:01,000 --> 00:00:03,000\nHello\n\n");
            return null;
        }).when(ffmpegMock).execute();

        String vtt = hlsService.getSubtitleSegment(mediaFileId,
                String.format("seg_sub_%s_00000.vtt", subtitleId));

        assertTrue(vtt.startsWith("WEBVTT"));
        assertTrue(vtt.contains("Hello"));
    }

    @Test
    void getSubtitleSegmentWithEmbeddedSubtitleUsesCachedSrtFile() throws IOException {
        UUID mediaFileId = UUID.randomUUID();
        UUID subtitleId = UUID.randomUUID();
        Path cacheDir = tempDir.resolve(mediaFileId.toString());
        Files.createDirectories(cacheDir);
        // Pre-create the SRT file — extractEmbeddedSubtitleToSrt should return it without running FFmpeg
        Files.writeString(cacheDir.resolve("sub_" + subtitleId + ".srt"),
                "1\n00:00:01,000 --> 00:00:03,000\nCached\n\n");

        MediaFileStreamEntity subtitleStream = MediaFileStreamEntity.builder()
                .codecType(StreamCodecType.SUBTITLE)
                .codecName("subrip")
                .streamIndex(2)
                .path("")
                .width(0).height(0)
                .build();
        ReflectionTestUtils.setField(subtitleStream, "id", subtitleId);

        when(mediaFileStreamRepository.findById(subtitleId)).thenReturn(Optional.of(subtitleStream));
        when(mediaFileRepository.findById(mediaFileId)).thenReturn(Optional.of(mediaFileEntity("/test/video.mkv")));
        when(ffprobeService.getKeyframes("/test/video.mkv")).thenReturn(List.of(0.0));
        when(ffprobeService.getTotalDuration("/test/video.mkv")).thenReturn(10.0);

        String vtt = hlsService.getSubtitleSegment(mediaFileId,
                String.format("seg_sub_%s_00000.vtt", subtitleId));

        assertTrue(vtt.contains("Cached"));
        verify(jaffree, never()).getFFMPEG();
    }

    @Test
    void getSubtitleSegmentParsesEdgeCaseSrtFormats() throws IOException {
        UUID mediaFileId = UUID.randomUUID();
        UUID subtitleId = UUID.randomUUID();
        Path srtFile = tempDir.resolve("edge.srt");
        // SRT with: unexpected non-blank line, no-millisecond timestamp, invalid timestamp
        Files.writeString(srtFile, """
                Unexpected line
                1
                00:00:01 --> 00:00:03
                NoMilliseconds

                bad-ts --> 00:00:05
                BadEntry

                """);

        MediaFileStreamEntity subtitleStream = MediaFileStreamEntity.builder()
                .codecType(StreamCodecType.EXTERNAL_SUBTITLE)
                .codecName("srt")
                .path(srtFile.toString())
                .width(0).height(0)
                .build();
        ReflectionTestUtils.setField(subtitleStream, "id", subtitleId);

        when(mediaFileStreamRepository.findById(subtitleId)).thenReturn(Optional.of(subtitleStream));
        when(mediaFileRepository.findById(mediaFileId)).thenReturn(Optional.of(mediaFileEntity("/test/video.mkv")));
        when(ffprobeService.getKeyframes("/test/video.mkv")).thenReturn(List.of(0.0));
        when(ffprobeService.getTotalDuration("/test/video.mkv")).thenReturn(10.0);

        String vtt = hlsService.getSubtitleSegment(mediaFileId,
                String.format("seg_sub_%s_00000.vtt", subtitleId));

        assertTrue(vtt.startsWith("WEBVTT"));
        assertTrue(vtt.contains("NoMilliseconds"));
        assertFalse(vtt.contains("BadEntry"));
    }

    // ========== generateAllPlaylists audio-only (synthetic keyframes) ==========

    @Test
    void generateAllPlaylistsAudioOnlyBuildsSyntheticKeyframes() throws IOException {
        UUID id = UUID.randomUUID();
        MediaFileStreamEntity audio = audioStream(0, "eng", "English");
        // No video stream → synthetic keyframes, and the keyframe probe is skipped entirely
        MediaFileEntity mediaFile = mediaFileEntity("/test/audio.flac", audio);
        when(mediaFileRepository.findById(id)).thenReturn(Optional.of(mediaFile));
        when(ffprobeService.getTotalDuration("/test/audio.flac")).thenReturn(30.0);

        hlsService.generateAllPlaylists(id, false, true, SubtitleFormat.WEBVTT);

        Path cacheFile = tempDir.resolve(id.toString()).resolve("master_d0_t1_sWEBVTT.m3u8");
        assertTrue(Files.exists(cacheFile));
        verify(ffprobeService, never()).getKeyframes(any());
    }

    @Test
    void generateAllPlaylistsAudioOnlyWritesAllMasterVariants() throws IOException {
        UUID id = UUID.randomUUID();
        MediaFileEntity mediaFile = mediaFileEntity("/test/audio.flac", audioStream(0, "eng", "English"));
        when(mediaFileRepository.findById(id)).thenReturn(Optional.of(mediaFile));
        when(ffprobeService.getTotalDuration("/test/audio.flac")).thenReturn(30.0);

        hlsService.generateAllPlaylists(id, false, true, SubtitleFormat.WEBVTT);

        // Every direct/transcode/subtitle-format combination is a cache hit for audio-only files.
        for (String variant : List.of("d1_t0", "d0_t1", "d1_t1")) {
            for (SubtitleFormat format : SubtitleFormat.values()) {
                assertTrue(Files.exists(tempDir.resolve(id.toString())
                                .resolve("master_" + variant + "_s" + format.name() + ".m3u8")),
                        "expected master variant " + variant + "/" + format);
            }
        }
    }

    @Test
    void generateAllPlaylistsAudioOnlySeedsDurationFromDatabase() throws IOException {
        UUID id = UUID.randomUUID();
        MediaFileEntity mediaFile = mediaFileEntity("/test/audio.flac", audioStream(0, "eng", "English"));
        mediaFile.setDurationInMilliseconds(30_000);
        when(mediaFileRepository.findById(id)).thenReturn(Optional.of(mediaFile));

        hlsService.generateAllPlaylists(id, false, true, SubtitleFormat.WEBVTT);

        // Duration came from the entity, so no ffprobe at all ran for this file.
        verify(ffprobeService, never()).getTotalDuration(any());
        verify(ffprobeService, never()).getKeyframes(any());
        assertTrue(Files.exists(tempDir.resolve(id.toString()).resolve("master_d0_t1_sWEBVTT.m3u8")));
    }

    // ========== generateAllPlaylists remote upload IOException ==========

    @Test
    void generateAllPlaylistsSwallowsIOExceptionOnPlaylistUpload() throws IOException {
        UUID id = UUID.randomUUID();
        MediaFileStreamEntity videoStream = videoStream(0, 1920, 1080);
        MediaFileStreamEntity audioStream = audioStream(1, "eng", "English");
        NodeEntity remoteNode = NodeEntity.builder().name("remote-node").url("http://remote:8080").build();
        DirectoryEntity directory = DirectoryEntity.builder()
                .name("remote-dir").path("/remote")
                .directoryType(DirectoryType.LIBRARY).nodeEntity(remoteNode).build();
        MediaFileEntity remoteFile = MediaFileEntity.builder()
                .path("/remote/video.mkv").size(0)
                .directoryEntityId(UUID.randomUUID())
                .mediaFileStreamEntity(List.of(videoStream, audioStream)).build();
        remoteFile.setDirectoryEntity(directory);
        ReflectionTestUtils.setField(remoteFile, "id", id);

        when(mediaFileRepository.findById(id)).thenReturn(Optional.of(remoteFile));
        when(nodeTokenManager.getDownloadToken()).thenReturn("token");
        String remoteUrl = "http://remote:8080/mediaFile/" + id + "/download?token=token";
        when(ffprobeService.getKeyframes(remoteUrl)).thenReturn(List.of(0.0, 5.0));
        when(ffprobeService.getTotalDuration(remoteUrl)).thenReturn(10.0);
        doThrow(new IOException("upload failed")).when(remoteNodeClient).uploadFile(any(), any(), any());

        assertDoesNotThrow(() -> hlsService.generateAllPlaylists(id, true, false, SubtitleFormat.WEBVTT));
        verify(remoteNodeClient, atLeastOnce()).uploadFile(eq("http://remote:8080"), eq(id), any());
    }

    // ========== watcherExecutor thread factory ==========

    @Test
    void watcherExecutorUsesCustomThreadFactory() throws Exception {
        ExecutorService executor = (ExecutorService) ReflectionTestUtils.getField(hlsService, "watcherExecutor");
        CountDownLatch latch = new CountDownLatch(1);
        AtomicBoolean isDaemon = new AtomicBoolean(false);
        executor.submit(() -> {
            isDaemon.set(Thread.currentThread().isDaemon());
            latch.countDown();
        });
        assertTrue(latch.await(2, TimeUnit.SECONDS));
        assertTrue(isDaemon.get());
    }

    // ========== Passes already completed on disk ==========

    @Test
    void startAllPassesSkipsPassesThatCompletedOnDisk() throws IOException {
        UUID id = UUID.randomUUID();
        Path cacheDir = Files.createDirectories(tempDir.resolve(id.toString()));
        for (String prefix : List.of("done_seg_video_720p_", "done_seg_video_480p_",
                "done_seg_audio_1_192k_", "done_seg_audio_1_64k_")) {
            Files.writeString(cacheDir.resolve(prefix), "");
        }

        MediaFileEntity mediaFile = mediaFileEntity("/test/video.mkv",
                videoStream(0, 1920, 1080), audioStream(1, "eng", "English"));
        when(mediaFileRepository.findById(id)).thenReturn(Optional.of(mediaFile));

        hlsService.startAllPasses(id, false, true);

        // Every pass already wrote its done marker — no FFmpeg is started at all.
        verify(jaffree, never()).getFFMPEG();
    }

    @Test
    void getVideoSegmentServesStableSegmentWithoutRequestingAPass() throws IOException {
        ReflectionTestUtils.setField(transcodeService, "segmentStabilityMs", 0L);
        UUID id = UUID.randomUUID();
        Path cacheDir = Files.createDirectories(tempDir.resolve(id.toString()));
        Path segment = cacheDir.resolve("seg_video_720p_00000.ts");
        Files.writeString(segment, "video-data");
        // First observation records the size sample; the segment is stable from the next call on.
        transcodeService.stableSegmentOrNull(segment);

        Path result = hlsService.getVideoSegment(id, "seg_video_720p_00000.ts");

        assertEquals(segment, result);
        verify(messageSender, never()).sendTranscodePassRequested(any(), any());
        verify(jaffree, never()).getFFMPEG();
    }

    // ========== Audio-only stream playlist ==========

    @Test
    void getStreamPlaylistForAudioOnlyFileUsesSyntheticKeyframes() throws IOException {
        UUID id = UUID.randomUUID();
        MediaFileEntity mediaFile = mediaFileEntity("/test/audio.flac", audioStream(0, "eng", "English"));
        mediaFile.setDurationInMilliseconds(25_000);
        when(mediaFileRepository.findById(id)).thenReturn(Optional.of(mediaFile));

        String playlist = hlsService.getStreamPlaylist(id, "stream_audio_0_192k.m3u8");

        // Synthetic 10s grid: 0-10, 10-20, 20-25
        assertTrue(playlist.contains("seg_audio_0_192k_00000.ts"));
        assertTrue(playlist.contains("seg_audio_0_192k_00002.ts"));
        verify(ffprobeService, never()).getKeyframes(any());
        verify(ffprobeService, never()).getTotalDuration(any());
    }

    // ========== Helpers ==========

    private MediaFileEntity mediaFileEntity(String path, MediaFileStreamEntity... streams) {
        NodeEntity node = NodeEntity.builder()
                .name(LOCAL_NODE_NAME)
                .url("http://localhost:8080")
                .build();
        DirectoryEntity directory = DirectoryEntity.builder()
                .name("test-dir")
                .path("/test")
                .directoryType(DirectoryType.LIBRARY)
                .nodeEntity(node)
                .build();
        MediaFileEntity entity = MediaFileEntity.builder()
                .path(path)
                .size(0)
                .directoryEntityId(UUID.randomUUID())
                .mediaFileStreamEntity(List.of(streams))
                .build();
        entity.setDirectoryEntity(directory);
        return entity;
    }

    private MediaFileStreamEntity videoStream(int index, int width, int height) {
        return MediaFileStreamEntity.builder()
                .streamIndex(index)
                .codecType(StreamCodecType.VIDEO)
                .codecName("h264")
                .width(width).height(height)
                .path("")
                .build();
    }

    private MediaFileStreamEntity audioStream(int index, String language, String title) {
        return MediaFileStreamEntity.builder()
                .streamIndex(index)
                .codecType(StreamCodecType.AUDIO)
                .codecName("aac")
                .width(0).height(0)
                .language(language)
                .title(title)
                .path("")
                .build();
    }

    private MediaFileStreamEntity subtitleStream(UUID id, int index, String language, String title) {
        MediaFileStreamEntity stream = MediaFileStreamEntity.builder()
                .streamIndex(index)
                .codecType(StreamCodecType.EXTERNAL_SUBTITLE)
                .codecName("srt")
                .width(0).height(0)
                .language(language)
                .title(title)
                .path("")
                .build();
        ReflectionTestUtils.setField(stream, "id", id);
        return stream;
    }
}
