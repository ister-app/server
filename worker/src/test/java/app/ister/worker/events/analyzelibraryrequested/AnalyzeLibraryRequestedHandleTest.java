package app.ister.worker.events.analyzelibraryrequested;

import app.ister.core.entity.DirectoryEntity;
import app.ister.core.entity.NodeEntity;
import app.ister.core.enums.DirectoryType;
import app.ister.core.enums.EventType;
import app.ister.core.eventdata.AnalyzeLibraryRequestedData;
import app.ister.core.repository.DirectoryRepository;
import app.ister.core.repository.EpisodeRepository;
import app.ister.core.repository.MovieRepository;
import app.ister.core.repository.ShowRepository;
import app.ister.core.service.MessageSender;
import app.ister.core.service.NodeService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
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
        DirectoryEntity dir1 = DirectoryEntity.builder().name("disk1").build();
        DirectoryEntity dir2 = DirectoryEntity.builder().name("disk2").build();
        UUID showId = UUID.randomUUID();
        UUID episodeId = UUID.randomUUID();
        UUID movieId = UUID.randomUUID();
        AnalyzeLibraryRequestedData data = AnalyzeLibraryRequestedData.builder()
                .eventType(EventType.ANALYZE_LIBRARY_REQUEST)
                .build();

        when(nodeService.getOrCreateNodeEntityForThisNode()).thenReturn(nodeEntity);
        when(directoryRepository.findByDirectoryTypeAndNodeEntity(DirectoryType.LIBRARY, nodeEntity))
                .thenReturn(List.of(dir1, dir2));
        when(showRepository.findIdsOfShowsWithoutMetadataForNode("TestServer")).thenReturn(List.of(showId));
        when(episodeRepository.findIdsOfEpisodesWithoutMetadataForNode("TestServer")).thenReturn(List.of(episodeId));
        when(movieRepository.findIdsOfMoviesWithoutMetadataForNode("TestServer")).thenReturn(List.of(movieId));

        assertTrue(subject.handle(data));

        verify(messageSender).sendUpdateImagesRequested(any(), eq("disk1"));
        verify(messageSender).sendUpdateImagesRequested(any(), eq("disk2"));
        verify(messageSender).sendShowFound(any());
        verify(messageSender).sendEpisodeFound(any());
        verify(messageSender).sendMovieFound(any());
    }
}
