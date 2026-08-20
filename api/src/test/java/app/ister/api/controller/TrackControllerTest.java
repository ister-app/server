package app.ister.api.controller;

import app.ister.core.entity.AlbumEntity;
import app.ister.core.entity.LibraryEntity;
import app.ister.core.entity.PersonEntity;
import app.ister.core.entity.TrackCreditEntity;
import app.ister.core.entity.TrackEntity;
import app.ister.core.enums.SortingEnum;
import app.ister.core.enums.SortingOrder;
import app.ister.core.enums.TrackCreditType;
import app.ister.core.repository.LibraryRepository;
import app.ister.core.repository.PersonRepository;
import app.ister.core.repository.TrackCreditRepository;
import app.ister.core.repository.TrackRepository;
import app.ister.core.repository.WatchStatusRepository;
import app.ister.core.service.LibraryAccessService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.security.core.Authentication;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TrackControllerTest {

    @InjectMocks
    private TrackController subject;

    @Mock
    private TrackRepository trackRepository;

    @Mock
    private TrackCreditRepository trackCreditRepository;

    @Mock
    private PersonRepository personRepository;

    @Mock
    private WatchStatusRepository watchStatusRepository;

    @Mock
    private LibraryAccessService libraryAccessService;

    @Mock
    private LibraryRepository libraryRepository;

    @Mock
    private FilteredBrowse filteredBrowse;

    @Mock
    private Authentication authentication;

    private TrackController.TracksArguments args(Optional<UUID> artistId, Optional<UUID> libraryId) {
        return new TrackController.TracksArguments(Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty(), artistId, libraryId, Optional.empty());
    }

    private TrackEntity track(UUID id) {
        TrackEntity track = TrackEntity.builder().number(1).discNumber(1).build();
        ReflectionTestUtils.setField(track, "id", id);
        return track;
    }

    @Test
    void tracksWithArtistIdQueriesTheArtistPage() {
        UUID personId = UUID.randomUUID();
        UUID libraryId = UUID.randomUUID();
        TrackEntity track = track(UUID.randomUUID());
        when(filteredBrowse.page(any(), any(), any(), any(), any(), any(), any())).thenReturn(Optional.empty());
        when(libraryAccessService.allowedLibraryIds(any())).thenReturn(Optional.of(Set.of(libraryId)));
        when(trackRepository.findForPersonInLibraries(eq(personId), eq(Set.of(libraryId)),
                eq(SortingEnum.NAME), eq(SortingOrder.ASCENDING), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(track)));

        Page<TrackEntity> result = subject.tracks(args(Optional.of(personId), Optional.empty()), authentication);

        assertEquals(1, result.getContent().size());
        verify(trackRepository, never()).findInLibraries(any(), any(), any(), any());
    }

    @Test
    void tracksWithoutArtistIdBrowsesTheLibrary() {
        UUID libraryId = UUID.randomUUID();
        when(filteredBrowse.page(any(), any(), any(), any(), any(), any(), any())).thenReturn(Optional.empty());
        when(libraryAccessService.allowedLibraryIds(any())).thenReturn(Optional.of(Set.of(libraryId)));
        when(trackRepository.findInLibraries(eq(Set.of(libraryId)), any(), any(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(track(UUID.randomUUID()))));

        Page<TrackEntity> result = subject.tracks(args(Optional.empty(), Optional.empty()), authentication);

        assertEquals(1, result.getContent().size());
    }

    @Test
    void tracksWithDeniedLibraryIdReturnsEmptyWithoutQuerying() {
        UUID libraryId = UUID.randomUUID();
        when(filteredBrowse.page(any(), any(), any(), any(), any(), any(), any())).thenReturn(Optional.empty());
        when(libraryAccessService.canAccess(eq(libraryId), any())).thenReturn(false);

        Page<TrackEntity> result = subject.tracks(
                args(Optional.of(UUID.randomUUID()), Optional.of(libraryId)), authentication);

        assertTrue(result.isEmpty());
        verify(trackRepository, never()).findForPersonInLibraries(any(), any(), any(), any(), any());
    }

    @Test
    void filterTakesPrecedenceOverArtistId() {
        TrackEntity track = track(UUID.randomUUID());
        when(filteredBrowse.page(any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(Optional.of(new PageImpl<>(List.of(track))));

        Page<TrackEntity> result = subject.tracks(args(Optional.of(UUID.randomUUID()), Optional.empty()), authentication);

        assertEquals(1, result.getContent().size());
        verify(trackRepository, never()).findForPersonInLibraries(any(), any(), any(), any(), any());
    }

    @Test
    void artistsBatchMappingGroupsCreditsByTrackInPositionOrder() {
        UUID trackId = UUID.randomUUID();
        TrackEntity track = track(trackId);
        TrackCreditEntity featured = TrackCreditEntity.builder().trackEntity(track).position(1)
                .creditType(TrackCreditType.FEATURED)
                .personEntity(PersonEntity.builder().name("Sean Paul").build()).build();
        TrackCreditEntity primary = TrackCreditEntity.builder().trackEntity(track).position(0)
                .creditType(TrackCreditType.PRIMARY)
                .personEntity(PersonEntity.builder().name("Blu Cantrell").build()).build();
        when(trackCreditRepository.findByTrackEntity_IdIn(List.of(trackId))).thenReturn(List.of(featured, primary));

        Map<TrackEntity, List<TrackCreditEntity>> result = subject.artists(List.of(track));

        assertEquals(2, result.get(track).size());
        assertEquals(TrackCreditType.PRIMARY, result.get(track).get(0).getCreditType());
        assertEquals("Sean Paul", result.get(track).get(1).getPersonEntity().getName());
    }

    @Test
    void tracksFallsBackToAllLibrariesForAdmins() {
        LibraryEntity library = LibraryEntity.builder().name("Music").build();
        library.setId(UUID.randomUUID());
        when(filteredBrowse.page(any(), any(), any(), any(), any(), any(), any())).thenReturn(Optional.empty());
        when(libraryAccessService.allowedLibraryIds(any())).thenReturn(Optional.empty());
        when(libraryRepository.findAll()).thenReturn(List.of(library));
        when(trackRepository.findInLibraries(eq(List.of(library.getId())), any(), any(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(track(UUID.randomUUID()))));

        Page<TrackEntity> result = subject.tracks(args(Optional.empty(), Optional.empty()), authentication);

        assertEquals(1, result.getContent().size());
    }

    @Test
    void albumSchemaMappingReturnsAlbum() {
        AlbumEntity album = AlbumEntity.builder().name("Abbey Road").build();
        TrackEntity track = TrackEntity.builder().albumEntity(album).build();

        assertEquals(album, subject.album(track));
    }
}
