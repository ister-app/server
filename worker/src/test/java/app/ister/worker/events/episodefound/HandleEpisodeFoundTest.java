package app.ister.worker.events.episodefound;

import app.ister.core.entity.EpisodeEntity;
import app.ister.core.entity.SeasonEntity;
import app.ister.core.entity.ShowEntity;
import app.ister.core.enums.EventType;
import app.ister.core.enums.ImageType;
import app.ister.core.eventdata.EpisodeFoundData;
import app.ister.core.repository.EpisodeRepository;
import app.ister.worker.events.tmdbmetadata.EpisodeMetadata;
import app.ister.worker.events.tmdbmetadata.ImageDownloadService;
import app.ister.worker.events.tmdbmetadata.MetadataSave;
import app.ister.worker.events.tmdbmetadata.TMDBResult;
import feign.FeignException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class HandleEpisodeFoundTest {

    @InjectMocks
    private HandleEpisodeFound subject;

    @Mock
    private EpisodeRepository episodeRepository;

    @Mock
    private EpisodeMetadata episodeMetadata;

    @Mock
    private MetadataSave metaDataSave;

    @Mock
    private ImageDownloadService imageDownloadService;

    @Test
    void handles() {
        assertEquals(EventType.EPISODE_FOUND, subject.handles());
    }

    @Test
    void listenerThrowsOnWrongEventType() {
        EpisodeFoundData data = EpisodeFoundData.builder()
                .eventType(EventType.FILE_SCAN_REQUESTED)
                .build();
        assertThrows(IllegalArgumentException.class, () -> subject.listener(data));
    }

    @Test
    void handleReturnsTrueImmediatelyWhenNoApiKey() {
        ReflectionTestUtils.setField(subject, "apikey", "");
        EpisodeFoundData data = EpisodeFoundData.builder()
                .eventType(EventType.EPISODE_FOUND)
                .build();

        assertTrue(subject.handle(data));

        verifyNoInteractions(episodeRepository, episodeMetadata, metaDataSave, imageDownloadService);
    }

    @Test
    void handleWithResultHavingBackgroundUrl() throws IOException {
        ReflectionTestUtils.setField(subject, "apikey", "test-key");
        UUID episodeId = UUID.randomUUID();
        ShowEntity show = ShowEntity.builder().name("Show").releaseYear(2024).build();
        SeasonEntity season = SeasonEntity.builder().number(1).build();
        EpisodeEntity episodeEntity = EpisodeEntity.builder()
                .id(episodeId).number(1).showEntity(show).seasonEntity(season).build();
        EpisodeFoundData data = EpisodeFoundData.builder()
                .eventType(EventType.EPISODE_FOUND)
                .episodeId(episodeId)
                .build();
        TMDBResult result = TMDBResult.builder()
                .language("eng")
                .title("Episode 1")
                .backgroundUrl("https://example.com/still.jpg")
                .build();

        when(episodeRepository.findById(episodeId)).thenReturn(Optional.of(episodeEntity));
        when(episodeMetadata.getMetadata(eq("Show"), eq(2024), eq(1), eq(1), anyString()))
                .thenReturn(Optional.of(result));

        assertTrue(subject.handle(data));

        verify(metaDataSave, times(2)).save(result, null, null, episodeEntity);
        verify(imageDownloadService, times(2)).downloadAndSave(
                eq("https://example.com/still.jpg"), eq(ImageType.BACKGROUND), eq("eng"), isNull(), isNull(), eq(episodeEntity));
    }

    @Test
    void handleWithNoTmdbResult() {
        ReflectionTestUtils.setField(subject, "apikey", "test-key");
        UUID episodeId = UUID.randomUUID();
        ShowEntity show = ShowEntity.builder().name("Show").releaseYear(2024).build();
        SeasonEntity season = SeasonEntity.builder().number(1).build();
        EpisodeEntity episodeEntity = EpisodeEntity.builder()
                .id(episodeId).number(1).showEntity(show).seasonEntity(season).build();
        EpisodeFoundData data = EpisodeFoundData.builder()
                .eventType(EventType.EPISODE_FOUND)
                .episodeId(episodeId)
                .build();

        when(episodeRepository.findById(episodeId)).thenReturn(Optional.of(episodeEntity));
        when(episodeMetadata.getMetadata(anyString(), anyInt(), anyInt(), anyInt(), anyString()))
                .thenReturn(Optional.empty());

        assertTrue(subject.handle(data));

        verifyNoInteractions(metaDataSave, imageDownloadService);
    }

    @Test
    void handleWithResultHavingPosterUrl() throws IOException {
        ReflectionTestUtils.setField(subject, "apikey", "test-key");
        UUID episodeId = UUID.randomUUID();
        ShowEntity show = ShowEntity.builder().name("Show").releaseYear(2024).build();
        SeasonEntity season = SeasonEntity.builder().number(1).build();
        EpisodeEntity episodeEntity = EpisodeEntity.builder()
                .id(episodeId).number(1).showEntity(show).seasonEntity(season).build();
        EpisodeFoundData data = EpisodeFoundData.builder()
                .eventType(EventType.EPISODE_FOUND)
                .episodeId(episodeId)
                .build();
        TMDBResult result = TMDBResult.builder()
                .language("eng")
                .title("Episode 1")
                .posterUrl("https://example.com/poster.jpg")
                .build();

        when(episodeRepository.findById(episodeId)).thenReturn(Optional.of(episodeEntity));
        when(episodeMetadata.getMetadata(eq("Show"), eq(2024), eq(1), eq(1), anyString()))
                .thenReturn(Optional.of(result));

        assertTrue(subject.handle(data));

        verify(imageDownloadService, times(2)).downloadAndSave(
                eq("https://example.com/poster.jpg"), eq(ImageType.COVER), eq("eng"), isNull(), isNull(), eq(episodeEntity));
    }

    @Test
    void handleReturnsFalseOnFeignException() throws Exception {
        ReflectionTestUtils.setField(subject, "apikey", "test-key");
        UUID episodeId = UUID.randomUUID();
        ShowEntity show = ShowEntity.builder().name("Show").releaseYear(2024).build();
        SeasonEntity season = SeasonEntity.builder().number(1).build();
        EpisodeEntity episodeEntity = EpisodeEntity.builder()
                .id(episodeId).number(1).showEntity(show).seasonEntity(season).build();
        EpisodeFoundData data = EpisodeFoundData.builder()
                .eventType(EventType.EPISODE_FOUND)
                .episodeId(episodeId)
                .build();

        when(episodeRepository.findById(episodeId)).thenReturn(Optional.of(episodeEntity));
        when(episodeMetadata.getMetadata(anyString(), anyInt(), anyInt(), anyInt(), anyString()))
                .thenThrow(mock(FeignException.class));

        assertFalse(subject.handle(data));
    }

    @Test
    void handleReturnsFalseOnIOException() throws IOException {
        ReflectionTestUtils.setField(subject, "apikey", "test-key");
        UUID episodeId = UUID.randomUUID();
        ShowEntity show = ShowEntity.builder().name("Show").releaseYear(2024).build();
        SeasonEntity season = SeasonEntity.builder().number(1).build();
        EpisodeEntity episodeEntity = EpisodeEntity.builder()
                .id(episodeId).number(1).showEntity(show).seasonEntity(season).build();
        EpisodeFoundData data = EpisodeFoundData.builder()
                .eventType(EventType.EPISODE_FOUND)
                .episodeId(episodeId)
                .build();
        TMDBResult result = TMDBResult.builder()
                .language("eng")
                .backgroundUrl("https://example.com/still.jpg")
                .build();

        when(episodeRepository.findById(episodeId)).thenReturn(Optional.of(episodeEntity));
        when(episodeMetadata.getMetadata(anyString(), anyInt(), anyInt(), anyInt(), anyString()))
                .thenReturn(Optional.of(result));
        doThrow(new IOException("download failed"))
                .when(imageDownloadService).downloadAndSave(anyString(), any(), anyString(), any(), any(), any());

        assertFalse(subject.handle(data));
    }

    @Test
    void listenerCallsHandleWithCorrectEventType() {
        ReflectionTestUtils.setField(subject, "apikey", "");
        EpisodeFoundData data = EpisodeFoundData.builder()
                .eventType(EventType.EPISODE_FOUND)
                .build();
        assertDoesNotThrow(() -> subject.listener(data));
    }
}
