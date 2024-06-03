package app.ister.server.eventHandlers;

import app.ister.server.entitiy.DirectoryEntity;
import app.ister.server.entitiy.EpisodeEntity;
import app.ister.server.entitiy.ImageEntity;
import app.ister.server.entitiy.MediaFileEntity;
import app.ister.server.entitiy.MediaFileStreamEntity;
import app.ister.server.entitiy.NodeEntity;
import app.ister.server.entitiy.ServerEventEntity;
import app.ister.server.enums.DirectoryType;
import app.ister.server.enums.EventType;
import app.ister.server.eventHandlers.mediaFileFound.MediaFileFoundCheckForStreams;
import app.ister.server.eventHandlers.mediaFileFound.MediaFileFoundCreateBackground;
import app.ister.server.eventHandlers.mediaFileFound.MediaFileFoundGetDuration;
import app.ister.server.repository.DirectoryRepository;
import app.ister.server.repository.ImageRepository;
import app.ister.server.repository.MediaFileRepository;
import app.ister.server.repository.MediaFileStreamRepository;
import app.ister.server.service.NodeService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
    private MediaFileStreamRepository mediaFileStreamRepositoryMock;
    @Mock
    private ImageRepository imageRepositoryMock;

    @Mock
    private MediaFileFoundCheckForStreams mediaFileFoundCheckForStreamsMock;
    @Mock
    private MediaFileFoundCreateBackground mediaFileFoundCreateBackgroundMock;
    @Mock
    private MediaFileFoundGetDuration mediaFileFoundGetDurationMock;

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
                mediaFileStreamRepositoryMock,
                imageRepositoryMock,
                mediaFileFoundCheckForStreamsMock,
                mediaFileFoundCreateBackgroundMock,
                mediaFileFoundGetDurationMock);
    }

    @Test
    void happyFlow() {
        DirectoryEntity directoryEntity = DirectoryEntity.builder().build();
        EpisodeEntity episodeEntity = EpisodeEntity.builder()
                .id(UUID.randomUUID())
                .imagesEntities(List.of(
                        ImageEntity.builder().build()
                )).build();
        String filePath = "/home/path";
        ServerEventEntity serverEventEntity = ServerEventEntity.builder()
                .eventType(EventType.MEDIA_FILE_FOUND)
                .directoryEntity(directoryEntity)
                .episodeEntity(episodeEntity)
                .path(filePath)
                .build();
        MediaFileEntity mediaFileEntity = MediaFileEntity.builder().path(filePath).build();
        MediaFileStreamEntity mediaFileStreamEntity = MediaFileStreamEntity.builder().build();

        when(mediaFileRepositoryMock.findByDirectoryEntityAndEpisodeEntityAndPath(directoryEntity, episodeEntity, filePath)).thenReturn(Optional.of(mediaFileEntity));
        when(mediaFileFoundGetDurationMock.getDuration(filePath)).thenReturn(10L);
        when(mediaFileFoundCheckForStreamsMock.checkForStreams(mediaFileEntity, null)).thenReturn(List.of(mediaFileStreamEntity));

        subject.handle(serverEventEntity);
    }
}