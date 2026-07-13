package app.ister.worker.events.analyzelibraryrequested;

import app.ister.core.entity.DirectoryEntity;
import app.ister.core.entity.NodeEntity;
import app.ister.core.entity.PersonEntity;
import app.ister.core.enums.DirectoryType;
import app.ister.core.enums.EventType;
import app.ister.core.enums.LibraryType;
import app.ister.core.eventdata.AnalyzeLibraryRequestedData;
import app.ister.core.eventdata.PersonFoundData;
import app.ister.core.eventdata.UpdateImagesRequestedData;
import app.ister.core.repository.AlbumRepository;
import app.ister.core.repository.PersonRepository;
import app.ister.core.repository.DirectoryRepository;
import app.ister.core.repository.EpisodeRepository;
import app.ister.core.repository.MovieRepository;
import app.ister.core.repository.ShowRepository;
import app.ister.core.repository.TrackRepository;
import app.ister.core.service.MessageSender;
import app.ister.core.service.NodeService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AnalyzeLibraryRequestedHandleTest {

    @InjectMocks
    private AnalyzeLibraryRequestedHandle subject;

    @Mock
    private ShowRepository showRepository;

    @Mock
    private EpisodeRepository episodeRepository;

    @Mock
    private MovieRepository movieRepository;

    @Mock
    private MessageSender messageSender;

    @Mock
    private NodeService nodeService;

    @Mock
    private DirectoryRepository directoryRepository;

    @Mock
    private PersonRepository personRepository;

    @Mock
    private AlbumRepository albumRepository;

    @Mock
    private TrackRepository trackRepository;

    @Test
    void handles() {
        assertEquals(EventType.ANALYZE_LIBRARY_REQUEST, subject.handles());
    }

    @Test
    void listenerThrowsOnWrongEventType() {
        AnalyzeLibraryRequestedData data = AnalyzeLibraryRequestedData.builder()
                .eventType(EventType.FILE_SCAN_REQUESTED)
                .build();
        assertThrows(IllegalArgumentException.class, () -> subject.listener(data));
    }

    @Test
    void handleSendsUpdateImagesPerDirectoryAndDispatchesMetadataEvents() {
        NodeEntity nodeEntity = NodeEntity.builder().name("TestServer").build();
        DirectoryEntity library = DirectoryEntity.builder()
                .id(UUID.randomUUID()).name("disk1").directoryType(DirectoryType.LIBRARY).build();
        DirectoryEntity cache = DirectoryEntity.builder()
                .id(UUID.randomUUID()).name("TestServer-cache-directory").directoryType(DirectoryType.CACHE).build();
        UUID showId = UUID.randomUUID();
        UUID episodeId = UUID.randomUUID();
        UUID movieId = UUID.randomUUID();
        AnalyzeLibraryRequestedData data = AnalyzeLibraryRequestedData.builder()
                .eventType(EventType.ANALYZE_LIBRARY_REQUEST)
                .build();

        when(nodeService.getOrCreateNodeEntityForThisNode()).thenReturn(nodeEntity);
        when(directoryRepository.findByNodeEntity(nodeEntity)).thenReturn(List.of(library, cache));
        when(showRepository.findIdsOfShowsWithoutMetadataForNode("TestServer")).thenReturn(List.of(showId));
        when(episodeRepository.findIdsOfEpisodesWithoutMetadataForNode("TestServer")).thenReturn(List.of(episodeId));
        when(movieRepository.findIdsOfMoviesWithoutMetadataForNode("TestServer")).thenReturn(List.of(movieId));

        subject.handle(data);

        verify(messageSender).sendUpdateImagesRequested(any(), eq("disk1"));
        // The cache directory holds the downloaded artwork -- by far the most images -- so it must
        // get a sweep of its own, not just the library directories.
        verify(messageSender).sendUpdateImagesRequested(any(), eq("TestServer-cache-directory"));
        verify(messageSender).sendShowFound(any());
        verify(messageSender).sendEpisodeFound(any());
        verify(messageSender).sendMovieFound(any());
    }

    @Test
    void handleDispatchesPersonFoundForMusicArtistsAndBookAuthorsWithoutMetadata() {
        NodeEntity nodeEntity = NodeEntity.builder().name("TestServer").build();
        PersonEntity artist = PersonEntity.builder().id(UUID.randomUUID()).name("Artist").build();
        PersonEntity author = PersonEntity.builder().id(UUID.randomUUID()).name("Author").build();

        when(nodeService.getOrCreateNodeEntityForThisNode()).thenReturn(nodeEntity);
        when(personRepository.findByLibraryEntity_LibraryTypeAndMetadataEntitiesIsEmpty(LibraryType.MUSIC))
                .thenReturn(List.of(artist));
        when(personRepository.findByLibraryEntity_LibraryTypeAndMetadataEntitiesIsEmpty(LibraryType.BOOK))
                .thenReturn(List.of(author));

        subject.handle(AnalyzeLibraryRequestedData.builder().eventType(EventType.ANALYZE_LIBRARY_REQUEST).build());

        // Unrouted, i.e. onto the global queue: only the worker's HandlePersonFound (MusicBrainz /
        // Open Library / Wikipedia) listens there. A node-scoped send would reach the disk handler,
        // which merely re-parses artist.nfo, and the enrichment this dispatch exists for never runs.
        ArgumentCaptor<PersonFoundData> captor = ArgumentCaptor.forClass(PersonFoundData.class);
        verify(messageSender, times(2)).sendPersonFound(captor.capture());
        verify(messageSender, never()).sendPersonFound(any(), anyString());
        assertEquals(List.of(artist.getId(), author.getId()),
                captor.getAllValues().stream().map(PersonFoundData::getPersonId).toList());
    }

    @Test
    void handleStartsEachSweepAtTheBeginningOfItsDirectory() {
        NodeEntity nodeEntity = NodeEntity.builder().name("TestServer").build();
        DirectoryEntity directory = DirectoryEntity.builder()
                .id(UUID.randomUUID()).name("disk1").directoryType(DirectoryType.LIBRARY).build();

        when(nodeService.getOrCreateNodeEntityForThisNode()).thenReturn(nodeEntity);
        when(directoryRepository.findByNodeEntity(nodeEntity)).thenReturn(List.of(directory));

        subject.handle(AnalyzeLibraryRequestedData.builder().eventType(EventType.ANALYZE_LIBRARY_REQUEST).build());

        ArgumentCaptor<UpdateImagesRequestedData> sweep = ArgumentCaptor.forClass(UpdateImagesRequestedData.class);
        verify(messageSender).sendUpdateImagesRequested(sweep.capture(), eq("disk1"));
        assertEquals(directory.getId(), sweep.getValue().getDirectoryEntityId());
        assertEquals("disk1", sweep.getValue().getDirectoryName());
        assertNull(sweep.getValue().getAfterId(), "a fresh sweep must start without a cursor");
    }
}
