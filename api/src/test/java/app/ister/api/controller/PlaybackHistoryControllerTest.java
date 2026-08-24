package app.ister.api.controller;

import app.ister.core.entity.LibraryEntity;
import app.ister.core.entity.WatchStatusEntity;
import app.ister.core.enums.MediaType;
import app.ister.core.service.LibraryAccessService;
import app.ister.core.service.PlaybackHistoryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.Authentication;

import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PlaybackHistoryControllerTest {

    @InjectMocks
    private PlaybackHistoryController subject;

    @Mock
    private PlaybackHistoryService playbackHistoryService;

    @Mock
    private LibraryAccessService libraryAccessService;

    @Mock
    private Authentication authentication;

    private UUID mediaId;
    private LibraryEntity library;

    @BeforeEach
    void setUp() {
        mediaId = UUID.randomUUID();
        library = LibraryEntity.builder().build();
    }

    @Test
    void playbackHistoryDelegatesToTheService() {
        List<WatchStatusEntity> rows = List.of(WatchStatusEntity.builder().build());
        when(playbackHistoryService.libraryOf(MediaType.MOVIE, mediaId)).thenReturn(Optional.of(library));
        when(libraryAccessService.canAccess(library, authentication)).thenReturn(true);
        when(playbackHistoryService.history(authentication, MediaType.MOVIE, mediaId)).thenReturn(rows);

        assertEquals(rows, subject.playbackHistory(MediaType.MOVIE, mediaId, authentication));
    }

    @Test
    void anInaccessibleLibraryBehavesAsNotFound() {
        when(playbackHistoryService.libraryOf(MediaType.MOVIE, mediaId)).thenReturn(Optional.of(library));
        when(libraryAccessService.canAccess(library, authentication)).thenReturn(false);

        assertEquals(List.of(), subject.playbackHistory(MediaType.MOVIE, mediaId, authentication));
        verify(playbackHistoryService, never()).history(authentication, MediaType.MOVIE, mediaId);
    }

    @Test
    void markPlayedDelegatesToTheService() {
        WatchStatusEntity entity = WatchStatusEntity.builder().watched(true).build();
        when(playbackHistoryService.libraryOf(MediaType.TRACK, mediaId)).thenReturn(Optional.of(library));
        when(libraryAccessService.canAccess(library, authentication)).thenReturn(true);
        when(playbackHistoryService.markPlayed(authentication, MediaType.TRACK, mediaId)).thenReturn(entity);

        assertEquals(entity, subject.markPlayed(MediaType.TRACK, mediaId, authentication));
    }

    @Test
    void markPlayedOnAnInaccessibleItemThrowsNotFound() {
        when(playbackHistoryService.libraryOf(MediaType.TRACK, mediaId)).thenReturn(Optional.empty());

        assertThrows(NoSuchElementException.class,
                () -> subject.markPlayed(MediaType.TRACK, mediaId, authentication));
        verify(playbackHistoryService, never()).markPlayed(authentication, MediaType.TRACK, mediaId);
    }

    @Test
    void deleteWatchStatusDelegatesWithoutALibraryCheck() {
        UUID id = UUID.randomUUID();
        when(playbackHistoryService.deleteWatchStatus(authentication, id)).thenReturn(true);

        assertTrue(subject.deleteWatchStatus(id, authentication));
    }
}
