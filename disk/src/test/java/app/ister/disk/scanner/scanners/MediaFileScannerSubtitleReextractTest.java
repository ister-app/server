package app.ister.disk.scanner.scanners;

import app.ister.core.entity.MediaFileStreamEntity;
import app.ister.core.enums.StreamCodecType;
import app.ister.core.repository.MediaFileEpisodeRepository;
import app.ister.core.repository.MediaFileRepository;
import app.ister.core.repository.MediaFileStreamRepository;
import app.ister.core.service.MessageSender;
import app.ister.core.service.ScannerHelperService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MediaFileScannerSubtitleReextractTest {
    @Mock
    private ScannerHelperService scannerHelperServiceMock;
    @Mock
    private MediaFileRepository mediaFileRepositoryMock;
    @Mock
    private MediaFileEpisodeRepository mediaFileEpisodeRepositoryMock;
    @Mock
    private MediaFileStreamRepository mediaFileStreamRepositoryMock;
    @Mock
    private MessageSender messageSenderMock;

    @InjectMocks
    private MediaFileScanner subject;

    private final UUID mediaFileId = UUID.randomUUID();

    private MediaFileStreamEntity stream(StreamCodecType type, String codecName, int index) {
        return MediaFileStreamEntity.builder()
                .codecType(type)
                .codecName(codecName)
                .streamIndex(index)
                .build();
    }

    private void givenSubtitleStreams(MediaFileStreamEntity... subtitles) {
        when(mediaFileStreamRepositoryMock.findByMediaFileEntity_IdAndCodecType(mediaFileId, StreamCodecType.SUBTITLE))
                .thenReturn(List.of(subtitles));
    }

    private void givenExtractedStreams(MediaFileStreamEntity... external) {
        when(mediaFileStreamRepositoryMock.findByMediaFileEntity_IdAndCodecType(mediaFileId, StreamCodecType.EXTERNAL_SUBTITLE))
                .thenReturn(List.of(external));
    }

    @Test
    void imageSubtitleWithoutExtractedCounterpartNeedsReextract() {
        givenSubtitleStreams(stream(StreamCodecType.SUBTITLE, "dvd_subtitle", 3));
        givenExtractedStreams();
        assertTrue(subject.needsSubtitleReextract(mediaFileId));
    }

    @Test
    void textSubtitleOnlyNeedsNoReextract() {
        givenSubtitleStreams(stream(StreamCodecType.SUBTITLE, "subrip", 3));
        assertFalse(subject.needsSubtitleReextract(mediaFileId));
    }

    @Test
    void imageSubtitleWithExtractedCounterpartNeedsNoReextract() {
        givenSubtitleStreams(stream(StreamCodecType.SUBTITLE, "hdmv_pgs_subtitle", 3));
        givenExtractedStreams(stream(StreamCodecType.EXTERNAL_SUBTITLE, "subtitle srt", 3));
        assertFalse(subject.needsSubtitleReextract(mediaFileId));
    }

    @Test
    void extractedCounterpartAtOtherIndexStillNeedsReextract() {
        givenSubtitleStreams(
                stream(StreamCodecType.SUBTITLE, "dvd_subtitle", 3),
                stream(StreamCodecType.SUBTITLE, "dvd_subtitle", 4));
        givenExtractedStreams(stream(StreamCodecType.EXTERNAL_SUBTITLE, "subtitle srt", 3));
        assertTrue(subject.needsSubtitleReextract(mediaFileId));
    }

    @Test
    void imageSubtitleMarkedExtractionFailedNeedsNoReextract() {
        MediaFileStreamEntity failed = stream(StreamCodecType.SUBTITLE, "dvd_subtitle", 3);
        failed.setExtractionFailed(true);
        givenSubtitleStreams(failed);
        assertFalse(subject.needsSubtitleReextract(mediaFileId));
    }

    @Test
    void unmarkedImageSubtitleNextToFailedOneStillNeedsReextract() {
        MediaFileStreamEntity failed = stream(StreamCodecType.SUBTITLE, "dvd_subtitle", 3);
        failed.setExtractionFailed(true);
        givenSubtitleStreams(failed, stream(StreamCodecType.SUBTITLE, "dvd_subtitle", 4));
        givenExtractedStreams();
        assertTrue(subject.needsSubtitleReextract(mediaFileId));
    }

    @Test
    void fileWithoutSubtitleStreamsNeedsNoReextract() {
        givenSubtitleStreams();
        assertFalse(subject.needsSubtitleReextract(mediaFileId));
    }

    private void givenVideoStreams(MediaFileStreamEntity... video) {
        when(mediaFileStreamRepositoryMock.findByMediaFileEntity_IdAndCodecType(mediaFileId, StreamCodecType.VIDEO))
                .thenReturn(List.of(video));
    }

    @Test
    void videoStreamWithoutCropColumnsNeedsCropDetect() {
        givenVideoStreams(MediaFileStreamEntity.builder()
                .codecType(StreamCodecType.VIDEO).codecName("h264").streamIndex(0)
                .width(720).height(576).build());
        assertTrue(subject.needsCropDetect(mediaFileId));
    }

    @Test
    void fullFrameSentinelNeedsNoCropDetect() {
        givenVideoStreams(MediaFileStreamEntity.builder()
                .codecType(StreamCodecType.VIDEO).codecName("h264").streamIndex(0)
                .width(720).height(576)
                .cropX(0).cropY(0).cropWidth(720).cropHeight(576).build());
        assertFalse(subject.needsCropDetect(mediaFileId));
    }

    @Test
    void audioOnlyFileNeedsNoCropDetect() {
        givenVideoStreams();
        assertFalse(subject.needsCropDetect(mediaFileId));
    }
}
