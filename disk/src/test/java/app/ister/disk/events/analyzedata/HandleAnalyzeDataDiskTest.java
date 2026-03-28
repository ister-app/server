package app.ister.disk.events.analyzedata;

import app.ister.core.entity.DirectoryEntity;
import app.ister.core.entity.EpisodeEntity;
import app.ister.core.entity.MediaFileEntity;
import app.ister.core.entity.MediaFileStreamEntity;
import app.ister.core.entity.MetadataEntity;
import app.ister.core.entity.MovieEntity;
import app.ister.core.entity.OtherPathFileEntity;
import app.ister.core.enums.EventType;
import app.ister.core.enums.PathFileType;
import app.ister.core.enums.StreamCodecType;
import app.ister.core.eventdata.AnalyzeData;
import app.ister.core.repository.DirectoryRepository;
import app.ister.core.repository.EpisodeRepository;
import app.ister.core.repository.MediaFileStreamRepository;
import app.ister.core.repository.MovieRepository;
import app.ister.core.repository.OtherPathFileRepository;
import app.ister.core.service.MessageSender;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class HandleAnalyzeDataDiskTest {

    @InjectMocks
    private HandleAnalyzeDataDisk subject;

    @Mock
    private DirectoryRepository directoryRepository;
    @Mock
    private EpisodeRepository episodeRepository;
    @Mock
    private MovieRepository movieRepository;
    @Mock
    private MediaFileStreamRepository mediaFileStreamRepository;
    @Mock
    private OtherPathFileRepository otherPathFileRepository;
    @Mock
    private MessageSender messageSender;

    @Test
    void handles() {
        assertEquals(EventType.ANALYZE_DATA, subject.handles());
    }

    @Test
    void listenerThrowsOnWrongEventType() {
        AnalyzeData data = AnalyzeData.builder()
                .eventType(EventType.FILE_SCAN_REQUESTED)
                .build();
        assertThrows(IllegalArgumentException.class, () -> subject.listener(data));
    }

    @Test
    void handleEpisodePathSendsMediaFileFoundAndImageFound() {
        ReflectionTestUtils.setField(subject, "tmpDir", "/nonexistent-tmp-dir");
        UUID dirId = UUID.randomUUID();
        UUID episodeId = UUID.randomUUID();
        UUID mediaFileId = UUID.randomUUID();

        DirectoryEntity dir = DirectoryEntity.builder().id(dirId).name("shows").build();
        MediaFileEntity mf = MediaFileEntity.builder().id(mediaFileId).path("/shows/episode.mkv").build();
        mf.setDirectoryEntity(dir);
        EpisodeEntity episode = EpisodeEntity.builder()
                .id(episodeId)
                .mediaFileEntities(List.of(mf))
                .metadataEntities(List.of())
                .imagesEntities(List.of())
                .build();

        AnalyzeData data = AnalyzeData.builder()
                .eventType(EventType.ANALYZE_DATA)
                .directoryId(dirId)
                .episodeId(episodeId)
                .build();

        when(directoryRepository.findById(dirId)).thenReturn(Optional.of(dir));
        when(episodeRepository.findById(episodeId)).thenReturn(Optional.of(episode));
        when(mediaFileStreamRepository.findByMediaFileEntity_IdAndCodecType(mediaFileId, StreamCodecType.EXTERNAL_SUBTITLE))
                .thenReturn(List.of());

        assertTrue(subject.handle(data));

        verify(mediaFileStreamRepository).deleteAllByMediaFileEntityId(mediaFileId);
        verify(messageSender).sendMediaFileFound(any(), eq("shows"));
    }

    @Test
    void handleMoviePathSendsMediaFileFound() {
        ReflectionTestUtils.setField(subject, "tmpDir", "/nonexistent-tmp-dir");
        UUID dirId = UUID.randomUUID();
        UUID movieId = UUID.randomUUID();
        UUID mediaFileId = UUID.randomUUID();

        DirectoryEntity dir = DirectoryEntity.builder().id(dirId).name("movies").build();
        MediaFileEntity mf = MediaFileEntity.builder().id(mediaFileId).path("/movies/film.mkv").build();
        mf.setDirectoryEntity(dir);
        MovieEntity movie = MovieEntity.builder()
                .id(movieId)
                .mediaFileEntities(List.of(mf))
                .metadataEntities(List.of())
                .imagesEntities(List.of())
                .build();

        AnalyzeData data = AnalyzeData.builder()
                .eventType(EventType.ANALYZE_DATA)
                .directoryId(dirId)
                .movieId(movieId)
                .build();

        when(directoryRepository.findById(dirId)).thenReturn(Optional.of(dir));
        when(movieRepository.findById(movieId)).thenReturn(Optional.of(movie));
        when(mediaFileStreamRepository.findByMediaFileEntity_IdAndCodecType(mediaFileId, StreamCodecType.EXTERNAL_SUBTITLE))
                .thenReturn(List.of());

        assertTrue(subject.handle(data));

        verify(mediaFileStreamRepository).deleteAllByMediaFileEntityId(mediaFileId);
        verify(messageSender).sendMediaFileFound(any(), eq("movies"));
    }

    @Test
    void handleSendsNfoFileFoundViaMetadataFk() {
        ReflectionTestUtils.setField(subject, "tmpDir", "/nonexistent-tmp-dir");
        UUID dirId = UUID.randomUUID();
        UUID episodeId = UUID.randomUUID();

        DirectoryEntity dir = DirectoryEntity.builder().id(dirId).name("shows").build();
        MetadataEntity metadata = MetadataEntity.builder().build();
        OtherPathFileEntity nfoFile = OtherPathFileEntity.builder()
                .path("/shows/episode.nfo")
                .pathFileType(PathFileType.NFO)
                .build();
        EpisodeEntity episode = EpisodeEntity.builder()
                .id(episodeId)
                .mediaFileEntities(List.of())
                .metadataEntities(List.of(metadata))
                .imagesEntities(List.of())
                .build();

        AnalyzeData data = AnalyzeData.builder()
                .eventType(EventType.ANALYZE_DATA)
                .directoryId(dirId)
                .episodeId(episodeId)
                .build();

        when(directoryRepository.findById(dirId)).thenReturn(Optional.of(dir));
        when(episodeRepository.findById(episodeId)).thenReturn(Optional.of(episode));
        when(otherPathFileRepository.findByMetadataEntity(metadata)).thenReturn(Optional.of(nfoFile));

        assertTrue(subject.handle(data));

        verify(messageSender).sendNfoFileFound(any(), eq("shows"));
    }

    @Test
    void handleDoesNotSendNfoFileFoundWhenNoOtherPathFileForMetadata() {
        ReflectionTestUtils.setField(subject, "tmpDir", "/nonexistent-tmp-dir");
        UUID dirId = UUID.randomUUID();
        UUID episodeId = UUID.randomUUID();

        DirectoryEntity dir = DirectoryEntity.builder().id(dirId).name("shows").build();
        MetadataEntity metadata = MetadataEntity.builder().build();
        EpisodeEntity episode = EpisodeEntity.builder()
                .id(episodeId)
                .mediaFileEntities(List.of())
                .metadataEntities(List.of(metadata))
                .imagesEntities(List.of())
                .build();

        AnalyzeData data = AnalyzeData.builder()
                .eventType(EventType.ANALYZE_DATA)
                .directoryId(dirId)
                .episodeId(episodeId)
                .build();

        when(directoryRepository.findById(dirId)).thenReturn(Optional.of(dir));
        when(episodeRepository.findById(episodeId)).thenReturn(Optional.of(episode));
        when(otherPathFileRepository.findByMetadataEntity(metadata)).thenReturn(Optional.empty());

        assertTrue(subject.handle(data));

        verify(messageSender, never()).sendNfoFileFound(any(), any());
    }

    @Test
    void handleSendsSubtitleFileFoundViaStreamFk() {
        ReflectionTestUtils.setField(subject, "tmpDir", "/nonexistent-tmp-dir");
        UUID dirId = UUID.randomUUID();
        UUID episodeId = UUID.randomUUID();
        UUID mediaFileId = UUID.randomUUID();

        DirectoryEntity dir = DirectoryEntity.builder().id(dirId).name("shows").build();
        MediaFileEntity mf = MediaFileEntity.builder().id(mediaFileId).path("/shows/episode.mkv").build();
        mf.setDirectoryEntity(dir);
        MediaFileStreamEntity stream = MediaFileStreamEntity.builder().build();
        OtherPathFileEntity subtitleFile = OtherPathFileEntity.builder()
                .path("/shows/episode.en.srt")
                .pathFileType(PathFileType.SUBTITLE)
                .build();
        EpisodeEntity episode = EpisodeEntity.builder()
                .id(episodeId)
                .mediaFileEntities(List.of(mf))
                .metadataEntities(List.of())
                .imagesEntities(List.of())
                .build();

        AnalyzeData data = AnalyzeData.builder()
                .eventType(EventType.ANALYZE_DATA)
                .directoryId(dirId)
                .episodeId(episodeId)
                .build();

        when(directoryRepository.findById(dirId)).thenReturn(Optional.of(dir));
        when(episodeRepository.findById(episodeId)).thenReturn(Optional.of(episode));
        when(mediaFileStreamRepository.findByMediaFileEntity_IdAndCodecType(mediaFileId, StreamCodecType.EXTERNAL_SUBTITLE))
                .thenReturn(List.of(stream));
        when(otherPathFileRepository.findByMediaFileStreamEntity(stream)).thenReturn(Optional.of(subtitleFile));

        assertTrue(subject.handle(data));

        verify(messageSender).sendSubtitleFileFound(any(), eq("shows"));
    }

    @Test
    void handleDoesNotSendSubtitleFileFoundWhenNoOtherPathFileForStream() {
        ReflectionTestUtils.setField(subject, "tmpDir", "/nonexistent-tmp-dir");
        UUID dirId = UUID.randomUUID();
        UUID episodeId = UUID.randomUUID();
        UUID mediaFileId = UUID.randomUUID();

        DirectoryEntity dir = DirectoryEntity.builder().id(dirId).name("shows").build();
        MediaFileEntity mf = MediaFileEntity.builder().id(mediaFileId).path("/shows/episode.mkv").build();
        mf.setDirectoryEntity(dir);
        MediaFileStreamEntity stream = MediaFileStreamEntity.builder().build();
        EpisodeEntity episode = EpisodeEntity.builder()
                .id(episodeId)
                .mediaFileEntities(List.of(mf))
                .metadataEntities(List.of())
                .imagesEntities(List.of())
                .build();

        AnalyzeData data = AnalyzeData.builder()
                .eventType(EventType.ANALYZE_DATA)
                .directoryId(dirId)
                .episodeId(episodeId)
                .build();

        when(directoryRepository.findById(dirId)).thenReturn(Optional.of(dir));
        when(episodeRepository.findById(episodeId)).thenReturn(Optional.of(episode));
        when(mediaFileStreamRepository.findByMediaFileEntity_IdAndCodecType(mediaFileId, StreamCodecType.EXTERNAL_SUBTITLE))
                .thenReturn(List.of(stream));
        when(otherPathFileRepository.findByMediaFileStreamEntity(stream)).thenReturn(Optional.empty());

        assertTrue(subject.handle(data));

        verify(messageSender, never()).sendSubtitleFileFound(any(), any());
    }

    @Test
    void handleEpisodeWithNoLocalFilesSkipsMediaFileSending() {
        ReflectionTestUtils.setField(subject, "tmpDir", "/nonexistent-tmp-dir");
        UUID dirId = UUID.randomUUID();
        UUID otherDirId = UUID.randomUUID();
        UUID episodeId = UUID.randomUUID();

        DirectoryEntity dir = DirectoryEntity.builder().id(dirId).name("shows").build();
        DirectoryEntity otherDir = DirectoryEntity.builder().id(otherDirId).name("other").build();
        MediaFileEntity mfOtherDir = MediaFileEntity.builder().path("/other/episode.mkv").build();
        mfOtherDir.setDirectoryEntity(otherDir);
        EpisodeEntity episode = EpisodeEntity.builder()
                .id(episodeId)
                .mediaFileEntities(List.of(mfOtherDir))
                .metadataEntities(List.of())
                .imagesEntities(List.of())
                .build();

        AnalyzeData data = AnalyzeData.builder()
                .eventType(EventType.ANALYZE_DATA)
                .directoryId(dirId)
                .episodeId(episodeId)
                .build();

        when(directoryRepository.findById(dirId)).thenReturn(Optional.of(dir));
        when(episodeRepository.findById(episodeId)).thenReturn(Optional.of(episode));

        assertTrue(subject.handle(data));

        verify(mediaFileStreamRepository, never()).deleteAllByMediaFileEntityId(any());
    }
}
