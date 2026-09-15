package app.ister.core.service;

import app.ister.core.entity.AlbumEntity;
import app.ister.core.entity.LibraryEntity;
import app.ister.core.entity.MetadataEntity;
import app.ister.core.entity.PersonEntity;
import app.ister.core.entity.PlayQueueItemEntity;
import app.ister.core.entity.RatingEntity;
import app.ister.core.entity.TrackEntity;
import app.ister.core.entity.UserEntity;
import app.ister.core.entity.WatchStatusEntity;
import app.ister.core.enums.LibraryType;
import app.ister.core.enums.SearchEntityType;
import app.ister.core.repository.AlbumRepository;
import app.ister.core.repository.MetadataRepository;
import app.ister.core.repository.PlayQueueItemRepository;
import app.ister.core.repository.PlaylistItemRepository;
import app.ister.core.repository.RatingRepository;
import app.ister.core.repository.TrackRepository;
import app.ister.core.repository.WatchStatusRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OrphanTrackCleanupServiceTest {
    @InjectMocks
    private OrphanTrackCleanupService subject;
    @Mock
    private TrackRepository trackRepository;
    @Mock
    private AlbumRepository albumRepository;
    @Mock
    private MetadataRepository metadataRepository;
    @Mock
    private WatchStatusRepository watchStatusRepository;
    @Mock
    private PlayQueueItemRepository playQueueItemRepository;
    @Mock
    private PlaylistItemRepository playlistItemRepository;
    @Mock
    private RatingRepository ratingRepository;
    @Mock
    private ServerEventService serverEventService;

    private final LibraryEntity library = LibraryEntity.builder().id(UUID.randomUUID()).libraryType(LibraryType.MUSIC).build();
    private final PersonEntity artist = PersonEntity.builder().id(UUID.randomUUID()).name("Miracle Of Sound").build();
    private final UserEntity user = UserEntity.builder().id(UUID.randomUUID()).build();

    @Test
    void nonMusicLibraryIsLeftAlone() {
        LibraryEntity shows = LibraryEntity.builder().id(UUID.randomUUID()).libraryType(LibraryType.SHOW).build();
        assertEquals(0, subject.cleanUp(shows));
        verifyNoInteractions(trackRepository, albumRepository);
    }

    @Test
    void historyMovesToTheTrackThatStillHasAFile() {
        TrackEntity orphan = TrackEntity.builder().id(UUID.randomUUID()).personEntity(artist).build();
        TrackEntity successor = TrackEntity.builder().id(UUID.randomUUID()).personEntity(artist).build();
        WatchStatusEntity status = WatchStatusEntity.builder().userEntity(user).playQueueItemId(UUID.randomUUID()).trackEntity(orphan).build();
        RatingEntity rating = RatingEntity.builder().userEntity(user).trackEntity(orphan).value(4).build();
        PlayQueueItemEntity queued = PlayQueueItemEntity.builder().trackEntityId(orphan.getId()).build();

        when(trackRepository.findOrphansInLibrary(library.getId())).thenReturn(List.of(orphan));
        when(metadataRepository.findByTrackEntityId(orphan.getId()))
                .thenReturn(List.of(MetadataEntity.builder().title("Kickback").build()));
        when(trackRepository.findReplacementCandidates(artist, "Kickback", orphan.getId())).thenReturn(List.of(successor));
        when(watchStatusRepository.findByTrackEntity(orphan)).thenReturn(List.of(status));
        when(watchStatusRepository.findByUserEntityAndPlayQueueItemIdAndTrackEntity(user, status.getPlayQueueItemId(), successor))
                .thenReturn(Optional.empty());
        when(ratingRepository.findByTrackEntity(orphan)).thenReturn(List.of(rating));
        when(ratingRepository.findByUserEntityAndTrackEntity(user, successor)).thenReturn(Optional.empty());
        when(playQueueItemRepository.findByTrackEntityId(orphan.getId())).thenReturn(List.of(queued));
        when(albumRepository.findEmptyInLibrary(library.getId())).thenReturn(List.of());

        assertEquals(1, subject.cleanUp(library));

        assertEquals(successor, status.getTrackEntity());
        verify(watchStatusRepository).save(status);
        assertEquals(successor, rating.getTrackEntity());
        verify(ratingRepository).save(rating);
        assertEquals(successor.getId(), queued.getTrackEntityId());
        verify(playQueueItemRepository).save(queued);
        verify(trackRepository).delete(orphan);
        verify(serverEventService).createSearchDeleteEvent(SearchEntityType.TRACK, orphan.getId());
    }

    @Test
    void historyIsDroppedWhenTheSuccessorAlreadyHasItOrThereIsNone() {
        TrackEntity orphan = TrackEntity.builder().id(UUID.randomUUID()).personEntity(artist).build();
        TrackEntity successor = TrackEntity.builder().id(UUID.randomUUID()).personEntity(artist).build();
        WatchStatusEntity status = WatchStatusEntity.builder().userEntity(user).playQueueItemId(UUID.randomUUID()).trackEntity(orphan).build();
        TrackEntity loner = TrackEntity.builder().id(UUID.randomUUID()).personEntity(artist).build();
        PlayQueueItemEntity queued = PlayQueueItemEntity.builder().trackEntityId(loner.getId()).build();

        when(trackRepository.findOrphansInLibrary(library.getId())).thenReturn(List.of(orphan, loner));
        when(metadataRepository.findByTrackEntityId(orphan.getId()))
                .thenReturn(List.of(MetadataEntity.builder().title("Kickback").build()));
        when(trackRepository.findReplacementCandidates(artist, "Kickback", orphan.getId())).thenReturn(List.of(successor));
        when(watchStatusRepository.findByTrackEntity(orphan)).thenReturn(List.of(status));
        when(watchStatusRepository.findByUserEntityAndPlayQueueItemIdAndTrackEntity(user, status.getPlayQueueItemId(), successor))
                .thenReturn(Optional.of(WatchStatusEntity.builder().build()));
        when(playQueueItemRepository.findByTrackEntityId(orphan.getId())).thenReturn(List.of());
        when(metadataRepository.findByTrackEntityId(loner.getId())).thenReturn(List.of());
        when(playQueueItemRepository.findByTrackEntityId(loner.getId())).thenReturn(List.of(queued));
        when(albumRepository.findEmptyInLibrary(library.getId())).thenReturn(List.of());

        assertEquals(2, subject.cleanUp(library));

        verify(watchStatusRepository).delete(status);
        verify(watchStatusRepository, never()).save(any());
        verify(playQueueItemRepository).delete(queued);
        verify(trackRepository).delete(orphan);
        verify(trackRepository).delete(loner);
    }

    @Test
    void emptyAlbumsAreDeletedWithTheirRatingsAndSearchDocuments() {
        AlbumEntity album = AlbumEntity.builder().id(UUID.randomUUID()).name("Digital Shadow 2014").build();
        RatingEntity rating = RatingEntity.builder().userEntity(user).albumEntity(album).value(5).build();
        when(trackRepository.findOrphansInLibrary(library.getId())).thenReturn(List.of());
        when(albumRepository.findEmptyInLibrary(library.getId())).thenReturn(List.of(album));
        when(ratingRepository.findByAlbumEntity(album)).thenReturn(List.of(rating));

        subject.cleanUp(library);

        verify(ratingRepository).deleteAll(List.of(rating));
        verify(albumRepository).delete(album);
        verify(serverEventService).createSearchDeleteEvent(SearchEntityType.ALBUM, album.getId());
    }
}
