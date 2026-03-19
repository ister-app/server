package app.ister.worker.events.moviefound;

import app.ister.core.enums.EventType;
import app.ister.core.eventdata.MovieFoundData;
import app.ister.core.repository.MovieRepository;
import app.ister.worker.events.TMDBMetadata.ImageDownloadService;
import app.ister.worker.events.TMDBMetadata.MetadataSave;
import app.ister.worker.events.TMDBMetadata.MovieMetadata;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class MovieFoundHandleTest {

    @InjectMocks
    private MovieFoundHandle subject;

    @Mock
    private MovieRepository movieRepository;

    @Mock
    private MovieMetadata movieMetadata;

    @Mock
    private MetadataSave metaDataSave;

    @Mock
    private ImageDownloadService imageDownloadService;

    @Test
    void handles() {
        assertEquals(EventType.MOVIE_FOUND, subject.handles());
    }

    @Test
    void listenerThrowsOnWrongEventType() {
        MovieFoundData data = MovieFoundData.builder()
                .eventType(EventType.FILE_SCAN_REQUESTED)
                .build();
        assertThrows(IllegalArgumentException.class, () -> subject.listener(data));
    }

    @Test
    void handleReturnsTrueImmediatelyWhenNoApiKey() {
        ReflectionTestUtils.setField(subject, "apikey", "");
        MovieFoundData data = MovieFoundData.builder()
                .eventType(EventType.MOVIE_FOUND)
                .build();

        assertTrue(subject.handle(data));

        verifyNoInteractions(movieRepository, movieMetadata, metaDataSave, imageDownloadService);
    }
}
