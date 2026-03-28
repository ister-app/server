package app.ister.transcoder;

import app.ister.core.entity.MediaFileEntity;
import app.ister.core.entity.MediaFileStreamEntity;
import app.ister.core.enums.StreamCodecType;
import app.ister.core.repository.MediaFileRepository;
import app.ister.core.repository.MediaFileStreamRepository;
import app.ister.core.utils.Jaffree;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import com.github.kokorin.jaffree.ffmpeg.FFmpeg;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.mockito.Mockito.RETURNS_SELF;

@ExtendWith(MockitoExtension.class)
class HlsServiceTest {

    @Mock private Jaffree jaffree;
    @Mock private FfprobeService ffprobeService;
    @Mock private MediaFileRepository mediaFileRepository;
    @Mock private MediaFileStreamRepository mediaFileStreamRepository;

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
        ReflectionTestUtils.setField(transcodeService, "concurrentFileSlots", new Semaphore(10));
        hlsService = new HlsService(playlistBuilder, subtitleService, transcodeService,
                mediaFileRepository, mediaFileStreamRepository);
        ReflectionTestUtils.setField(hlsService, "tmpDir", tempDir.toString());
    }

    // ========== Master playlist ==========

    @Test
    void getMasterPlaylistDirectOnly() throws IOException {
        UUID id = UUID.randomUUID();
        MediaFileStreamEntity videoStream = videoStream(0, 1920, 1080);
        MediaFileStreamEntity audioStream = audioStream(1, "eng", "English");
        MediaFileEntity mediaFile = mediaFileEntity(id, "/test/video.mkv", videoStream, audioStream);

        when(mediaFileRepository.findById(id)).thenReturn(Optional.of(mediaFile));
        when(ffprobeService.getKeyframes("/test/video.mkv")).thenReturn(List.of(0.0, 5.0));
        when(ffprobeService.getTotalDuration("/test/video.mkv")).thenReturn(10.0);

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
        MediaFileEntity mediaFile = mediaFileEntity(id, "/test/video.mkv", videoStream, audioStream);

        when(mediaFileRepository.findById(id)).thenReturn(Optional.of(mediaFile));
        when(ffprobeService.getKeyframes("/test/video.mkv")).thenReturn(List.of(0.0, 5.0));
        when(ffprobeService.getTotalDuration("/test/video.mkv")).thenReturn(10.0);

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
        MediaFileEntity mediaFile = mediaFileEntity(id, "/test/video.mkv", videoStream, audioStream);

        when(mediaFileRepository.findById(id)).thenReturn(Optional.of(mediaFile));
        when(ffprobeService.getKeyframes("/test/video.mkv")).thenReturn(List.of(0.0, 5.0));
        when(ffprobeService.getTotalDuration("/test/video.mkv")).thenReturn(10.0);

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
        MediaFileEntity mediaFile = mediaFileEntity(id, "/test/video.mkv", videoStream, audioStream, subtitleStream);

        when(mediaFileRepository.findById(id)).thenReturn(Optional.of(mediaFile));
        when(ffprobeService.getKeyframes("/test/video.mkv")).thenReturn(List.of(0.0, 5.0));
        when(ffprobeService.getTotalDuration("/test/video.mkv")).thenReturn(10.0);

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
        MediaFileEntity mediaFile = mediaFileEntity(id, "/test/video.mkv", videoStream, audioStream);

        when(mediaFileRepository.findById(id)).thenReturn(Optional.of(mediaFile));
        when(ffprobeService.getKeyframes(any())).thenReturn(List.of(0.0, 5.0));
        when(ffprobeService.getTotalDuration(any())).thenReturn(10.0);

        hlsService.getMasterPlaylist(id, true, false, SubtitleFormat.WEBVTT);
        hlsService.getMasterPlaylist(id, true, false, SubtitleFormat.WEBVTT);

        // ffprobe should only be called once (second call uses cache)
        verify(ffprobeService, times(1)).getKeyframes(any());
    }

    // ========== Stream playlists ==========

    @Test
    void getStreamPlaylistForVideo720pUsesKeyframes() throws IOException {
        UUID id = UUID.randomUUID();
        when(mediaFileRepository.findById(id)).thenReturn(Optional.of(mediaFileEntity(id, "/test/video.mkv")));
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
        when(mediaFileRepository.findById(id)).thenReturn(Optional.of(mediaFileEntity(id, "/test/video.mkv")));
        when(ffprobeService.getTotalDuration("/test/video.mkv")).thenReturn(60.0);
        when(ffprobeService.getKeyframes("/test/video.mkv")).thenReturn(List.of(0.0));

        String playlist = hlsService.getStreamPlaylist(id, "stream_audio_1_copy.m3u8");

        assertTrue(playlist.contains("seg_audio_0.000000_60.000000_1_copy.ts"));
        assertTrue(playlist.contains("#EXT-X-ENDLIST"));
    }

    @Test
    void getStreamPlaylistForAudio192kUsesKeyframes() throws IOException {
        UUID id = UUID.randomUUID();
        when(mediaFileRepository.findById(id)).thenReturn(Optional.of(mediaFileEntity(id, "/test/video.mkv")));
        when(ffprobeService.getKeyframes("/test/video.mkv")).thenReturn(List.of(0.0, 4.0));
        when(ffprobeService.getTotalDuration("/test/video.mkv")).thenReturn(8.0);

        String playlist = hlsService.getStreamPlaylist(id, "stream_audio_1_192k.m3u8");

        assertTrue(playlist.contains("seg_audio_1_192k_00000.ts"));
        assertTrue(playlist.contains("seg_audio_1_192k_00001.ts"));
    }

    @Test
    void getStreamPlaylistUnknownFilenameThrows() throws IOException {
        UUID id = UUID.randomUUID();
        when(mediaFileRepository.findById(id)).thenReturn(Optional.of(mediaFileEntity(id, "/test/video.mkv")));
        when(ffprobeService.getKeyframes(any())).thenReturn(List.of(0.0, 5.0));
        when(ffprobeService.getTotalDuration(any())).thenReturn(10.0);

        assertThrows(IllegalArgumentException.class,
                () -> hlsService.getStreamPlaylist(id, "stream_unknown_foo.m3u8"));
    }

    @Test
    void getStreamPlaylistIsCached() throws IOException {
        UUID id = UUID.randomUUID();
        when(mediaFileRepository.findById(id)).thenReturn(Optional.of(mediaFileEntity(id, "/test/video.mkv")));
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
        when(mediaFileRepository.findById(mediaFileId)).thenReturn(Optional.of(mediaFileEntity(mediaFileId, "/test/video.mkv")));
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
        Files.writeString(srtFile,
                "1\n00:00:01,000 --> 00:00:03,000\nInside\n\n" +
                "2\n00:00:20,000 --> 00:00:25,000\nOutside\n\n");

        MediaFileStreamEntity subtitleStream = MediaFileStreamEntity.builder()
                .codecType(StreamCodecType.EXTERNAL_SUBTITLE)
                .codecName("srt")
                .path(srtFile.toString())
                .width(0).height(0)
                .build();
        ReflectionTestUtils.setField(subtitleStream, "id", subtitleId);

        when(mediaFileStreamRepository.findById(subtitleId)).thenReturn(Optional.of(subtitleStream));
        when(mediaFileRepository.findById(mediaFileId)).thenReturn(Optional.of(mediaFileEntity(mediaFileId, "/test/video.mkv")));
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
        when(mediaFileRepository.findById(mediaFileId)).thenReturn(Optional.of(mediaFileEntity(mediaFileId, "/test/video.mkv")));
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
        when(mediaFileRepository.findById(id)).thenReturn(Optional.of(mediaFileEntity(id, "/test/video.mkv")));
        // Segments: 0-7s (7s), 7-10s (3s) → max=7 → target=7
        when(ffprobeService.getKeyframes("/test/video.mkv")).thenReturn(List.of(0.0, 7.0));
        when(ffprobeService.getTotalDuration("/test/video.mkv")).thenReturn(10.0);

        String playlist = hlsService.getStreamPlaylist(id, "stream_video_720p.m3u8");

        assertTrue(playlist.contains("#EXT-X-TARGETDURATION:7"));
    }

    @Test
    void vodPlaylistThrowsWhenNoKeyframes() throws IOException {
        UUID id = UUID.randomUUID();
        when(mediaFileRepository.findById(id)).thenReturn(Optional.of(mediaFileEntity(id, "/test/video.mkv")));
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

        when(mediaFileRepository.findById(id)).thenReturn(Optional.of(mediaFileEntity(id, "/test/video.mkv")));
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
            return null;
        }).when(ffmpegMock).execute();

        ExecutorService pool = Executors.newFixedThreadPool(2);

        Future<Path> f1 = pool.submit(() -> hlsService.getVideoSegment(id, "seg_video_720p_00000.ts"));
        passStarted.await(3, TimeUnit.SECONDS);

        Future<Path> f2 = pool.submit(() -> hlsService.getVideoSegment(id, "seg_video_720p_00001.ts"));

        releasePass.countDown();

        f1.get(5, TimeUnit.SECONDS);
        f2.get(5, TimeUnit.SECONDS);
        pool.shutdown();

        // Only ONE FFmpeg pass started for both segments of the same quality
        verify(ffmpegMock, times(1)).execute();
    }

    @Test
    void generationLocksAreBoundedByQualityCount() throws Exception {
        UUID id = UUID.randomUUID();
        Path cacheDir = tempDir.resolve(id.toString());
        Files.createDirectories(cacheDir);

        when(mediaFileRepository.findById(id)).thenReturn(Optional.of(mediaFileEntity(id, "/test/video.mkv")));
        when(ffprobeService.getKeyframes("/test/video.mkv")).thenReturn(List.of(0.0, 5.0));

        FFmpeg ffmpegMock = mock(FFmpeg.class, RETURNS_SELF);
        when(jaffree.getFFMPEG()).thenReturn(ffmpegMock);

        doAnswer(inv -> {
            for (int i = 0; i < 2; i++) {
                Files.writeString(cacheDir.resolve(String.format("seg_video_720p_%05d.ts", i)), "data");
            }
            return null;
        }).when(ffmpegMock).execute();

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

        when(mediaFileRepository.findById(id)).thenReturn(Optional.of(mediaFileEntity(id, "/test/video.mkv")));
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
            return null;
        }).when(ffmpegMock).execute();

        Path videoResult = hlsService.getVideoSegment(id, "seg_video_720p_00000.ts");
        Path audioResult = hlsService.getAudioSegment(id, "seg_audio_1_192k_00000.ts");

        verify(ffmpegMock, times(2)).execute();
        assertTrue(videoResult.toString().contains("seg_video_720p_00000.ts"));
        assertTrue(audioResult.toString().contains("seg_audio_1_192k_00000.ts"));
    }

    // ========== Audio COPY segment ==========

    @Test
    void getAudioSegmentCopyModeGeneratesOnDemand() throws Exception {
        UUID id = UUID.randomUUID();
        Path cacheDir = tempDir.resolve(id.toString());
        Files.createDirectories(cacheDir);

        when(mediaFileRepository.findById(id)).thenReturn(Optional.of(mediaFileEntity(id, "/test/video.mkv")));

        FFmpeg ffmpegMock = mock(FFmpeg.class, RETURNS_SELF);
        when(jaffree.getFFMPEG()).thenReturn(ffmpegMock);

        String segFilename = "seg_audio_0.000000_60.000000_1_copy.ts";
        doAnswer(inv -> {
            Files.writeString(cacheDir.resolve(segFilename), "audio-copy-data");
            return null;
        }).when(ffmpegMock).execute();

        Path result = hlsService.getAudioSegment(id, segFilename);

        assertNotNull(result);
        assertTrue(result.toString().endsWith(segFilename));
    }

    @Test
    void getAudioSegmentCopyModeIsCached() throws Exception {
        UUID id = UUID.randomUUID();
        Path cacheDir = tempDir.resolve(id.toString());
        Files.createDirectories(cacheDir);

        when(mediaFileRepository.findById(id)).thenReturn(Optional.of(mediaFileEntity(id, "/test/video.mkv")));

        FFmpeg ffmpegMock = mock(FFmpeg.class, RETURNS_SELF);
        when(jaffree.getFFMPEG()).thenReturn(ffmpegMock);

        String segFilename = "seg_audio_0.000000_60.000000_1_copy.ts";
        doAnswer(inv -> {
            Files.writeString(cacheDir.resolve(segFilename), "audio-copy-data");
            return null;
        }).when(ffmpegMock).execute();

        hlsService.getAudioSegment(id, segFilename);
        hlsService.getAudioSegment(id, segFilename);

        // FFmpeg should only be called once (second call hits cache)
        verify(ffmpegMock, times(1)).execute();
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
        when(mediaFileRepository.findById(mediaFileId)).thenReturn(Optional.of(mediaFileEntity(mediaFileId, "/test/video.mkv")));
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

        when(mediaFileRepository.findById(id)).thenReturn(Optional.of(mediaFileEntity(id, "/test/video.mkv")));
        when(ffprobeService.getKeyframes("/test/video.mkv")).thenReturn(List.of(0.0, 5.0));

        FFmpeg ffmpegMock = mock(FFmpeg.class, RETURNS_SELF);
        when(jaffree.getFFMPEG()).thenReturn(ffmpegMock);

        doAnswer(inv -> {
            Files.writeString(cacheDir.resolve("seg_video_720p_00000.ts"), "video-data");
            return null;
        }).when(ffmpegMock).execute();

        Path result = hlsService.getVideoSegment(id, "seg_video_720p_00000.ts");

        assertNotNull(result);
        verify(ffmpegMock, times(1)).execute();
    }

    @Test
    void nvdecModeStartsVideoPassNormally() throws Exception {
        UUID id = UUID.randomUUID();
        Path cacheDir = tempDir.resolve(id.toString());
        Files.createDirectories(cacheDir);

        ReflectionTestUtils.setField(transcodeService, "hwaccelProperty", "nvdec");

        when(mediaFileRepository.findById(id)).thenReturn(Optional.of(mediaFileEntity(id, "/test/video.mkv")));
        when(ffprobeService.getKeyframes("/test/video.mkv")).thenReturn(List.of(0.0, 5.0));

        FFmpeg ffmpegMock = mock(FFmpeg.class, RETURNS_SELF);
        when(jaffree.getFFMPEG()).thenReturn(ffmpegMock);

        doAnswer(inv -> {
            Files.writeString(cacheDir.resolve("seg_video_720p_00000.ts"), "video-data");
            return null;
        }).when(ffmpegMock).execute();

        Path result = hlsService.getVideoSegment(id, "seg_video_720p_00000.ts");

        assertNotNull(result);
        verify(ffmpegMock, times(1)).execute();
    }

    @Test
    void hwaccelDoesNotAffectAudioPass() throws Exception {
        UUID id = UUID.randomUUID();
        Path cacheDir = tempDir.resolve(id.toString());
        Files.createDirectories(cacheDir);

        ReflectionTestUtils.setField(transcodeService, "hwaccelProperty", "vaapi");

        when(mediaFileRepository.findById(id)).thenReturn(Optional.of(mediaFileEntity(id, "/test/video.mkv")));
        when(ffprobeService.getKeyframes("/test/video.mkv")).thenReturn(List.of(0.0, 5.0));

        FFmpeg ffmpegMock = mock(FFmpeg.class, RETURNS_SELF);
        when(jaffree.getFFMPEG()).thenReturn(ffmpegMock);

        doAnswer(inv -> {
            Files.writeString(cacheDir.resolve("seg_audio_1_192k_00000.ts"), "audio-data");
            return null;
        }).when(ffmpegMock).execute();

        Path result = hlsService.getAudioSegment(id, "seg_audio_1_192k_00000.ts");

        assertNotNull(result);
        verify(ffmpegMock, times(1)).execute();
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

        when(mediaFileRepository.findById(id)).thenReturn(Optional.of(mediaFileEntity(id, "/test/video.mkv")));
        when(ffprobeService.getKeyframes("/test/video.mkv")).thenReturn(List.of(0.0, 5.0));

        FFmpeg ffmpegMock = mock(FFmpeg.class, RETURNS_SELF);
        when(jaffree.getFFMPEG()).thenReturn(ffmpegMock);

        doAnswer(inv -> {
            Files.writeString(cacheDir.resolve("seg_video_720p_00000.ts"), "video-data");
            Files.writeString(cacheDir.resolve("seg_audio_1_192k_00000.ts"), "audio-data");
            return null;
        }).when(ffmpegMock).execute();

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

        when(mediaFileRepository.findById(id1)).thenReturn(Optional.of(mediaFileEntity(id1, "/test/video1.mkv")));
        when(mediaFileRepository.findById(id2)).thenReturn(Optional.of(mediaFileEntity(id2, "/test/video2.mkv")));
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
            return null;
        }).when(ffmpegMock).execute();

        ExecutorService pool = Executors.newFixedThreadPool(2);

        Future<Path> f1 = pool.submit(() -> hlsService.getVideoSegment(id1, "seg_video_720p_00000.ts"));
        file1PassStarted.await(3, TimeUnit.SECONDS);

        // File 2 starts while file 1 holds the slot — it should be queued
        Future<Path> f2 = pool.submit(() -> hlsService.getVideoSegment(id2, "seg_video_720p_00000.ts"));

        // Give file 2 time to queue up, then release file 1
        Thread.sleep(200);
        releaseFile1.countDown();

        f1.get(5, TimeUnit.SECONDS);
        f2.get(5, TimeUnit.SECONDS);
        pool.shutdown();

        // File 2 must NOT have run while file 1 was still active
        assertFalse(file2RanWhileFile1Active.get(), "File 2 should have waited for file 1's slot to be released");
        verify(ffmpegMock, times(2)).execute();
    }

    @Test
    void slotIsReleasedAfterAllPassesComplete() throws Exception {
        ReflectionTestUtils.setField(transcodeService, "concurrentFileSlots", new Semaphore(1));

        UUID id = UUID.randomUUID();
        Path cacheDir = tempDir.resolve(id.toString());
        Files.createDirectories(cacheDir);

        when(mediaFileRepository.findById(id)).thenReturn(Optional.of(mediaFileEntity(id, "/test/video.mkv")));
        when(ffprobeService.getKeyframes("/test/video.mkv")).thenReturn(List.of(0.0, 5.0));

        FFmpeg ffmpegMock = mock(FFmpeg.class, RETURNS_SELF);
        when(jaffree.getFFMPEG()).thenReturn(ffmpegMock);

        doAnswer(inv -> {
            Files.writeString(cacheDir.resolve("seg_video_720p_00000.ts"), "data");
            return null;
        }).when(ffmpegMock).execute();

        hlsService.getVideoSegment(id, "seg_video_720p_00000.ts");

        // Wait briefly for the pass future to complete
        Thread.sleep(200);

        // After all passes for file are done, the slot must be back (1 available)
        Semaphore slots = (Semaphore) ReflectionTestUtils.getField(transcodeService, "concurrentFileSlots");
        assertNotNull(slots);
        assertEquals(1, slots.availablePermits(), "Slot should be released after all passes complete");
    }

    @Test
    void failedPassIsRestartedOnNextRequest() throws Exception {
        UUID id = UUID.randomUUID();
        Path cacheDir = tempDir.resolve(id.toString());
        Files.createDirectories(cacheDir);

        when(mediaFileRepository.findById(id)).thenReturn(Optional.of(mediaFileEntity(id, "/test/video.mkv")));
        when(ffprobeService.getKeyframes("/test/video.mkv")).thenReturn(List.of(0.0, 5.0));

        FFmpeg ffmpegMock = mock(FFmpeg.class, RETURNS_SELF);
        when(jaffree.getFFMPEG()).thenReturn(ffmpegMock);

        AtomicInteger callCount = new AtomicInteger(0);
        doAnswer(inv -> {
            if (callCount.incrementAndGet() == 1) {
                throw new RuntimeException("Simulated FFmpeg crash");
            }
            Files.writeString(cacheDir.resolve("seg_video_720p_00000.ts"), "data");
            return null;
        }).when(ffmpegMock).execute();

        assertThrows(IOException.class, () -> hlsService.getVideoSegment(id, "seg_video_720p_00000.ts"));

        Path result = hlsService.getVideoSegment(id, "seg_video_720p_00000.ts");
        assertNotNull(result);
        verify(ffmpegMock, times(2)).execute();
    }

    // ========== Helpers ==========

    private MediaFileEntity mediaFileEntity(UUID id, String path, MediaFileStreamEntity... streams) {
        return MediaFileEntity.builder()
                .path(path)
                .size(0)
                .directoryEntityId(UUID.randomUUID())
                .mediaFileStreamEntity(List.of(streams))
                .build();
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
