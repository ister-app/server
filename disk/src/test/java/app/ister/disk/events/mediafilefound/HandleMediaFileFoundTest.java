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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
    private ImageRepository imageRepositoryMock;
    @Mock
    private MediaFileFoundCheckForStreams mediaFileFoundCheckForStreamsMock;
    @Mock
    private MediaFileFoundCreateBackground mediaFileFoundCreateBackgroundMock;
    @Mock
    private MediaFileFoundGetDuration mediaFileFoundGetDurationMock;
    @Mock
    private MediaFileFoundExtractSubtitles mediaFileFoundExtractSubtitlesMock;
    @Mock
    private MediaFileEpisodeRepository mediaFileEpisodeRepositoryMock;
    @Mock
    private MediaFileFoundEpisodeBoundaries mediaFileFoundEpisodeBoundariesMock;
    @Mock
    private MessageSender messageSenderMock;

    @InjectMocks
    private HandleMediaFileFound subject;

    @Test
    void handles() {
        assertEquals(EventType.MEDIA_FILE_FOUND, subject.handles());
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
        EpisodeEntity episodeEntity = EpisodeEntity.builder().seasonEntity(testSeason())
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
        NodeEntity nodeEntity = NodeEntity.builder().name("node1").build();
        DirectoryEntity cacheDirectory = DirectoryEntity.builder()
                .id(UUID.randomUUID()).path("/cache/").name("cache").build();

        when(directoryRepositoryMock.findById(directoryEntity.getId())).thenReturn(Optional.of(directoryEntity));
        when(episodeRepositoryMock.findById(episodeEntity.getId())).thenReturn(Optional.of(episodeEntity));
        when(mediaFileRepositoryMock.findByDirectoryEntityAndPath(directoryEntity, filePath)).thenReturn(Optional.of(mediaFileEntity));
        when(mediaFileFoundCheckForStreamsMock.checkForStreams(mediaFileEntity, null)).thenReturn(new MediaFileFoundCheckForStreams.CheckResult(List.of(mediaFileStreamEntity), false, 10L));
        when(imageRepositoryMock.existsByEpisodeEntityId(episodeEntity.getId())).thenReturn(true);
        when(nodeServiceMock.getOrCreateNodeEntityForThisNode()).thenReturn(nodeEntity);
        when(directoryRepositoryMock.findByDirectoryTypeAndNodeEntity(DirectoryType.CACHE, nodeEntity)).thenReturn(List.of(cacheDirectory));
        when(mediaFileFoundExtractSubtitlesMock.extractSubtitles(any(), any(), any(), any())).thenReturn(List.of());

        subject.handle(mediaFileFoundData);

        verify(mediaFileRepositoryMock).save(mediaFileEntity);
    }

    @Test
    void handleWithMediaFileNotFoundSkipsProcessing() {
        DirectoryEntity directoryEntity = DirectoryEntity.builder().build();
        UUID episodeId = UUID.randomUUID();
        EpisodeEntity episodeEntity = EpisodeEntity.builder().seasonEntity(testSeason()).id(episodeId).imagesEntities(new ArrayList<>()).build();
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

        subject.handle(data);

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
        NodeEntity nodeEntity = NodeEntity.builder().name("node1").build();
        DirectoryEntity cacheDirectory = DirectoryEntity.builder()
                .id(UUID.randomUUID()).path("/cache/").name("cache").build();

        when(directoryRepositoryMock.findById(directoryEntity.getId())).thenReturn(Optional.of(directoryEntity));
        when(movieRepositoryMock.findById(movieId)).thenReturn(Optional.of(movieEntity));
        when(mediaFileRepositoryMock.findByDirectoryEntityAndPath(directoryEntity, filePath)).thenReturn(Optional.of(mediaFileEntity));
        when(mediaFileFoundCheckForStreamsMock.checkForStreams(eq(mediaFileEntity), any())).thenReturn(new MediaFileFoundCheckForStreams.CheckResult(List.of(), false, 5000L));
        when(imageRepositoryMock.existsByMovieEntityId(movieId)).thenReturn(true);
        when(nodeServiceMock.getOrCreateNodeEntityForThisNode()).thenReturn(nodeEntity);
        when(directoryRepositoryMock.findByDirectoryTypeAndNodeEntity(DirectoryType.CACHE, nodeEntity)).thenReturn(List.of(cacheDirectory));
        when(mediaFileFoundExtractSubtitlesMock.extractSubtitles(any(), any(), any(), any())).thenReturn(List.of());

        subject.handle(data);

        verify(mediaFileRepositoryMock).save(mediaFileEntity);
    }

    @Test
    void happyFlowWithEpisodeWithoutImagesCreatesBackground() {
        ReflectionTestUtils.setField(subject, "dirOfFFmpeg", "/usr/bin");
        DirectoryEntity directoryEntity = DirectoryEntity.builder().build();
        UUID episodeId = UUID.randomUUID();
        EpisodeEntity episodeEntity = EpisodeEntity.builder().seasonEntity(testSeason()).id(episodeId).imagesEntities(new ArrayList<>()).build();
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
        when(mediaFileFoundCheckForStreamsMock.checkForStreams(eq(mediaFileEntity), any())).thenReturn(new MediaFileFoundCheckForStreams.CheckResult(List.of(), false, 10000L));
        when(nodeServiceMock.getOrCreateNodeEntityForThisNode()).thenReturn(nodeEntity);
        when(directoryRepositoryMock.findByDirectoryTypeAndNodeEntity(DirectoryType.CACHE, nodeEntity)).thenReturn(List.of(cacheDirectory));

        subject.handle(data);

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
        when(mediaFileFoundCheckForStreamsMock.checkForStreams(eq(mediaFileEntity), any())).thenReturn(new MediaFileFoundCheckForStreams.CheckResult(List.of(), false, 5000L));
        when(nodeServiceMock.getOrCreateNodeEntityForThisNode()).thenReturn(nodeEntity);
        when(directoryRepositoryMock.findByDirectoryTypeAndNodeEntity(DirectoryType.CACHE, nodeEntity)).thenReturn(List.of(cacheDirectory));

        subject.handle(data);

        verify(mediaFileRepositoryMock).save(mediaFileEntity);
        verify(messageSenderMock).sendImageFound(any(ImageFoundData.class), eq("cache"));
    }

    @Test
    void createBackgroundHandlesJaffreeAbnormalExitException() {
        ReflectionTestUtils.setField(subject, "dirOfFFmpeg", "/usr/bin");
        DirectoryEntity directoryEntity = DirectoryEntity.builder().build();
        UUID episodeId = UUID.randomUUID();
        EpisodeEntity episodeEntity = EpisodeEntity.builder().seasonEntity(testSeason()).id(episodeId).imagesEntities(new ArrayList<>()).build();
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
        when(mediaFileFoundCheckForStreamsMock.checkForStreams(eq(mediaFileEntity), any())).thenReturn(new MediaFileFoundCheckForStreams.CheckResult(List.of(), false, 10000L));
        when(nodeServiceMock.getOrCreateNodeEntityForThisNode()).thenReturn(nodeEntity);
        when(directoryRepositoryMock.findByDirectoryTypeAndNodeEntity(DirectoryType.CACHE, nodeEntity)).thenReturn(List.of(cacheDirectory));
        doThrow(mock(JaffreeAbnormalExitException.class))
                .when(mediaFileFoundCreateBackgroundMock).createBackground(any(), any(), any(), anyLong());

        subject.handle(data);

        verify(mediaFileRepositoryMock).save(mediaFileEntity);
        // The failed background must not be announced; the season's segment detection still fires.
        verify(messageSenderMock, never()).sendImageFound(any(), any());
        verify(messageSenderMock).sendDetectSegments(any(), any());
    }

    @Test
    void listenerCallsHandleWithCorrectEventType() {
        DirectoryEntity directoryEntity = DirectoryEntity.builder().build();
        UUID episodeId = UUID.randomUUID();
        EpisodeEntity episodeEntity = EpisodeEntity.builder().seasonEntity(testSeason())
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
        NodeEntity nodeEntity = NodeEntity.builder().name("node1").build();
        DirectoryEntity cacheDirectory = DirectoryEntity.builder()
                .id(UUID.randomUUID()).path("/cache/").name("cache").build();

        when(directoryRepositoryMock.findById(directoryEntity.getId())).thenReturn(Optional.of(directoryEntity));
        when(episodeRepositoryMock.findById(episodeEntity.getId())).thenReturn(Optional.of(episodeEntity));
        when(mediaFileRepositoryMock.findByDirectoryEntityAndPath(directoryEntity, filePath)).thenReturn(Optional.of(mediaFileEntity));
        when(mediaFileFoundCheckForStreamsMock.checkForStreams(eq(mediaFileEntity), any())).thenReturn(new MediaFileFoundCheckForStreams.CheckResult(List.of(), false, 10L));
        when(nodeServiceMock.getOrCreateNodeEntityForThisNode()).thenReturn(nodeEntity);
        when(directoryRepositoryMock.findByDirectoryTypeAndNodeEntity(DirectoryType.CACHE, nodeEntity)).thenReturn(List.of(cacheDirectory));
        when(mediaFileFoundExtractSubtitlesMock.extractSubtitles(any(), any(), any(), any())).thenReturn(List.of());
        when(imageRepositoryMock.existsByEpisodeEntityId(episodeEntity.getId())).thenReturn(true);

        assertDoesNotThrow(() -> subject.listener(data));
    }

    @Test
    void multiEpisodeFileStoresBoundariesAndCreatesAStillPerEpisode() {
        ReflectionTestUtils.setField(subject, "dirOfFFmpeg", "/usr/bin");
        DirectoryEntity directoryEntity = DirectoryEntity.builder().build();
        UUID fileId = UUID.randomUUID();
        UUID episode6Id = UUID.randomUUID();
        UUID episode7Id = UUID.randomUUID();
        String filePath = "/home/path/s04e06-e07.mkv";
        MediaFileFoundData data = MediaFileFoundData.builder()
                .eventType(EventType.MEDIA_FILE_FOUND)
                .directoryEntityUUID(directoryEntity.getId())
                .episodeEntityUUID(episode6Id)
                .episodeEntityUUIDs(List.of(episode6Id, episode7Id))
                .path(filePath)
                .build();
        MediaFileEntity mediaFileEntity = MediaFileEntity.builder().id(fileId).path(filePath).build();
        NodeEntity nodeEntity = NodeEntity.builder().name("node1").build();
        DirectoryEntity cacheDirectory = DirectoryEntity.builder()
                .id(UUID.randomUUID()).path("/cache/").name("cache").build();
        MediaFileEpisodeEntity part0 = MediaFileEpisodeEntity.builder()
                .mediaFileEntityId(fileId).episodeEntityId(episode6Id).partNumber(0).build();
        MediaFileEpisodeEntity part1 = MediaFileEpisodeEntity.builder()
                .mediaFileEntityId(fileId).episodeEntityId(episode7Id).partNumber(1).build();

        when(directoryRepositoryMock.findById(directoryEntity.getId())).thenReturn(Optional.of(directoryEntity));
        when(episodeRepositoryMock.findById(episode6Id)).thenReturn(Optional.of(EpisodeEntity.builder().seasonEntity(testSeason()).id(episode6Id).build()));
        when(episodeRepositoryMock.findById(episode7Id)).thenReturn(Optional.of(EpisodeEntity.builder().seasonEntity(testSeason()).id(episode7Id).build()));
        when(mediaFileRepositoryMock.findByDirectoryEntityAndPath(directoryEntity, filePath)).thenReturn(Optional.of(mediaFileEntity));
        when(mediaFileFoundCheckForStreamsMock.checkForStreams(eq(mediaFileEntity), any())).thenReturn(new MediaFileFoundCheckForStreams.CheckResult(List.of(), false, 5400000L));
        when(mediaFileEpisodeRepositoryMock.findByMediaFileEntityIdOrderByPartNumber(fileId)).thenReturn(List.of(part0, part1));
        when(mediaFileFoundEpisodeBoundariesMock.boundaryStarts(filePath, "/usr/bin", 5400000L, 2)).thenReturn(List.of(0L, 2650000L));
        when(imageRepositoryMock.existsByEpisodeEntityId(any())).thenReturn(false);
        when(nodeServiceMock.getOrCreateNodeEntityForThisNode()).thenReturn(nodeEntity);
        when(directoryRepositoryMock.findByDirectoryTypeAndNodeEntity(DirectoryType.CACHE, nodeEntity)).thenReturn(List.of(cacheDirectory));
        when(mediaFileFoundExtractSubtitlesMock.extractSubtitles(any(), any(), any(), any())).thenReturn(List.of());

        subject.handle(data);

        assertEquals(0L, part0.getStartInMilliseconds());
        assertEquals(2650000L, part0.getDurationInMilliseconds());
        assertEquals(2650000L, part1.getStartInMilliseconds());
        assertEquals(2750000L, part1.getDurationInMilliseconds());
        verify(mediaFileEpisodeRepositoryMock).saveAll(List.of(part0, part1));
        // One still per episode, each at the midpoint of its own slice.
        verify(mediaFileFoundCreateBackgroundMock).createBackground(any(), any(), any(), eq(1325000L));
        verify(mediaFileFoundCreateBackgroundMock).createBackground(any(), any(), any(), eq(4025000L));
        verify(messageSenderMock, times(2)).sendImageFound(any(ImageFoundData.class), eq("cache"));
    }

    private static SeasonEntity testSeason() {
        return SeasonEntity.builder().id(UUID.randomUUID()).build();
    }
}
