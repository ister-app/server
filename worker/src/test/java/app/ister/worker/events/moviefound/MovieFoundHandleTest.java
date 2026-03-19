package app.ister.worker.events.moviefound;

import app.ister.core.entity.MovieEntity;
import app.ister.core.enums.EventType;
import app.ister.core.enums.ImageType;
import app.ister.core.eventdata.MovieFoundData;
import app.ister.core.repository.MovieRepository;
import app.ister.worker.events.tmdbmetadata.ImageDownloadService;
import app.ister.worker.events.tmdbmetadata.MetadataSave;
import app.ister.worker.events.tmdbmetadata.MovieMetadata;
import app.ister.worker.events.tmdbmetadata.TMDBResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
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

    @Test
    void handleWithResultHavingUrls() throws IOException {
        ReflectionTestUtils.setField(subject, "apikey", "test-key");
        UUID movieId = UUID.randomUUID();
        MovieEntity movieEntity = MovieEntity.builder().id(movieId).name("Movie").releaseYear(2024).build();
        MovieFoundData data = MovieFoundData.builder()
                .eventType(EventType.MOVIE_FOUND)
                .movieId(movieId)
                .build();
        TMDBResult result = TMDBResult.builder()
                .language("eng")
                .title("Movie")
                .backgroundUrl("https://example.com/bg.jpg")
                .posterUrl("https://example.com/poster.jpg")
                .build();

        when(movieRepository.findById(movieId)).thenReturn(Optional.of(movieEntity));
        when(movieMetadata.getMetadata(eq("Movie"), eq(2024), anyString())).thenReturn(Optional.of(result));

        assertTrue(subject.handle(data));

        verify(metaDataSave, times(2)).save(result, movieEntity, null, null);
        verify(imageDownloadService, times(2)).downloadAndSave(
                eq("https://example.com/bg.jpg"), eq(ImageType.BACKGROUND), eq("eng"), eq(movieEntity), isNull(), isNull());
        verify(imageDownloadService, times(2)).downloadAndSave(
                eq("https://example.com/poster.jpg"), eq(ImageType.COVER), eq("eng"), eq(movieEntity), isNull(), isNull());
    }

    @Test
    void handleWithResultHavingNoUrls() throws IOException {
        ReflectionTestUtils.setField(subject, "apikey", "test-key");
        UUID movieId = UUID.randomUUID();
        MovieEntity movieEntity = MovieEntity.builder().id(movieId).name("Movie").releaseYear(2024).build();
        MovieFoundData data = MovieFoundData.builder()
                .eventType(EventType.MOVIE_FOUND)
                .movieId(movieId)
                .build();
        TMDBResult result = TMDBResult.builder()
                .language("eng")
                .title("Movie")
                .build();

        when(movieRepository.findById(movieId)).thenReturn(Optional.of(movieEntity));
        when(movieMetadata.getMetadata(eq("Movie"), eq(2024), anyString())).thenReturn(Optional.of(result));

        assertTrue(subject.handle(data));

        verify(metaDataSave, times(2)).save(result, movieEntity, null, null);
        verifyNoInteractions(imageDownloadService);
    }

    @Test
    void handleWithNoTmdbResult() {
        ReflectionTestUtils.setField(subject, "apikey", "test-key");
        UUID movieId = UUID.randomUUID();
        MovieEntity movieEntity = MovieEntity.builder().id(movieId).name("Movie").releaseYear(2024).build();
        MovieFoundData data = MovieFoundData.builder()
                .eventType(EventType.MOVIE_FOUND)
                .movieId(movieId)
                .build();

        when(movieRepository.findById(movieId)).thenReturn(Optional.of(movieEntity));
        when(movieMetadata.getMetadata(anyString(), anyInt(), anyString())).thenReturn(Optional.empty());

        assertTrue(subject.handle(data));

        verifyNoInteractions(metaDataSave, imageDownloadService);
    }
}
