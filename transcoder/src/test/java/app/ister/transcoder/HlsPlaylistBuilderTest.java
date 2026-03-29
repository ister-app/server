package app.ister.transcoder;

import app.ister.core.entity.MediaFileEntity;
import app.ister.core.entity.MediaFileStreamEntity;
import app.ister.core.enums.StreamCodecType;
import app.ister.core.enums.SubtitleFormat;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class HlsPlaylistBuilderTest {

    private HlsPlaylistBuilder builder;

    @BeforeEach
    void setUp() {
        builder = new HlsPlaylistBuilder();
    }

    // ========== buildMasterPlaylist ==========

    @Test
    void buildMasterPlaylistDirectOnlyContainsCopyVariant() {
        MediaFileEntity mediaFile = mediaFile(videoStream(0, 1920, 1080), audioStream(1, "eng", "English"));
        String result = builder.buildMasterPlaylist(mediaFile, true, false, SubtitleFormat.WEBVTT);

        assertTrue(result.contains("#EXTM3U"));
        assertTrue(result.contains("stream_video_copy.m3u8"));
        assertFalse(result.contains("stream_video_720p.m3u8"));
        assertFalse(result.contains("stream_video_480p.m3u8"));
        assertTrue(result.contains("audio-copy"));
    }

    @Test
    void buildMasterPlaylistTranscodeOnlyContains720pAnd480p() {
        MediaFileEntity mediaFile = mediaFile(videoStream(0, 1920, 1080), audioStream(1, "eng", "English"));
        String result = builder.buildMasterPlaylist(mediaFile, false, true, SubtitleFormat.WEBVTT);

        assertFalse(result.contains("stream_video_copy.m3u8"));
        assertTrue(result.contains("stream_video_720p.m3u8"));
        assertTrue(result.contains("stream_video_480p.m3u8"));
        // 720p and 480p share the same audio group (192k)
        assertTrue(result.contains("audio-192k"));
        assertFalse(result.contains("audio-64k"));
    }

    @Test
    void buildMasterPlaylistBothContainsAllVariants() {
        MediaFileEntity mediaFile = mediaFile(videoStream(0, 1920, 1080), audioStream(1, "eng", "English"));
        String result = builder.buildMasterPlaylist(mediaFile, true, true, SubtitleFormat.WEBVTT);

        assertTrue(result.contains("stream_video_copy.m3u8"));
        assertTrue(result.contains("stream_video_720p.m3u8"));
        assertTrue(result.contains("stream_video_480p.m3u8"));
    }

    @Test
    void buildMasterPlaylistIncludesTextSubtitles() {
        UUID subId = UUID.randomUUID();
        MediaFileStreamEntity sub = subtitleStream(subId, 2, "nld", "Nederlands", "subrip", StreamCodecType.SUBTITLE);
        MediaFileEntity mediaFile = mediaFile(videoStream(0, 1920, 1080), audioStream(1, "eng", null), sub);

        String result = builder.buildMasterPlaylist(mediaFile, true, false, SubtitleFormat.WEBVTT);

        assertTrue(result.contains("TYPE=SUBTITLES"));
        assertTrue(result.contains("GROUP-ID=\"subs\""));
        assertTrue(result.contains("stream_sub_" + subId + "_webvtt.m3u8"));
        assertTrue(result.contains("SUBTITLES=\"subs\""));
        assertTrue(result.contains("LANGUAGE=\"nld\""));
    }

    @Test
    void buildMasterPlaylistExcludesImageSubtitles() {
        UUID subId = UUID.randomUUID();
        MediaFileStreamEntity imgSub = subtitleStream(subId, 2, "nld", "NL", "dvd_subtitle", StreamCodecType.SUBTITLE);
        MediaFileEntity mediaFile = mediaFile(videoStream(0, 1920, 1080), audioStream(1, "eng", null), imgSub);

        String result = builder.buildMasterPlaylist(mediaFile, true, false, SubtitleFormat.WEBVTT);

        assertFalse(result.contains("TYPE=SUBTITLES"));
        assertFalse(result.contains("SUBTITLES=\"subs\""));
    }

    @Test
    void buildMasterPlaylistSrtSubtitleFormat() {
        UUID subId = UUID.randomUUID();
        MediaFileStreamEntity sub = subtitleStream(subId, 2, "eng", "English", "subrip", StreamCodecType.SUBTITLE);
        MediaFileEntity mediaFile = mediaFile(videoStream(0, 1920, 1080), audioStream(1, "eng", null), sub);

        String result = builder.buildMasterPlaylist(mediaFile, true, false, SubtitleFormat.SRT);

        assertTrue(result.contains("stream_sub_" + subId + "_srt.m3u8"));
    }

    @Test
    void buildMasterPlaylistExternalSubtitleIsIncluded() {
        UUID subId = UUID.randomUUID();
        MediaFileStreamEntity extSub = subtitleStream(subId, -1, "eng", "English", "srt", StreamCodecType.EXTERNAL_SUBTITLE);
        MediaFileEntity mediaFile = mediaFile(videoStream(0, 1920, 1080), audioStream(1, "eng", null), extSub);

        String result = builder.buildMasterPlaylist(mediaFile, true, false, SubtitleFormat.WEBVTT);

        assertTrue(result.contains("TYPE=SUBTITLES"));
    }

    @Test
    void buildMasterPlaylistSubtitleFallsBackToLangWhenNoTitle() {
        UUID subId = UUID.randomUUID();
        MediaFileStreamEntity sub = subtitleStream(subId, 2, "nld", null, "subrip", StreamCodecType.SUBTITLE);
        MediaFileEntity mediaFile = mediaFile(videoStream(0, 1920, 1080), audioStream(1, "eng", null), sub);

        String result = builder.buildMasterPlaylist(mediaFile, true, false, SubtitleFormat.WEBVTT);

        assertTrue(result.contains("NAME=\"nld\""));
    }

    @Test
    void buildMasterPlaylistFallsBackToUndForNullLanguage() {
        UUID subId = UUID.randomUUID();
        MediaFileStreamEntity sub = subtitleStream(subId, 2, null, null, "subrip", StreamCodecType.SUBTITLE);
        MediaFileEntity mediaFile = mediaFile(videoStream(0, 1920, 1080), audioStream(1, "eng", null), sub);

        String result = builder.buildMasterPlaylist(mediaFile, true, false, SubtitleFormat.WEBVTT);

        assertTrue(result.contains("LANGUAGE=\"und\""));
    }

    @Test
    void buildMasterPlaylistUsesSourceResolutionForCopyVariant() {
        MediaFileEntity mediaFile = mediaFile(videoStream(0, 1280, 720), audioStream(1, "eng", null));
        String result = builder.buildMasterPlaylist(mediaFile, true, false, SubtitleFormat.WEBVTT);

        assertTrue(result.contains("RESOLUTION=1280x720"));
    }

    @Test
    void buildMasterPlaylistNoVideoStreamDefaultsTo1920x1080() {
        MediaFileEntity mediaFile = mediaFile(audioStream(1, "eng", null));
        String result = builder.buildMasterPlaylist(mediaFile, true, false, SubtitleFormat.WEBVTT);

        assertTrue(result.contains("RESOLUTION=1920x1080"));
    }

    @Test
    void buildMasterPlaylistFirstAudioIsDefault() {
        MediaFileStreamEntity audio1 = audioStream(1, "eng", "English");
        MediaFileStreamEntity audio2 = audioStream(2, "nld", "Dutch");
        MediaFileEntity mediaFile = mediaFile(videoStream(0, 1920, 1080), audio1, audio2);

        String result = builder.buildMasterPlaylist(mediaFile, true, false, SubtitleFormat.WEBVTT);

        // First audio track in first group should be DEFAULT=YES
        int firstDefault = result.indexOf("DEFAULT=YES");
        int firstNo = result.indexOf("DEFAULT=NO");
        assertTrue(firstDefault < firstNo);
    }

    @Test
    void buildMasterPlaylistFirstSubtitleIsAutoselect() {
        UUID sub1 = UUID.randomUUID();
        UUID sub2 = UUID.randomUUID();
        MediaFileStreamEntity s1 = subtitleStream(sub1, 2, "eng", "English", "subrip", StreamCodecType.SUBTITLE);
        MediaFileStreamEntity s2 = subtitleStream(sub2, 3, "nld", "Dutch", "subrip", StreamCodecType.SUBTITLE);
        MediaFileEntity mediaFile = mediaFile(videoStream(0, 1920, 1080), audioStream(1, "eng", null), s1, s2);

        String result = builder.buildMasterPlaylist(mediaFile, true, false, SubtitleFormat.WEBVTT);

        assertTrue(result.contains("AUTOSELECT=YES"));
        assertTrue(result.contains("AUTOSELECT=NO"));
    }

    // ========== buildStreamPlaylist ==========

    @Test
    void buildStreamPlaylistVideoUsesKeyframedSegments() {
        List<Double> keyframes = List.of(0.0, 5.0, 10.0);
        String result = builder.buildStreamPlaylist("stream_video_720p.m3u8", keyframes, 15.0);

        assertTrue(result.contains("seg_video_720p_00000.ts"));
        assertTrue(result.contains("seg_video_720p_00001.ts"));
        assertTrue(result.contains("seg_video_720p_00002.ts"));
        assertTrue(result.contains("#EXT-X-ENDLIST"));
    }

    @Test
    void buildStreamPlaylistCopyVideoUsesKeyframedSegments() {
        List<Double> keyframes = List.of(0.0, 4.0, 8.0);
        String result = builder.buildStreamPlaylist("stream_video_copy.m3u8", keyframes, 12.0);

        assertTrue(result.contains("seg_video_copy_00000.ts"));
        assertTrue(result.contains("seg_video_copy_00001.ts"));
    }

    @Test
    void buildStreamPlaylistAudioCopyReturnsSingleSegment() {
        List<Double> keyframes = List.of(0.0);
        String result = builder.buildStreamPlaylist("stream_audio_1_copy.m3u8", keyframes, 60.0);

        assertTrue(result.contains("seg_audio_0.000000_60.000000_1_copy.ts"));
        assertTrue(result.contains("#EXT-X-ENDLIST"));
        // Single segment, no multiple entries
        assertEquals(1, countOccurrences(result, "#EXTINF:"));
    }

    @Test
    void buildStreamPlaylistAudioTranscodedUsesKeyframedSegments() {
        List<Double> keyframes = List.of(0.0, 5.0);
        String result = builder.buildStreamPlaylist("stream_audio_2_192k.m3u8", keyframes, 10.0);

        assertTrue(result.contains("seg_audio_2_192k_00000.ts"));
        assertTrue(result.contains("seg_audio_2_192k_00001.ts"));
    }

    @Test
    void buildStreamPlaylistSubSrtReturnsSingleSegment() {
        UUID subtitleId = UUID.randomUUID();
        List<Double> keyframes = List.of(0.0);
        String result = builder.buildStreamPlaylist("stream_sub_" + subtitleId + "_srt.m3u8", keyframes, 45.0);

        assertTrue(result.contains("sub_" + subtitleId + ".srt"));
        assertEquals(1, countOccurrences(result, "#EXTINF:"));
    }

    @Test
    void buildStreamPlaylistSubWebVttUsesKeyframedSegments() {
        UUID subtitleId = UUID.randomUUID();
        List<Double> keyframes = List.of(0.0, 5.0, 10.0);
        String result = builder.buildStreamPlaylist("stream_sub_" + subtitleId + "_webvtt.m3u8", keyframes, 15.0);

        assertTrue(result.contains("seg_sub_" + subtitleId + "_00000.vtt"));
        assertTrue(result.contains("seg_sub_" + subtitleId + "_00001.vtt"));
        assertTrue(result.contains("seg_sub_" + subtitleId + "_00002.vtt"));
    }

    @Test
    void buildStreamPlaylistUnknownFilenameThrows() {
        assertThrows(IllegalArgumentException.class,
                () -> builder.buildStreamPlaylist("stream_unknown_foo.m3u8", List.of(0.0), 10.0));
    }

    // ========== buildVodPlaylist ==========

    @Test
    void buildVodPlaylistSingleKeyframe() {
        List<Double> keyframes = List.of(0.0);
        String result = builder.buildVodPlaylist(keyframes, 10.0, (s, d, i) -> "seg_" + i + ".ts");

        assertTrue(result.contains("#EXTM3U"));
        assertTrue(result.contains("#EXT-X-VERSION:6"));
        assertTrue(result.contains("#EXT-X-TARGETDURATION:10"));
        assertTrue(result.contains("#EXTINF:10.000000,"));
        assertTrue(result.contains("seg_0.ts"));
        assertTrue(result.contains("#EXT-X-ENDLIST"));
    }

    @Test
    void buildVodPlaylistMultipleKeyframesHaveCorrectDurations() {
        List<Double> keyframes = List.of(0.0, 4.0, 7.0);
        String result = builder.buildVodPlaylist(keyframes, 10.0, (s, d, i) -> "seg_" + i + ".ts");

        assertTrue(result.contains("#EXTINF:4.000000,"));  // 4-0
        assertTrue(result.contains("#EXTINF:3.000000,"));  // 7-4
        assertTrue(result.contains("#EXTINF:3.000000,"));  // 10-7
        assertTrue(result.contains("#EXT-X-TARGETDURATION:4")); // max = 4
    }

    @Test
    void buildVodPlaylistEmptyKeyframesThrows() {
        assertThrows(IllegalStateException.class,
                () -> builder.buildVodPlaylist(List.of(), 10.0, (s, d, i) -> "seg_" + i + ".ts"));
    }

    @Test
    void buildVodPlaylistHasMediaSequenceZero() {
        List<Double> keyframes = List.of(0.0);
        String result = builder.buildVodPlaylist(keyframes, 5.0, (s, d, i) -> "s.ts");

        assertTrue(result.contains("#EXT-X-MEDIA-SEQUENCE:0"));
    }

    @Test
    void buildVodPlaylistHasVodPlaylistType() {
        List<Double> keyframes = List.of(0.0);
        String result = builder.buildVodPlaylist(keyframes, 5.0, (s, d, i) -> "s.ts");

        assertTrue(result.contains("#EXT-X-PLAYLIST-TYPE:VOD"));
    }

    @Test
    void buildVodPlaylistTargetDurationIsCeiled() {
        List<Double> keyframes = List.of(0.0, 4.5);
        // Segments: 4.5s and 5.5s → max=5.5 → ceil=6
        String result = builder.buildVodPlaylist(keyframes, 10.0, (s, d, i) -> "s_" + i + ".ts");

        assertTrue(result.contains("#EXT-X-TARGETDURATION:6"));
    }

    // ========== buildSingleSegmentPlaylist ==========

    @Test
    void buildSingleSegmentPlaylistHasCorrectStructure() {
        String result = builder.buildSingleSegmentPlaylist(90.0, "sub_abc.srt");

        assertTrue(result.contains("#EXTM3U"));
        assertTrue(result.contains("#EXT-X-VERSION:6"));
        assertTrue(result.contains("#EXT-X-TARGETDURATION:90"));
        assertTrue(result.contains("#EXTINF:90.000000,"));
        assertTrue(result.contains("sub_abc.srt"));
        assertTrue(result.contains("#EXT-X-ENDLIST"));
    }

    @Test
    void buildSingleSegmentPlaylistDurationIsCeiled() {
        String result = builder.buildSingleSegmentPlaylist(60.5, "file.srt");

        assertTrue(result.contains("#EXT-X-TARGETDURATION:61"));
    }

    // ========== Helpers ==========

    private int countOccurrences(String text, String substring) {
        int count = 0;
        int idx = 0;
        while ((idx = text.indexOf(substring, idx)) != -1) {
            count++;
            idx += substring.length();
        }
        return count;
    }

    private MediaFileEntity mediaFile(MediaFileStreamEntity... streams) {
        return MediaFileEntity.builder()
                .path("/test/video.mkv")
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

    private MediaFileStreamEntity subtitleStream(UUID id, int index, String language, String title,
                                                  String codecName, StreamCodecType codecType) {
        MediaFileStreamEntity stream = MediaFileStreamEntity.builder()
                .streamIndex(index)
                .codecType(codecType)
                .codecName(codecName)
                .width(0).height(0)
                .language(language)
                .title(title)
                .path("")
                .build();
        ReflectionTestUtils.setField(stream, "id", id);
        return stream;
    }
}
