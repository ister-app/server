package app.ister.worker.events.analyzedata;

import app.ister.core.entity.DirectoryEntity;
import app.ister.core.entity.EpisodeEntity;
import app.ister.core.entity.LibraryEntity;
import app.ister.core.entity.MediaFileEntity;
import app.ister.core.entity.MovieEntity;
import app.ister.core.entity.ShowEntity;
import app.ister.core.enums.EventType;
import app.ister.core.enums.LibraryType;
import app.ister.core.eventdata.AnalyzeData;
import app.ister.core.repository.EpisodeRepository;
import app.ister.core.repository.ImageRepository;
import app.ister.core.repository.LibraryRepository;
import app.ister.core.repository.MediaFileStreamRepository;
import app.ister.core.repository.MetadataRepository;
import app.ister.core.repository.MovieRepository;
import app.ister.core.repository.ShowRepository;
import app.ister.core.service.MessageSender;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Sort;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AnalyzeDataHandleTest {

    @InjectMocks
    private AnalyzeDataHandle subject;

    @Mock
    private EpisodeRepository episodeRepository;
    @Mock
    private MovieRepository movieRepository;
    @Mock
    private ShowRepository showRepository;
    @Mock
    private LibraryRepository libraryRepository;
    @Mock
    private MetadataRepository metadataRepository;
    @Mock
    private MediaFileStreamRepository mediaFileStreamRepository;
    @Mock
    private ImageRepository imageRepository;
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
    void listenerCallsHandleWithCorrectEventType() {
        UUID episodeId = UUID.randomUUID();
        DirectoryEntity dir = DirectoryEntity.builder().id(UUID.randomUUID()).name("dir1").build();
        EpisodeEntity episode = EpisodeEntity.builder()
                .id(episodeId)
                .mediaFileEntities(List.of(MediaFileEntity.builder()
                        .directoryEntity(dir)
                        .build()))
                .build();
        AnalyzeData data = AnalyzeData.builder()
                .eventType(EventType.ANALYZE_DATA)
                .episodeId(episodeId)
                .build();
        when(episodeRepository.findById(episodeId)).thenReturn(Optional.of(episode));

        assertDoesNotThrow(() -> subject.listener(data));
    }

    @Test
    void handleLibraryIdShowTypeSendsShowFanOut() {
        UUID libraryId = UUID.randomUUID();
        UUID showId1 = UUID.randomUUID();
        UUID showId2 = UUID.randomUUID();
        LibraryEntity library = LibraryEntity.builder()
                .id(libraryId)
                .libraryType(LibraryType.SHOW)
                .build();
        AnalyzeData data = AnalyzeData.builder()
                .eventType(EventType.ANALYZE_DATA)
                .libraryId(libraryId)
                .build();

        when(libraryRepository.findById(libraryId)).thenReturn(Optional.of(library));
        when(showRepository.findIdsByLibraryId(libraryId)).thenReturn(List.of(showId1, showId2));

        assertTrue(subject.handle(data));

        ArgumentCaptor<AnalyzeData> captor = ArgumentCaptor.forClass(AnalyzeData.class);
        verify(messageSender, times(2)).sendAnalyzeData(captor.capture());
        List<UUID> sentShowIds = captor.getAllValues().stream().map(AnalyzeData::getShowId).toList();
        assertTrue(sentShowIds.contains(showId1));
        assertTrue(sentShowIds.contains(showId2));
    }

    @Test
    void handleLibraryIdMovieTypeSendsMovieFanOut() {
        UUID libraryId = UUID.randomUUID();
        UUID movieId1 = UUID.randomUUID();
        LibraryEntity library = LibraryEntity.builder()
                .id(libraryId)
                .libraryType(LibraryType.MOVIE)
                .build();
        AnalyzeData data = AnalyzeData.builder()
                .eventType(EventType.ANALYZE_DATA)
                .libraryId(libraryId)
                .build();

        when(libraryRepository.findById(libraryId)).thenReturn(Optional.of(library));
        when(movieRepository.findIdsByLibraryId(libraryId)).thenReturn(List.of(movieId1));

        assertTrue(subject.handle(data));

        ArgumentCaptor<AnalyzeData> captor = ArgumentCaptor.forClass(AnalyzeData.class);
        verify(messageSender).sendAnalyzeData(captor.capture());
        assertEquals(movieId1, captor.getValue().getMovieId());
        assertEquals(EventType.ANALYZE_DATA, captor.getValue().getEventType());
    }

    @Test
    void handleShowIdSendsShowFoundAndEpisodeFanOut() {
        UUID showId = UUID.randomUUID();
        UUID episodeId1 = UUID.randomUUID();
        UUID episodeId2 = UUID.randomUUID();
        EpisodeEntity ep1 = EpisodeEntity.builder().id(episodeId1).build();
        EpisodeEntity ep2 = EpisodeEntity.builder().id(episodeId2).build();
        AnalyzeData data = AnalyzeData.builder()
                .eventType(EventType.ANALYZE_DATA)
                .showId(showId)
                .build();

        ShowEntity show = ShowEntity.builder().id(showId).metadataEntities(List.of()).imageEntities(List.of()).build();
        when(showRepository.findById(showId)).thenReturn(Optional.of(show));
        when(episodeRepository.findByShowEntityId(eq(showId), any(Sort.class))).thenReturn(List.of(ep1, ep2));

        assertTrue(subject.handle(data));

        verify(messageSender).sendShowFound(any());
        ArgumentCaptor<AnalyzeData> captor = ArgumentCaptor.forClass(AnalyzeData.class);
        verify(messageSender, times(2)).sendAnalyzeData(captor.capture());
        List<UUID> sentEpisodeIds = captor.getAllValues().stream().map(AnalyzeData::getEpisodeId).toList();
        assertTrue(sentEpisodeIds.contains(episodeId1));
        assertTrue(sentEpisodeIds.contains(episodeId2));
    }

    @Test
    void handleEpisodeIdSendsEpisodeFoundAndDirectoryFanOut() {
        UUID episodeId = UUID.randomUUID();
        DirectoryEntity dir = DirectoryEntity.builder().id(UUID.randomUUID()).name("dir1").build();
        MediaFileEntity mf = MediaFileEntity.builder()
                .directoryEntity(dir)
                .build();
        EpisodeEntity episode = EpisodeEntity.builder()
                .id(episodeId)
                .mediaFileEntities(List.of(mf))
                .build();
        AnalyzeData data = AnalyzeData.builder()
                .eventType(EventType.ANALYZE_DATA)
                .episodeId(episodeId)
                .build();

        when(episodeRepository.findById(episodeId)).thenReturn(Optional.of(episode));

        assertTrue(subject.handle(data));

        verify(messageSender).sendEpisodeFound(any());
        ArgumentCaptor<AnalyzeData> captor = ArgumentCaptor.forClass(AnalyzeData.class);
        verify(messageSender).sendAnalyzeData(captor.capture(), eq("dir1"));
        assertEquals(episodeId, captor.getValue().getEpisodeId());
        assertEquals(dir.getId(), captor.getValue().getDirectoryId());
    }

    @Test
    void handleMovieIdSendsMovieFoundAndDirectoryFanOut() {
        UUID movieId = UUID.randomUUID();
        DirectoryEntity dir = DirectoryEntity.builder().id(UUID.randomUUID()).name("movies").build();
        MediaFileEntity mf = MediaFileEntity.builder()
                .directoryEntity(dir)
                .build();
        MovieEntity movie = MovieEntity.builder()
                .id(movieId)
                .mediaFileEntities(List.of(mf))
                .build();
        AnalyzeData data = AnalyzeData.builder()
                .eventType(EventType.ANALYZE_DATA)
                .movieId(movieId)
                .build();

        when(movieRepository.findById(movieId)).thenReturn(Optional.of(movie));

        assertTrue(subject.handle(data));

        verify(messageSender).sendMovieFound(any());
        ArgumentCaptor<AnalyzeData> captor = ArgumentCaptor.forClass(AnalyzeData.class);
        verify(messageSender).sendAnalyzeData(captor.capture(), eq("movies"));
        assertEquals(movieId, captor.getValue().getMovieId());
        assertEquals(dir.getId(), captor.getValue().getDirectoryId());
    }
}
