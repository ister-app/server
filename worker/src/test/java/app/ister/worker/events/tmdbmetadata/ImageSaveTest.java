package app.ister.worker.events.tmdbmetadata;

import app.ister.core.entity.DirectoryEntity;
import app.ister.core.entity.EpisodeEntity;
import app.ister.core.entity.MovieEntity;
import app.ister.core.entity.NodeEntity;
import app.ister.core.entity.ShowEntity;
import app.ister.core.enums.ImageType;
import app.ister.core.eventdata.ImageFoundData;
import app.ister.core.service.MessageSender;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class ImageSaveTest {

    @InjectMocks
    private ImageSave subject;

    @Mock
    private MessageSender messageSender;

    private DirectoryEntity buildCacheDisk(String nodeName) {
        NodeEntity node = NodeEntity.builder().name(nodeName).url("http://localhost").build();
        return DirectoryEntity.builder()
                .id(UUID.randomUUID())
                .nodeEntity(node)
                .path("/cache/")
                .name("cache")
                .build();
    }

    @Test
    void saveForMovie() {
        DirectoryEntity cacheDisk = buildCacheDisk("node1");
        MovieEntity movie = MovieEntity.builder().id(UUID.randomUUID()).build();

        subject.save(cacheDisk, "/cache/img.jpg", ImageType.BACKGROUND, "en", "TMDB://img.jpg", movie, null, null);

        ArgumentCaptor<ImageFoundData> captor = ArgumentCaptor.forClass(ImageFoundData.class);
        verify(messageSender).sendImageFound(captor.capture(), org.mockito.ArgumentMatchers.eq("node1"));

        ImageFoundData sent = captor.getValue();
        assertEquals(cacheDisk.getId(), sent.getDirectoryEntityId());
        assertEquals("/cache/img.jpg", sent.getPath());
        assertEquals(ImageType.BACKGROUND, sent.getImageType());
        assertEquals("en", sent.getLanguage());
        assertEquals("TMDB://img.jpg", sent.getSourceUri());
        assertEquals(movie.getId(), sent.getMovieEntityId());
        assertNull(sent.getShowEntityId());
        assertNull(sent.getEpisodeEntityId());
    }

    @Test
    void saveForShow() {
        DirectoryEntity cacheDisk = buildCacheDisk("node1");
        ShowEntity show = ShowEntity.builder().id(UUID.randomUUID()).build();

        subject.save(cacheDisk, "/cache/img.jpg", ImageType.COVER, "nl", "TMDB://img.jpg", null, show, null);

        ArgumentCaptor<ImageFoundData> captor = ArgumentCaptor.forClass(ImageFoundData.class);
        verify(messageSender).sendImageFound(captor.capture(), org.mockito.ArgumentMatchers.eq("node1"));

        ImageFoundData sent = captor.getValue();
        assertEquals(show.getId(), sent.getShowEntityId());
        assertNull(sent.getMovieEntityId());
        assertNull(sent.getEpisodeEntityId());
    }

    @Test
    void saveForEpisode() {
        DirectoryEntity cacheDisk = buildCacheDisk("node1");
        EpisodeEntity episode = EpisodeEntity.builder().id(UUID.randomUUID()).build();

        subject.save(cacheDisk, "/cache/img.jpg", ImageType.BACKGROUND, "en", "TMDB://img.jpg", null, null, episode);

        ArgumentCaptor<ImageFoundData> captor = ArgumentCaptor.forClass(ImageFoundData.class);
        verify(messageSender).sendImageFound(captor.capture(), org.mockito.ArgumentMatchers.eq("node1"));

        ImageFoundData sent = captor.getValue();
        assertEquals(episode.getId(), sent.getEpisodeEntityId());
        assertNull(sent.getMovieEntityId());
        assertNull(sent.getShowEntityId());
    }
}
