package app.ister.api.controller;

import app.ister.api.controller.MetadataRefreshController.MetadataRefreshMode;
import app.ister.core.entity.DirectoryEntity;
import app.ister.core.enums.DirectoryType;
import app.ister.core.enums.EventType;
import app.ister.core.eventdata.AnalyzeData;
import app.ister.core.eventdata.MetadataBackfillRequestedData;
import app.ister.core.repository.DirectoryRepository;
import app.ister.core.service.MessageSender;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MetadataRefreshControllerTest {

    @InjectMocks
    private MetadataRefreshController subject;

    @Mock
    private MessageSender messageSender;

    @Mock
    private DirectoryRepository directoryRepository;

    @Test
    void refreshMetadataMissingSendsOneGlobalBackfillAndABlurhashSweepPerDirectory() {
        DirectoryEntity library = DirectoryEntity.builder()
                .id(UUID.randomUUID()).name("movies").directoryType(DirectoryType.LIBRARY).build();
        DirectoryEntity cache = DirectoryEntity.builder()
                .id(UUID.randomUUID()).name("node-cache").directoryType(DirectoryType.CACHE).build();
        when(directoryRepository.findAll()).thenReturn(List.of(library, cache));

        Boolean result = subject.refreshMetadata(MetadataRefreshMode.MISSING, null);

        assertTrue(result);
        // The cache directory holds the downloaded artwork — by far the most images — so it must
        // get a sweep of its own, not just the library directories.
        verify(messageSender).sendUpdateImagesRequested(any(), eq("movies"));
        verify(messageSender).sendUpdateImagesRequested(any(), eq("node-cache"));
        ArgumentCaptor<MetadataBackfillRequestedData> captor =
                ArgumentCaptor.forClass(MetadataBackfillRequestedData.class);
        verify(messageSender, times(1)).sendMetadataBackfillRequested(captor.capture());
        assertEquals(EventType.METADATA_BACKFILL_REQUESTED, captor.getValue().getEventType());
    }

    @Test
    void refreshMetadataMissingWithLibraryScopesSweepAndBackfill() {
        UUID libraryId = UUID.randomUUID();
        DirectoryEntity dir = DirectoryEntity.builder()
                .id(UUID.randomUUID()).name("movies").directoryType(DirectoryType.LIBRARY).build();
        when(directoryRepository.findByDirectoryTypeAndLibraryEntityId(DirectoryType.LIBRARY, libraryId))
                .thenReturn(List.of(dir));

        Boolean result = subject.refreshMetadata(MetadataRefreshMode.MISSING, libraryId);

        assertTrue(result);
        verify(messageSender).sendUpdateImagesRequested(any(), eq("movies"));
        ArgumentCaptor<MetadataBackfillRequestedData> captor =
                ArgumentCaptor.forClass(MetadataBackfillRequestedData.class);
        verify(messageSender).sendMetadataBackfillRequested(captor.capture());
        assertEquals(libraryId, captor.getValue().getLibraryId());
    }

    @Test
    void refreshMetadataForceSendsTheDestructiveAnalyzeDataFlow() {
        UUID libraryId = UUID.randomUUID();

        Boolean result = subject.refreshMetadata(MetadataRefreshMode.FORCE, libraryId);

        assertTrue(result);
        ArgumentCaptor<AnalyzeData> captor = ArgumentCaptor.forClass(AnalyzeData.class);
        verify(messageSender).sendAnalyzeData(captor.capture());
        assertEquals(libraryId, captor.getValue().getLibraryId());
        verify(messageSender, never()).sendMetadataBackfillRequested(any());
        verify(messageSender, never()).sendUpdateImagesRequested(any(), any());
    }

    @Test
    void refreshMetadataForceWithoutLibraryIsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> subject.refreshMetadata(MetadataRefreshMode.FORCE, null));
        verify(messageSender, never()).sendAnalyzeData(any());
    }

    @Test
    void refreshEpisodeSendsCorrectMessage() {
        UUID episodeId = UUID.randomUUID();

        Boolean result = subject.refreshEpisode(episodeId);

        assertTrue(result);
        ArgumentCaptor<AnalyzeData> captor = ArgumentCaptor.forClass(AnalyzeData.class);
        verify(messageSender).sendAnalyzeData(captor.capture());
        assertEquals(EventType.ANALYZE_DATA, captor.getValue().getEventType());
        assertEquals(episodeId, captor.getValue().getEpisodeId());
    }

    @Test
    void refreshMovieSendsCorrectMessage() {
        UUID movieId = UUID.randomUUID();

        Boolean result = subject.refreshMovie(movieId);

        assertTrue(result);
        ArgumentCaptor<AnalyzeData> captor = ArgumentCaptor.forClass(AnalyzeData.class);
        verify(messageSender).sendAnalyzeData(captor.capture());
        assertEquals(EventType.ANALYZE_DATA, captor.getValue().getEventType());
        assertEquals(movieId, captor.getValue().getMovieId());
    }

    @Test
    void refreshShowSendsCorrectMessage() {
        UUID showId = UUID.randomUUID();

        Boolean result = subject.refreshShow(showId);

        assertTrue(result);
        ArgumentCaptor<AnalyzeData> captor = ArgumentCaptor.forClass(AnalyzeData.class);
        verify(messageSender).sendAnalyzeData(captor.capture());
        assertEquals(EventType.ANALYZE_DATA, captor.getValue().getEventType());
        assertEquals(showId, captor.getValue().getShowId());
    }

    @Test
    void refreshPersonSendsCorrectMessage() {
        UUID personId = UUID.randomUUID();

        Boolean result = subject.refreshPerson(personId);

        assertTrue(result);
        ArgumentCaptor<AnalyzeData> captor = ArgumentCaptor.forClass(AnalyzeData.class);
        verify(messageSender).sendAnalyzeData(captor.capture());
        assertEquals(EventType.ANALYZE_DATA, captor.getValue().getEventType());
        assertEquals(personId, captor.getValue().getPersonId());
    }

    @Test
    void refreshAlbumSendsCorrectMessage() {
        UUID albumId = UUID.randomUUID();

        Boolean result = subject.refreshAlbum(albumId);

        assertTrue(result);
        ArgumentCaptor<AnalyzeData> captor = ArgumentCaptor.forClass(AnalyzeData.class);
        verify(messageSender).sendAnalyzeData(captor.capture());
        assertEquals(EventType.ANALYZE_DATA, captor.getValue().getEventType());
        assertEquals(albumId, captor.getValue().getAlbumId());
    }

    @Test
    void refreshTrackSendsCorrectMessage() {
        UUID trackId = UUID.randomUUID();

        Boolean result = subject.refreshTrack(trackId);

        assertTrue(result);
        ArgumentCaptor<AnalyzeData> captor = ArgumentCaptor.forClass(AnalyzeData.class);
        verify(messageSender).sendAnalyzeData(captor.capture());
        assertEquals(EventType.ANALYZE_DATA, captor.getValue().getEventType());
        assertEquals(trackId, captor.getValue().getTrackId());
    }
}
