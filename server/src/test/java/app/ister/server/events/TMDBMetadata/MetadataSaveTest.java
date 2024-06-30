package app.ister.server.events.TMDBMetadata;

import app.ister.server.entitiy.EpisodeEntity;
import app.ister.server.entitiy.MetadataEntity;
import app.ister.server.entitiy.ShowEntity;
import app.ister.server.repository.MetadataRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;

import static app.ister.server.events.MessageQueue.APP_ISTER_SERVER_EPISODE_FOUND;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class MetadataSaveTest {
    @Mock
    private MetadataRepository metadataRepositoryMock;

    @InjectMocks
    private MetadataSave subject;

    @Test
    void save() {
        TMDBResult tmdbResult = TMDBResult.builder().
                language("NL")
                .title("TITLE")
                .released(LocalDate.EPOCH)
                .sourceUri("URI")
                .description("DESCRIPTION")
                .build();
        ShowEntity showEntity = ShowEntity.builder().build();
        EpisodeEntity episodeEntity = EpisodeEntity.builder().build();
        MetadataEntity build = MetadataEntity.builder()
                .showEntity(showEntity)
                .episodeEntity(episodeEntity)
                .language("NL")
                .title("TITLE")
                .released(LocalDate.EPOCH)
                .sourceUri("URI")
                .description("DESCRIPTION")
                .build();
        subject.save(tmdbResult, showEntity, episodeEntity);
        verify(metadataRepositoryMock).save(build);
    }
}