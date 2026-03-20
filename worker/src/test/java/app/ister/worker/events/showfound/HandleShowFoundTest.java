package app.ister.worker.events.showfound;

import app.ister.core.entity.ShowEntity;
import app.ister.core.enums.EventType;
import app.ister.core.enums.ImageType;
import app.ister.core.eventdata.ShowFoundData;
import app.ister.core.repository.ShowRepository;
import app.ister.worker.events.tmdbmetadata.ImageDownloadService;
import app.ister.worker.events.tmdbmetadata.MetadataSave;
import app.ister.worker.events.tmdbmetadata.ShowMetadata;
import app.ister.worker.events.tmdbmetadata.TMDBResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import feign.FeignException;

import java.io.IOException;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class HandleShowFoundTest {

    @InjectMocks
    private HandleShowFound subject;

    @Mock
    private ShowRepository showRepository;

    @Mock
    private ShowMetadata showMetadata;

    @Mock
    private MetadataSave metaDataSave;

    @Mock
    private ImageDownloadService imageDownloadService;

    @Test
    void handles() {
        assertEquals(EventType.SHOW_FOUND, subject.handles());
    }

    @Test
    void listenerThrowsOnWrongEventType() {
        ShowFoundData data = ShowFoundData.builder()
                .eventType(EventType.FILE_SCAN_REQUESTED)
                .build();
        assertThrows(IllegalArgumentException.class, () -> subject.listener(data));
    }

    @Test
    void handleReturnsTrueImmediatelyWhenNoApiKey() {
        ReflectionTestUtils.setField(subject, "apikey", "");
        ShowFoundData data = ShowFoundData.builder()
                .eventType(EventType.SHOW_FOUND)
                .build();

        assertTrue(subject.handle(data));

        verifyNoInteractions(showRepository, showMetadata, metaDataSave, imageDownloadService);
    }

    @Test
    void handleWithResultHavingUrls() throws IOException {
        ReflectionTestUtils.setField(subject, "apikey", "test-key");
        UUID showId = UUID.randomUUID();
        ShowEntity showEntity = ShowEntity.builder().id(showId).name("Show").releaseYear(2024).build();
        ShowFoundData data = ShowFoundData.builder()
                .eventType(EventType.SHOW_FOUND)
                .showId(showId)
                .build();
        TMDBResult result = TMDBResult.builder()
                .language("eng")
                .title("Show")
                .backgroundUrl("https://example.com/bg.jpg")
                .posterUrl("https://example.com/poster.jpg")
                .build();

        when(showRepository.findById(showId)).thenReturn(Optional.of(showEntity));
        when(showMetadata.getMetadata(eq("Show"), eq(2024), anyString())).thenReturn(Optional.of(result));

        assertTrue(subject.handle(data));

        verify(metaDataSave, times(2)).save(result, null, showEntity, null);
        verify(imageDownloadService, times(2)).downloadAndSave(
                eq("https://example.com/bg.jpg"), eq(ImageType.BACKGROUND), eq("eng"), isNull(), eq(showEntity), isNull());
        verify(imageDownloadService, times(2)).downloadAndSave(
                eq("https://example.com/poster.jpg"), eq(ImageType.COVER), eq("eng"), isNull(), eq(showEntity), isNull());
    }

    @Test
    void handleWithResultHavingNoUrls() {
        ReflectionTestUtils.setField(subject, "apikey", "test-key");
        UUID showId = UUID.randomUUID();
        ShowEntity showEntity = ShowEntity.builder().id(showId).name("Show").releaseYear(2024).build();
        ShowFoundData data = ShowFoundData.builder()
                .eventType(EventType.SHOW_FOUND)
                .showId(showId)
                .build();
        TMDBResult result = TMDBResult.builder()
                .language("eng")
                .title("Show")
                .build();

        when(showRepository.findById(showId)).thenReturn(Optional.of(showEntity));
        when(showMetadata.getMetadata(eq("Show"), eq(2024), anyString())).thenReturn(Optional.of(result));

        assertTrue(subject.handle(data));

        verify(metaDataSave, times(2)).save(result, null, showEntity, null);
        verifyNoInteractions(imageDownloadService);
    }

    @Test
    void handleWithNoTmdbResult() {
        ReflectionTestUtils.setField(subject, "apikey", "test-key");
        UUID showId = UUID.randomUUID();
        ShowEntity showEntity = ShowEntity.builder().id(showId).name("Show").releaseYear(2024).build();
        ShowFoundData data = ShowFoundData.builder()
                .eventType(EventType.SHOW_FOUND)
                .showId(showId)
                .build();

        when(showRepository.findById(showId)).thenReturn(Optional.of(showEntity));
        when(showMetadata.getMetadata(anyString(), anyInt(), anyString())).thenReturn(Optional.empty());

        assertTrue(subject.handle(data));

        verifyNoInteractions(metaDataSave, imageDownloadService);
    }

    @Test
    void handleReturnsFalseOnFeignException() {
        ReflectionTestUtils.setField(subject, "apikey", "test-key");
        UUID showId = UUID.randomUUID();
        ShowEntity showEntity = ShowEntity.builder().id(showId).name("Show").releaseYear(2024).build();
        ShowFoundData data = ShowFoundData.builder()
                .eventType(EventType.SHOW_FOUND)
                .showId(showId)
                .build();

        when(showRepository.findById(showId)).thenReturn(Optional.of(showEntity));
        when(showMetadata.getMetadata(anyString(), anyInt(), anyString())).thenThrow(mock(FeignException.class));

        assertFalse(subject.handle(data));
    }

    @Test
    void handleReturnsFalseOnIOException() throws IOException {
        ReflectionTestUtils.setField(subject, "apikey", "test-key");
        UUID showId = UUID.randomUUID();
        ShowEntity showEntity = ShowEntity.builder().id(showId).name("Show").releaseYear(2024).build();
        ShowFoundData data = ShowFoundData.builder()
                .eventType(EventType.SHOW_FOUND)
                .showId(showId)
                .build();
        TMDBResult result = TMDBResult.builder()
                .language("eng")
                .backgroundUrl("https://example.com/bg.jpg")
                .build();

        when(showRepository.findById(showId)).thenReturn(Optional.of(showEntity));
        when(showMetadata.getMetadata(anyString(), anyInt(), anyString())).thenReturn(Optional.of(result));
        doThrow(new IOException("download failed"))
                .when(imageDownloadService).downloadAndSave(anyString(), any(), anyString(), any(), any(), any());

        assertFalse(subject.handle(data));
    }

    @Test
    void listenerCallsHandleWithCorrectEventType() {
        ReflectionTestUtils.setField(subject, "apikey", "");
        ShowFoundData data = ShowFoundData.builder()
                .eventType(EventType.SHOW_FOUND)
                .build();
        subject.listener(data); // should not throw
    }
}
