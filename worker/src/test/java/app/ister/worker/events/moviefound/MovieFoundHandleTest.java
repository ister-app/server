package app.ister.worker.events.moviefound;

import app.ister.core.EventHandlingException;
import app.ister.core.config.LanguageProperties;
import app.ister.core.entity.MovieEntity;
import app.ister.core.enums.EventType;
import app.ister.core.enums.ImageType;
import app.ister.core.eventdata.MovieFoundData;
import app.ister.core.repository.MovieRepository;
import app.ister.worker.events.tmdbmetadata.CreditsService;
import app.ister.worker.events.tmdbmetadata.ImageDownloadService;
import app.ister.worker.events.tmdbmetadata.ImageSave;
import app.ister.worker.events.tmdbmetadata.MetadataSave;
import app.ister.worker.events.tmdbmetadata.MovieMetadata;
import app.ister.worker.events.tmdbmetadata.TMDBResult;
import app.ister.worker.events.tmdbmetadata.TmdbExtrasService;
import feign.FeignException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
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

    @Mock
    private CreditsService creditsService;

    @Mock
    private TmdbExtrasService tmdbExtrasService;

    @Spy
    private LanguageProperties languageProperties = new LanguageProperties();

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
    void handleSkipsImmediatelyWhenNoApiKey() {
        ReflectionTestUtils.setField(subject, "apikey", "");
        MovieFoundData data = MovieFoundData.builder()
                .eventType(EventType.MOVIE_FOUND)
                .build();

        assertDoesNotThrow(() -> subject.handle(data));

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

        subject.handle(data);

        verify(metaDataSave, times(2)).save(result, movieEntity, null, null);
        verify(imageDownloadService, times(2)).downloadAndSave(
                "https://example.com/bg.jpg", ImageType.BACKGROUND, "eng",
                "TMDB://https://example.com/bg.jpg", new ImageSave.MediaEntityRef(movieEntity, null, null, null, null));
        verify(imageDownloadService, times(2)).downloadAndSave(
                "https://example.com/poster.jpg", ImageType.COVER, "eng",
                "TMDB://https://example.com/poster.jpg", new ImageSave.MediaEntityRef(movieEntity, null, null, null, null));
    }

    @Test
    void handleWithResultHavingNoUrls() {
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

        subject.handle(data);

        verify(metaDataSave, times(2)).save(result, movieEntity, null, null);
        verifyNoInteractions(imageDownloadService);
    }

    @Test
    void handleAppliesEnrichmentOnceAndFetchesExtras() {
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
                .tmdbId(99)
                .imdbId("tt1234567")
                .voteAverage(new BigDecimal("7.8"))
                .voteCount(1234)
                .runtime(138)
                .status("Released")
                .homepage("https://example.com")
                .collectionTmdbId(10)
                .collectionName("The Movie Collection")
                .studios("Warner Bros. Pictures")
                .originCountry("US")
                .build();

        when(movieRepository.findById(movieId)).thenReturn(Optional.of(movieEntity));
        when(movieMetadata.getMetadata(eq("Movie"), eq(2024), anyString())).thenReturn(Optional.of(result));

        subject.handle(data);

        assertEquals(99, movieEntity.getTmdbId());
        assertEquals("tt1234567", movieEntity.getImdbId());
        assertEquals(new BigDecimal("7.8"), movieEntity.getVoteAverage());
        assertEquals(1234, movieEntity.getVoteCount());
        assertEquals(138, movieEntity.getRuntime());
        assertEquals("Released", movieEntity.getStatus());
        assertEquals("https://example.com", movieEntity.getHomepage());
        assertEquals(10, movieEntity.getCollectionTmdbId());
        assertEquals("The Movie Collection", movieEntity.getCollectionName());
        assertEquals("Warner Bros. Pictures", movieEntity.getStudios());
        assertEquals("US", movieEntity.getOriginCountry());
        verify(creditsService).fetchForMovie(movieEntity, 99);
        verify(tmdbExtrasService).fetchForMovie(movieEntity, 99);
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

        subject.handle(data);

        verifyNoInteractions(metaDataSave, imageDownloadService);
    }

    @Test
    void handleThrowsOnFeignException() {
        ReflectionTestUtils.setField(subject, "apikey", "test-key");
        UUID movieId = UUID.randomUUID();
        MovieEntity movieEntity = MovieEntity.builder().id(movieId).name("Movie").releaseYear(2024).build();
        MovieFoundData data = MovieFoundData.builder()
                .eventType(EventType.MOVIE_FOUND)
                .movieId(movieId)
                .build();

        when(movieRepository.findById(movieId)).thenReturn(Optional.of(movieEntity));
        when(movieMetadata.getMetadata(anyString(), anyInt(), anyString())).thenThrow(mock(FeignException.class));

        assertThrows(FeignException.class, () -> subject.handle(data));
    }

    @Test
    void handleThrowsOnIOException() throws IOException {
        ReflectionTestUtils.setField(subject, "apikey", "test-key");
        UUID movieId = UUID.randomUUID();
        MovieEntity movieEntity = MovieEntity.builder().id(movieId).name("Movie").releaseYear(2024).build();
        MovieFoundData data = MovieFoundData.builder()
                .eventType(EventType.MOVIE_FOUND)
                .movieId(movieId)
                .build();
        TMDBResult result = TMDBResult.builder()
                .language("eng")
                .backgroundUrl("https://example.com/bg.jpg")
                .build();

        when(movieRepository.findById(movieId)).thenReturn(Optional.of(movieEntity));
        when(movieMetadata.getMetadata(anyString(), anyInt(), anyString())).thenReturn(Optional.of(result));
        doThrow(new IOException("download failed"))
                .when(imageDownloadService).downloadAndSave(anyString(), any(), anyString(), anyString(), any());

        assertThrows(EventHandlingException.class, () -> subject.handle(data));
    }

    @Test
    void listenerCallsHandleWithCorrectEventType() {
        ReflectionTestUtils.setField(subject, "apikey", "");
        MovieFoundData data = MovieFoundData.builder()
                .eventType(EventType.MOVIE_FOUND)
                .build();
        assertDoesNotThrow(() -> subject.listener(data));
    }
}
