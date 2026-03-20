package app.ister.disk.events.mediafilefound;

import app.ister.core.entity.*;
import app.ister.core.enums.DirectoryType;
import app.ister.core.enums.EventType;
import app.ister.core.eventdata.ImageFoundData;
import app.ister.core.eventdata.MediaFileFoundData;
import app.ister.core.repository.*;
import app.ister.core.service.MessageSender;
import app.ister.core.service.NodeService;
import com.github.kokorin.jaffree.process.JaffreeAbnormalExitException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class HandleMediaFileFoundTest {
    @Mock
    private NodeService nodeServiceMock;
    @Mock
    private DirectoryRepository directoryRepositoryMock;
    @Mock
    private MediaFileRepository mediaFileRepositoryMock;
    @Mock
    private EpisodeRepository episodeRepositoryMock;
    @Mock
    private MovieRepository movieRepositoryMock;
    @Mock
    private MediaFileStreamRepository mediaFileStreamRepositoryMock;
    @Mock
    private MediaFileFoundCheckForStreams mediaFileFoundCheckForStreamsMock;
    @Mock
    private MediaFileFoundCreateBackground mediaFileFoundCreateBackgroundMock;
    @Mock
    private MediaFileFoundGetDuration mediaFileFoundGetDurationMock;
    @Mock
    private MessageSender messageSenderMock;

    private HandleMediaFileFound subject;

    @Test
    void handles() {
        assertEquals(EventType.MEDIA_FILE_FOUND, subject.handles());
    }

    @BeforeEach
    void setup() {
        subject = new HandleMediaFileFound(
                nodeServiceMock,
                directoryRepositoryMock,
                mediaFileRepositoryMock,
                episodeRepositoryMock,
                movieRepositoryMock,
                mediaFileStreamRepositoryMock,
                mediaFileFoundCheckForStreamsMock,
                mediaFileFoundCreateBackgroundMock,
                mediaFileFoundGetDurationMock,
                messageSenderMock);
    }

    @Test
    void listenerThrowsOnWrongEventType() {
        MediaFileFoundData data = MediaFileFoundData.builder()
                .eventType(EventType.FILE_SCAN_REQUESTED)
                .build();
        assertThrows(IllegalArgumentException.class, () -> subject.listener(data));
    }

    @Test
    void happyFlowWithEpisodeThatHasImages() {
        DirectoryEntity directoryEntity = DirectoryEntity.builder().build();
        EpisodeEntity episodeEntity = EpisodeEntity.builder()
                .id(UUID.randomUUID())
                .imagesEntities(List.of(
                        ImageEntity.builder().build()
                )).build();
        String filePath = "/home/path";
        MediaFileFoundData mediaFileFoundData = MediaFileFoundData.builder()
                .eventType(EventType.MEDIA_FILE_FOUND)
                .directoryEntityUUID(directoryEntity.getId())
                .episodeEntityUUID(episodeEntity.getId())
                .path(filePath)
                .build();
        MediaFileEntity mediaFileEntity = MediaFileEntity.builder().path(filePath).build();
        MediaFileStreamEntity mediaFileStreamEntity = MediaFileStreamEntity.builder().build();

        when(directoryRepositoryMock.findById(directoryEntity.getId())).thenReturn(Optional.of(directoryEntity));
        when(episodeRepositoryMock.findById(episodeEntity.getId())).thenReturn(Optional.of(episodeEntity));
        when(mediaFileRepositoryMock.findByDirectoryEntityAndPath(directoryEntity, filePath)).thenReturn(Optional.of(mediaFileEntity));
        when(mediaFileFoundGetDurationMock.getDuration(filePath)).thenReturn(10L);
        when(mediaFileFoundCheckForStreamsMock.checkForStreams(mediaFileEntity, null)).thenReturn(List.of(mediaFileStreamEntity));

        assertTrue(subject.handle(mediaFileFoundData));

        verify(mediaFileRepositoryMock).save(mediaFileEntity);
        verifyNoInteractions(nodeServiceMock);
    }

    @Test
    void handleWithMediaFileNotFoundSkipsProcessing() {
        DirectoryEntity directoryEntity = DirectoryEntity.builder().build();
        UUID episodeId = UUID.randomUUID();
        EpisodeEntity episodeEntity = EpisodeEntity.builder().id(episodeId).imagesEntities(new ArrayList<>()).build();
        String filePath = "/home/path";
        MediaFileFoundData data = MediaFileFoundData.builder()
                .eventType(EventType.MEDIA_FILE_FOUND)
                .directoryEntityUUID(directoryEntity.getId())
                .episodeEntityUUID(episodeId)
                .path(filePath)
                .build();

        when(directoryRepositoryMock.findById(directoryEntity.getId())).thenReturn(Optional.of(directoryEntity));
        when(episodeRepositoryMock.findById(episodeId)).thenReturn(Optional.of(episodeEntity));
        when(mediaFileRepositoryMock.findByDirectoryEntityAndPath(directoryEntity, filePath)).thenReturn(Optional.empty());

        assertTrue(subject.handle(data));

        verifyNoInteractions(nodeServiceMock, mediaFileFoundGetDurationMock, mediaFileFoundCheckForStreamsMock);
    }

    @Test
    void handleWithMovieEntityUUID() {
        DirectoryEntity directoryEntity = DirectoryEntity.builder().build();
        UUID movieId = UUID.randomUUID();
        MovieEntity movieEntity = MovieEntity.builder().id(movieId).imagesEntities(List.of(ImageEntity.builder().build())).build();
        String filePath = "/home/movie.mkv";
        MediaFileFoundData data = MediaFileFoundData.builder()
                .eventType(EventType.MEDIA_FILE_FOUND)
                .directoryEntityUUID(directoryEntity.getId())
                .movieEntityUUID(movieId)
                .path(filePath)
                .build();
        MediaFileEntity mediaFileEntity = MediaFileEntity.builder().path(filePath).build();

        when(directoryRepositoryMock.findById(directoryEntity.getId())).thenReturn(Optional.of(directoryEntity));
        when(movieRepositoryMock.findById(movieId)).thenReturn(Optional.of(movieEntity));
        when(mediaFileRepositoryMock.findByDirectoryEntityAndPath(directoryEntity, filePath)).thenReturn(Optional.of(mediaFileEntity));
        when(mediaFileFoundGetDurationMock.getDuration(filePath)).thenReturn(5000L);
        when(mediaFileFoundCheckForStreamsMock.checkForStreams(eq(mediaFileEntity), any())).thenReturn(List.of());

        assertTrue(subject.handle(data));

        verify(mediaFileRepositoryMock).save(mediaFileEntity);
        verifyNoInteractions(nodeServiceMock);
    }

    @Test
    void happyFlowWithEpisodeWithoutImagesCreatesBackground() {
        ReflectionTestUtils.setField(subject, "dirOfFFmpeg", "/usr/bin");
        DirectoryEntity directoryEntity = DirectoryEntity.builder().build();
        UUID episodeId = UUID.randomUUID();
        EpisodeEntity episodeEntity = EpisodeEntity.builder().id(episodeId).imagesEntities(new ArrayList<>()).build();
        String filePath = "/home/path/episode.mkv";
        MediaFileFoundData data = MediaFileFoundData.builder()
                .eventType(EventType.MEDIA_FILE_FOUND)
                .directoryEntityUUID(directoryEntity.getId())
                .episodeEntityUUID(episodeId)
                .path(filePath)
                .build();
        MediaFileEntity mediaFileEntity = MediaFileEntity.builder().path(filePath).build();
        NodeEntity nodeEntity = NodeEntity.builder().name("node1").build();
        DirectoryEntity cacheDirectory = DirectoryEntity.builder()
                .id(UUID.randomUUID()).path("/cache/").name("cache").build();

        when(directoryRepositoryMock.findById(directoryEntity.getId())).thenReturn(Optional.of(directoryEntity));
        when(episodeRepositoryMock.findById(episodeId)).thenReturn(Optional.of(episodeEntity));
        when(mediaFileRepositoryMock.findByDirectoryEntityAndPath(directoryEntity, filePath)).thenReturn(Optional.of(mediaFileEntity));
        when(mediaFileFoundGetDurationMock.getDuration(filePath)).thenReturn(10000L);
        when(mediaFileFoundCheckForStreamsMock.checkForStreams(eq(mediaFileEntity), any())).thenReturn(List.of());
        when(nodeServiceMock.getOrCreateNodeEntityForThisNode()).thenReturn(nodeEntity);
        when(directoryRepositoryMock.findByDirectoryTypeAndNodeEntity(DirectoryType.CACHE, nodeEntity)).thenReturn(List.of(cacheDirectory));

        assertTrue(subject.handle(data));

        verify(mediaFileRepositoryMock).save(mediaFileEntity);
        verify(messageSenderMock).sendImageFound(any(ImageFoundData.class), eq("cache"));
    }

    @Test
    void happyFlowWithMovieWithoutImagesCreatesBackground() {
        ReflectionTestUtils.setField(subject, "dirOfFFmpeg", "/usr/bin");
        DirectoryEntity directoryEntity = DirectoryEntity.builder().build();
        UUID movieId = UUID.randomUUID();
        MovieEntity movieEntity = MovieEntity.builder().id(movieId).imagesEntities(new ArrayList<>()).build();
        String filePath = "/home/path/movie.mkv";
        MediaFileFoundData data = MediaFileFoundData.builder()
                .eventType(EventType.MEDIA_FILE_FOUND)
                .directoryEntityUUID(directoryEntity.getId())
                .movieEntityUUID(movieId)
                .path(filePath)
                .build();
        MediaFileEntity mediaFileEntity = MediaFileEntity.builder().path(filePath).build();
        NodeEntity nodeEntity = NodeEntity.builder().name("node1").build();
        DirectoryEntity cacheDirectory = DirectoryEntity.builder()
                .id(UUID.randomUUID()).path("/cache/").name("cache").build();

        when(directoryRepositoryMock.findById(directoryEntity.getId())).thenReturn(Optional.of(directoryEntity));
        when(movieRepositoryMock.findById(movieId)).thenReturn(Optional.of(movieEntity));
        when(mediaFileRepositoryMock.findByDirectoryEntityAndPath(directoryEntity, filePath)).thenReturn(Optional.of(mediaFileEntity));
        when(mediaFileFoundGetDurationMock.getDuration(filePath)).thenReturn(5000L);
        when(mediaFileFoundCheckForStreamsMock.checkForStreams(eq(mediaFileEntity), any())).thenReturn(List.of());
        when(nodeServiceMock.getOrCreateNodeEntityForThisNode()).thenReturn(nodeEntity);
        when(directoryRepositoryMock.findByDirectoryTypeAndNodeEntity(DirectoryType.CACHE, nodeEntity)).thenReturn(List.of(cacheDirectory));

        assertTrue(subject.handle(data));

        verify(mediaFileRepositoryMock).save(mediaFileEntity);
        verify(messageSenderMock).sendImageFound(any(ImageFoundData.class), eq("cache"));
    }

    @Test
    void createBackgroundHandlesJaffreeAbnormalExitException() {
        ReflectionTestUtils.setField(subject, "dirOfFFmpeg", "/usr/bin");
        DirectoryEntity directoryEntity = DirectoryEntity.builder().build();
        UUID episodeId = UUID.randomUUID();
        EpisodeEntity episodeEntity = EpisodeEntity.builder().id(episodeId).imagesEntities(new ArrayList<>()).build();
        String filePath = "/home/path/episode.mkv";
        MediaFileFoundData data = MediaFileFoundData.builder()
                .eventType(EventType.MEDIA_FILE_FOUND)
                .directoryEntityUUID(directoryEntity.getId())
                .episodeEntityUUID(episodeId)
                .path(filePath)
                .build();
        MediaFileEntity mediaFileEntity = MediaFileEntity.builder().path(filePath).build();
        NodeEntity nodeEntity = NodeEntity.builder().name("node1").build();
        DirectoryEntity cacheDirectory = DirectoryEntity.builder()
                .id(UUID.randomUUID()).path("/cache/").name("cache").build();

        when(directoryRepositoryMock.findById(directoryEntity.getId())).thenReturn(Optional.of(directoryEntity));
        when(episodeRepositoryMock.findById(episodeId)).thenReturn(Optional.of(episodeEntity));
        when(mediaFileRepositoryMock.findByDirectoryEntityAndPath(directoryEntity, filePath)).thenReturn(Optional.of(mediaFileEntity));
        when(mediaFileFoundGetDurationMock.getDuration(filePath)).thenReturn(10000L);
        when(mediaFileFoundCheckForStreamsMock.checkForStreams(eq(mediaFileEntity), any())).thenReturn(List.of());
        when(nodeServiceMock.getOrCreateNodeEntityForThisNode()).thenReturn(nodeEntity);
        when(directoryRepositoryMock.findByDirectoryTypeAndNodeEntity(DirectoryType.CACHE, nodeEntity)).thenReturn(List.of(cacheDirectory));
        doThrow(mock(JaffreeAbnormalExitException.class))
                .when(mediaFileFoundCreateBackgroundMock).createBackground(any(), any(), any(), anyLong());

        assertTrue(subject.handle(data));

        verify(mediaFileRepositoryMock).save(mediaFileEntity);
        verifyNoInteractions(messageSenderMock);
    }

    @Test
    void listenerCallsHandleWithCorrectEventType() {
        DirectoryEntity directoryEntity = DirectoryEntity.builder().build();
        UUID episodeId = UUID.randomUUID();
        EpisodeEntity episodeEntity = EpisodeEntity.builder()
                .id(episodeId)
                .imagesEntities(List.of(ImageEntity.builder().build()))
                .build();
        String filePath = "/home/path";
        MediaFileFoundData data = MediaFileFoundData.builder()
                .eventType(EventType.MEDIA_FILE_FOUND)
                .directoryEntityUUID(directoryEntity.getId())
                .episodeEntityUUID(episodeEntity.getId())
                .path(filePath)
                .build();
        MediaFileEntity mediaFileEntity = MediaFileEntity.builder().path(filePath).build();

        when(directoryRepositoryMock.findById(directoryEntity.getId())).thenReturn(Optional.of(directoryEntity));
        when(episodeRepositoryMock.findById(episodeEntity.getId())).thenReturn(Optional.of(episodeEntity));
        when(mediaFileRepositoryMock.findByDirectoryEntityAndPath(directoryEntity, filePath)).thenReturn(Optional.of(mediaFileEntity));
        when(mediaFileFoundGetDurationMock.getDuration(filePath)).thenReturn(10L);
        when(mediaFileFoundCheckForStreamsMock.checkForStreams(eq(mediaFileEntity), any())).thenReturn(List.of());

        subject.listener(data); // should not throw
    }
}
