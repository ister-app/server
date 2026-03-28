package app.ister.api.controller;

import app.ister.core.enums.EventType;
import app.ister.core.eventdata.AnalyzeData;
import app.ister.core.service.MessageSender;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class AnalyzeDataControllerTest {

    @InjectMocks
    private AnalyzeDataController subject;

    @Mock
    private MessageSender messageSender;

    @Test
    void analyzeDataForEpisodeSendsCorrectMessage() {
        UUID episodeId = UUID.randomUUID();

        Boolean result = subject.analyzeDataForEpisode(episodeId);

        assertTrue(result);
        ArgumentCaptor<AnalyzeData> captor = ArgumentCaptor.forClass(AnalyzeData.class);
        verify(messageSender).sendAnalyzeData(captor.capture());
        assertEquals(EventType.ANALYZE_DATA, captor.getValue().getEventType());
        assertEquals(episodeId, captor.getValue().getEpisodeId());
    }

    @Test
    void analyzeDataForMovieSendsCorrectMessage() {
        UUID movieId = UUID.randomUUID();

        Boolean result = subject.analyzeDataForMovie(movieId);

        assertTrue(result);
        ArgumentCaptor<AnalyzeData> captor = ArgumentCaptor.forClass(AnalyzeData.class);
        verify(messageSender).sendAnalyzeData(captor.capture());
        assertEquals(EventType.ANALYZE_DATA, captor.getValue().getEventType());
        assertEquals(movieId, captor.getValue().getMovieId());
    }

    @Test
    void analyzeDataForShowSendsCorrectMessage() {
        UUID showId = UUID.randomUUID();

        Boolean result = subject.analyzeDataForShow(showId);

        assertTrue(result);
        ArgumentCaptor<AnalyzeData> captor = ArgumentCaptor.forClass(AnalyzeData.class);
        verify(messageSender).sendAnalyzeData(captor.capture());
        assertEquals(EventType.ANALYZE_DATA, captor.getValue().getEventType());
        assertEquals(showId, captor.getValue().getShowId());
    }

    @Test
    void analyzeDataForLibrarySendsCorrectMessage() {
        UUID libraryId = UUID.randomUUID();

        Boolean result = subject.analyzeDataForLibrary(libraryId);

        assertTrue(result);
        ArgumentCaptor<AnalyzeData> captor = ArgumentCaptor.forClass(AnalyzeData.class);
        verify(messageSender).sendAnalyzeData(captor.capture());
        assertEquals(EventType.ANALYZE_DATA, captor.getValue().getEventType());
        assertEquals(libraryId, captor.getValue().getLibraryId());
    }
}
