package app.ister.disk.events.subtitleextract;

import app.ister.core.EventHandlingException;
import app.ister.core.entity.DirectoryEntity;
import app.ister.core.entity.MediaFileEntity;
import app.ister.core.entity.MediaFileStreamEntity;
import app.ister.core.entity.NodeEntity;
import app.ister.core.enums.DirectoryType;
import app.ister.core.enums.StreamCodecType;
import app.ister.core.node.MediaFileInputResolver;
import app.ister.core.node.RemoteNodeClient;
import app.ister.core.repository.DirectoryRepository;
import app.ister.core.repository.MediaFileRepository;
import app.ister.core.repository.MediaFileStreamRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SubtitleExtractionProcessorTest {

    @Mock private MediaFileRepository mediaFileRepository;
    @Mock private MediaFileStreamRepository mediaFileStreamRepository;
    @Mock private DirectoryRepository directoryRepository;
    @Mock private SubtitleExtractor extractor;
    @Mock private MediaFileInputResolver inputResolver;
    @Mock private RemoteNodeClient remoteNodeClient;
    @Mock private PlatformTransactionManager transactionManager;

    @TempDir Path tempDir;

    private SubtitleExtractionProcessor subject;

    private final UUID mediaFileId = UUID.randomUUID();
    private final UUID streamId = UUID.randomUUID();
    private final NodeEntity owner = NodeEntity.builder().name("owner").url("http://owner:8080").build();
    private MediaFileEntity mediaFile;
    private MediaFileStreamEntity subtitleStream;
    private Path ownerCache;

    @BeforeEach
    void setUp() {
        subject = new SubtitleExtractionProcessor(mediaFileRepository, mediaFileStreamRepository, directoryRepository,
                extractor, inputResolver, remoteNodeClient, transactionManager);
        ReflectionTestUtils.setField(subject, "dirOfFFmpeg", "/usr/bin");
        ReflectionTestUtils.setField(subject, "tmpDir", tempDir.resolve("tmp").toString());
        ownerCache = tempDir.resolve("owner-cache");

        subtitleStream = MediaFileStreamEntity.builder().id(streamId).codecType(StreamCodecType.SUBTITLE)
                .codecName("dvd_subtitle").streamIndex(3).language("eng").build();
        MediaFileStreamEntity audio = MediaFileStreamEntity.builder().id(UUID.randomUUID())
                .codecType(StreamCodecType.AUDIO).codecName("aac").streamIndex(1).build();
        MediaFileStreamEntity firstSub = MediaFileStreamEntity.builder().id(UUID.randomUUID())
                .codecType(StreamCodecType.SUBTITLE).codecName("subrip").streamIndex(2).build();
        mediaFile = MediaFileEntity.builder().id(mediaFileId).path("/tv/a.mkv")
                .mediaFileStreamEntity(new ArrayList<>(List.of(subtitleStream, audio, firstSub))).build();
        ReflectionTestUtils.setField(subtitleStream, "mediaFileEntity", mediaFile);

        lenient().when(mediaFileStreamRepository.findById(streamId)).thenReturn(Optional.of(subtitleStream));
        lenient().when(mediaFileRepository.findById(mediaFileId)).thenReturn(Optional.of(mediaFile));
        lenient().when(inputResolver.owner(mediaFile)).thenReturn(owner);
        lenient().when(directoryRepository.findByDirectoryTypeAndNodeEntity(DirectoryType.CACHE, owner))
                .thenReturn(List.of(DirectoryEntity.builder().directoryType(DirectoryType.CACHE).path(ownerCache.toString()).build()));
    }

    private void local() {
        when(inputResolver.isRemote(mediaFile)).thenReturn(false);
        when(inputResolver.resolve(mediaFile)).thenReturn("/tv/a.mkv");
    }

    private void remote() {
        when(inputResolver.isRemote(mediaFile)).thenReturn(true);
        when(inputResolver.resolve(mediaFile)).thenReturn("http://owner:8080/mediaFile/" + mediaFileId + "/download?token=t");
    }

    /** Writes the SRT wherever the processor asked for it, as the real extractor would. */
    private void extractorProduces(String fileName) {
        when(extractor.extractOne(anyString(), eq(mediaFileId), anyList(), eq(subtitleStream), eq(1), any(Path.class), eq("/usr/bin")))
                .thenAnswer(inv -> {
                    Path dir = inv.getArgument(5);
                    Path srt = dir.resolve(fileName);
                    Files.writeString(srt, "1\n");
                    return Optional.of(new SubtitleExtractor.ExtractedSubtitle(srt, "eng"));
                });
    }

    @Test
    void localExtractionWritesIntoTheOwnerCacheAndStoresTheRow() {
        local();
        extractorProduces(mediaFileId + "_3_eng.srt");

        subject.process(mediaFileId, streamId);

        ArgumentCaptor<MediaFileStreamEntity> saved = ArgumentCaptor.forClass(MediaFileStreamEntity.class);
        verify(mediaFileStreamRepository).save(saved.capture());
        assertEquals(StreamCodecType.EXTERNAL_SUBTITLE, saved.getValue().getCodecType());
        assertEquals(ownerCache.resolve(mediaFileId + "_3_eng.srt").toString(), saved.getValue().getPath());
        assertEquals(3, saved.getValue().getStreamIndex());
        assertTrue(Files.exists(ownerCache.resolve(mediaFileId + "_3_eng.srt")));
        verifyNoInteractions(remoteNodeClient);
    }

    /** The subtitle rank (0:s:N) counts only subtitle streams before this one: audio at index 1 does not count. */
    @Test
    void subtitleRankSkipsNonSubtitleStreams() {
        local();
        extractorProduces("x.srt");

        subject.process(mediaFileId, streamId);

        verify(extractor).extractOne(anyString(), eq(mediaFileId), anyList(), eq(subtitleStream), eq(1), any(Path.class), anyString());
    }

    @Test
    void remoteExtractionUploadsToTheOwnerAndRecordsTheOwnerPath() throws IOException {
        remote();
        extractorProduces(mediaFileId + "_3_eng.srt");

        subject.process(mediaFileId, streamId);

        ArgumentCaptor<Path> uploaded = ArgumentCaptor.forClass(Path.class);
        verify(remoteNodeClient).uploadToCache(eq("http://owner:8080"), uploaded.capture());
        assertTrue(uploaded.getValue().startsWith(tempDir.resolve("tmp")), "extracted into the helper's tmp dir");
        ArgumentCaptor<MediaFileStreamEntity> saved = ArgumentCaptor.forClass(MediaFileStreamEntity.class);
        verify(mediaFileStreamRepository).save(saved.capture());
        assertEquals(ownerCache.resolve(mediaFileId + "_3_eng.srt").toString(), saved.getValue().getPath());
        assertFalse(Files.exists(tempDir.resolve("tmp").resolve("subtitles").resolve(mediaFileId.toString())), "tmp cleaned up");
    }

    @Test
    void uploadFailureIsRetriedNotFlagged() throws IOException {
        remote();
        extractorProduces("x.srt");
        doThrow(new IOException("connection reset")).when(remoteNodeClient).uploadToCache(anyString(), any(Path.class));

        assertThrows(EventHandlingException.class, () -> subject.process(mediaFileId, streamId));

        verify(mediaFileStreamRepository, never()).save(any());
        assertNull(subtitleStream.getExtractionFailed());
    }

    @Test
    void toolFailureFlagsTheSourceRow() {
        local();
        when(extractor.extractOne(anyString(), eq(mediaFileId), anyList(), eq(subtitleStream), anyInt(), any(Path.class), anyString()))
                .thenAnswer(inv -> {
                    subtitleStream.setExtractionFailed(true);
                    return Optional.empty();
                });

        subject.process(mediaFileId, streamId);

        verify(mediaFileStreamRepository).save(subtitleStream);
        assertEquals(Boolean.TRUE, subtitleStream.getExtractionFailed());
    }

    @Test
    void skipsWhenAnExtractedRowAlreadyExists() {
        mediaFile.getMediaFileStreamEntity().add(MediaFileStreamEntity.builder().id(UUID.randomUUID())
                .codecType(StreamCodecType.EXTERNAL_SUBTITLE).codecName("subtitle srt").streamIndex(3).path("/x.srt").build());

        subject.process(mediaFileId, streamId);

        verifyNoInteractions(extractor);
        verify(mediaFileStreamRepository, never()).save(any());
    }

    @Test
    void skipsWhenTheStreamWasAlreadyFlagged() {
        subtitleStream.setExtractionFailed(true);

        subject.process(mediaFileId, streamId);

        verifyNoInteractions(extractor);
    }

    /** A re-analysis rewrote the rows while the OCR ran: the result belongs to nothing any more. */
    @Test
    void dropsTheResultWhenTheSourceRowIsGone() {
        local();
        extractorProduces("x.srt");
        when(mediaFileStreamRepository.findById(streamId))
                .thenReturn(Optional.of(subtitleStream))
                .thenReturn(Optional.empty());

        subject.process(mediaFileId, streamId);

        verify(mediaFileStreamRepository, never()).save(any());
    }
}
