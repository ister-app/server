package app.ister.api.controller;

import app.ister.core.entity.AlbumEntity;
import app.ister.core.entity.LibraryEntity;
import app.ister.core.entity.PersonEntity;
import app.ister.core.entity.MediaFileEntity;
import app.ister.core.entity.MetadataEntity;
import app.ister.core.entity.TrackEntity;
import app.ister.core.enums.SortingEnum;
import app.ister.core.enums.SortingOrder;
import app.ister.core.repository.LibraryRepository;
import app.ister.core.repository.PersonRepository;
import app.ister.core.repository.TrackRepository;
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

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TrackControllerTest {

    @InjectMocks
    private TrackController subject;

    @Mock
    private TrackRepository trackRepository;

    @Mock
    private PersonRepository personRepository;

    @Mock
    private LibraryAccessService libraryAccessService;

    @Mock
    private LibraryRepository libraryRepository;

    @Mock
    private Authentication authentication;

    @Test
    void trackByIdReturnsFromRepository() {
        UUID id = UUID.randomUUID();
        LibraryEntity library = LibraryEntity.builder().name("L").build();
        library.setId(UUID.randomUUID());
        PersonEntity artist = PersonEntity.builder().name("The Beatles").build();
        AlbumEntity album = AlbumEntity.builder().name("Abbey Road").releaseYear(1969)
                .libraryEntity(library).build();
        TrackEntity track = TrackEntity.builder().number(1).discNumber(1)
                .personEntity(artist).albumEntity(album).build();
        when(trackRepository.findById(id)).thenReturn(Optional.of(track));
        when(libraryAccessService.canAccess(any(LibraryEntity.class), any())).thenReturn(true);

        Optional<TrackEntity> result = subject.trackById(id, authentication);

        assertTrue(result.isPresent());
        assertEquals(1, result.get().getNumber());
        assertEquals(1, result.get().getDiscNumber());
    }

    @Test
    void trackByIdReturnsEmptyWhenNotFound() {
        UUID id = UUID.randomUUID();
        when(trackRepository.findById(id)).thenReturn(Optional.empty());

        assertTrue(subject.trackById(id, authentication).isEmpty());
    }

    @Test
    void tracksWithAccessibleLibraryDefaultsToNameAscending() {
        UUID libraryId = UUID.randomUUID();
        when(libraryAccessService.canAccess(libraryId, authentication)).thenReturn(true);
        Page<TrackEntity> page = new PageImpl<>(List.of(TrackEntity.builder().number(1).discNumber(1).build()));
        when(trackRepository.findInLibraries(eq(List.of(libraryId)),
                eq(SortingEnum.NAME), eq(SortingOrder.ASCENDING), any(Pageable.class)))
                .thenReturn(page);

        Page<TrackEntity> result = subject.tracks(Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.of(libraryId), authentication);

        assertEquals(1, result.getTotalElements());
    }

    @Test
    void tracksWithInaccessibleLibraryIsEmpty() {
        UUID libraryId = UUID.randomUUID();
        when(libraryAccessService.canAccess(libraryId, authentication)).thenReturn(false);

        Page<TrackEntity> result = subject.tracks(Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.of(libraryId), authentication);

        assertTrue(result.isEmpty());
        verify(trackRepository, never()).findInLibraries(any(), any(), any(), any());
    }

    @Test
    void tracksWithoutLibraryScopesToAllowedLibraries() {
        UUID allowed = UUID.randomUUID();
        when(libraryAccessService.allowedLibraryIds(authentication)).thenReturn(Optional.of(Set.of(allowed)));
        when(trackRepository.findInLibraries(eq(Set.of(allowed)),
                eq(SortingEnum.DATE_CREATED), eq(SortingOrder.DESCENDING), any(Pageable.class)))
                .thenReturn(Page.empty());

        subject.tracks(Optional.empty(), Optional.empty(), Optional.of(SortingEnum.DATE_CREATED),
                Optional.of(SortingOrder.DESCENDING), Optional.empty(), authentication);

        verify(trackRepository).findInLibraries(eq(Set.of(allowed)),
                eq(SortingEnum.DATE_CREATED), eq(SortingOrder.DESCENDING), any(Pageable.class));
        verify(libraryRepository, never()).findAll();
    }

    @Test
    void tracksForAdminSpanAllLibraries() {
        LibraryEntity library = LibraryEntity.builder().name("Music").build();
        library.setId(UUID.randomUUID());
        when(libraryAccessService.allowedLibraryIds(authentication)).thenReturn(Optional.empty());
        when(libraryRepository.findAll()).thenReturn(List.of(library));
        when(trackRepository.findInLibraries(eq(List.of(library.getId())),
                eq(SortingEnum.NAME), eq(SortingOrder.ASCENDING), any(Pageable.class)))
                .thenReturn(Page.empty());

        subject.tracks(Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty(), authentication);

        verify(trackRepository).findInLibraries(eq(List.of(library.getId())),
                eq(SortingEnum.NAME), eq(SortingOrder.ASCENDING), any(Pageable.class));
    }

    @Test
    void artistBatchMappingResolvesPerTrackArtists() {
        PersonEntity beatles = PersonEntity.builder().name("The Beatles").build();
        beatles.setId(UUID.randomUUID());
        PersonEntity stones = PersonEntity.builder().name("The Rolling Stones").build();
        stones.setId(UUID.randomUUID());
        TrackEntity track1 = TrackEntity.builder().personEntity(beatles).build();
        track1.setId(UUID.randomUUID());
        TrackEntity track2 = TrackEntity.builder().personEntity(stones).build();
        track2.setId(UUID.randomUUID());
        when(personRepository.findAllById(List.of(beatles.getId(), stones.getId())))
                .thenReturn(List.of(beatles, stones));

        var result = subject.artist(List.of(track1, track2));

        assertEquals(beatles, result.get(track1));
        assertEquals(stones, result.get(track2));
    }

    @Test
    void albumSchemaMappingReturnsAlbum() {
        AlbumEntity album = AlbumEntity.builder().name("Abbey Road").releaseYear(1969).build();
        TrackEntity track = TrackEntity.builder().albumEntity(album).build();

        assertEquals(album, subject.album(track));
    }

    @Test
    void metadataSchemaMappingReturnsMetadata() {
        MetadataEntity meta = MetadataEntity.builder().title("Come Together").build();
        TrackEntity track = TrackEntity.builder().number(1).discNumber(1)
                .metadataEntities(List.of(meta)).build();

        List<MetadataEntity> result = subject.metadata(track);

        assertEquals(1, result.size());
    }

    @Test
    void mediaFileSchemaMappingReturnsMediaFiles() {
        MediaFileEntity file = MediaFileEntity.builder().path("/music/track.flac").size(1000L).build();
        TrackEntity track = TrackEntity.builder().number(1).discNumber(1)
                .mediaFileEntities(List.of(file)).build();

        List<MediaFileEntity> result = subject.mediaFile(track);

        assertEquals(1, result.size());
    }
}
