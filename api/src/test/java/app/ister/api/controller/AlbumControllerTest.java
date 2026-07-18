package app.ister.api.controller;

import app.ister.core.entity.AlbumEntity;
import app.ister.core.entity.LibraryEntity;
import app.ister.core.entity.PersonEntity;
import app.ister.core.entity.ImageEntity;
import app.ister.core.entity.MetadataEntity;
import app.ister.core.entity.TrackEntity;
import app.ister.core.enums.SortingOrder;
import app.ister.core.repository.AlbumRepository;
import app.ister.core.repository.PersonRepository;
import app.ister.core.repository.ImageRepository;
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
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AlbumControllerTest {

    @InjectMocks
    private AlbumController subject;

    @Mock
    private AlbumRepository albumRepository;

    @Mock
    private PersonRepository personRepository;

    @Mock
    private ImageRepository imageRepository;

    @Mock
    private LibraryAccessService libraryAccessService;

    @Mock
    private Authentication authentication;

    private LibraryEntity library() {
        LibraryEntity library = LibraryEntity.builder().name("L").build();
        library.setId(UUID.randomUUID());
        return library;
    }

    @Test
    void albumByIdReturnsFromRepository() {
        UUID id = UUID.randomUUID();
        AlbumEntity album = AlbumEntity.builder().name("Abbey Road").releaseYear(1969)
                .libraryEntity(library()).build();
        when(albumRepository.findById(id)).thenReturn(Optional.of(album));
        when(libraryAccessService.canAccess(any(LibraryEntity.class), any())).thenReturn(true);

        Optional<AlbumEntity> result = subject.albumById(id, authentication);

        assertTrue(result.isPresent());
        assertEquals("Abbey Road", result.get().getName());
    }

    @Test
    void albumByIdReturnsEmptyWhenNotFound() {
        UUID id = UUID.randomUUID();
        when(albumRepository.findById(id)).thenReturn(Optional.empty());

        assertTrue(subject.albumById(id, authentication).isEmpty());
    }

    @Test
    void albumsWithPersonIdFiltersOnArtist() {
        UUID personId = UUID.randomUUID();
        PersonEntity artist = PersonEntity.builder().name("The Beatles").build();
        AlbumEntity album = AlbumEntity.builder().name("Abbey Road").releaseYear(1969).build();
        Page<AlbumEntity> page = new PageImpl<>(List.of(album));
        when(personRepository.findById(personId)).thenReturn(Optional.of(artist));
        when(albumRepository.findByPersonEntity(eq(artist), any(Pageable.class))).thenReturn(page);
        when(libraryAccessService.allowedLibraryIds(any())).thenReturn(Optional.empty());

        Page<AlbumEntity> result = subject.albums(
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.of(personId), Optional.empty(), authentication);

        assertEquals(1, result.getContent().size());
        assertEquals("Abbey Road", result.getContent().get(0).getName());
    }

    @Test
    void albumsWithUnknownPersonIdReturnsEmpty() {
        UUID personId = UUID.randomUUID();
        when(personRepository.findById(personId)).thenReturn(Optional.empty());

        Page<AlbumEntity> result = subject.albums(
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.of(personId), Optional.empty(), authentication);

        assertTrue(result.isEmpty());
    }

    @Test
    void albumsWithLibraryIdFiltersOnLibrary() {
        UUID libraryId = UUID.randomUUID();
        AlbumEntity album = AlbumEntity.builder().name("Abbey Road").releaseYear(1969).build();
        Page<AlbumEntity> page = new PageImpl<>(List.of(album));
        when(albumRepository.findByLibraryEntityId(eq(libraryId), any(Pageable.class))).thenReturn(page);
        when(libraryAccessService.canAccess(any(UUID.class), any())).thenReturn(true);

        Page<AlbumEntity> result = subject.albums(
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.of(libraryId), authentication);

        assertEquals(1, result.getContent().size());
    }

    @Test
    void artistSchemaMappingReturnsArtist() {
        PersonEntity artist = PersonEntity.builder().name("The Beatles").build();
        AlbumEntity album = AlbumEntity.builder().name("Abbey Road").personEntity(artist).build();

        assertEquals(artist, subject.artist(album));
    }

    @Test
    void imagesSchemaMappingQueriesRepository() {
        UUID albumId = UUID.randomUUID();
        AlbumEntity album = AlbumEntity.builder().build();
        org.springframework.test.util.ReflectionTestUtils.setField(album, "id", albumId);
        ImageEntity image = ImageEntity.builder().build();
        image.setAlbumEntity(album);
        when(imageRepository.findByAlbumEntityIdIn(List.of(albumId))).thenReturn(List.of(image));

        Map<AlbumEntity, List<ImageEntity>> result = subject.images(List.of(album));

        assertEquals(1, result.get(album).size());
    }

    @Test
    void tracksSchemaMappingReturnsTracks() {
        TrackEntity track = TrackEntity.builder().number(1).discNumber(1).build();
        AlbumEntity album = AlbumEntity.builder().name("Abbey Road").trackEntities(List.of(track)).build();

        List<TrackEntity> result = subject.tracks(album);

        assertEquals(1, result.size());
        assertEquals(track, result.get(0));
    }

    @Test
    void metadataSchemaMappingReturnsMetadata() {
        MetadataEntity meta = MetadataEntity.builder().title("Abbey Road").build();
        AlbumEntity album = AlbumEntity.builder().name("Abbey Road").metadataEntities(List.of(meta)).build();

        List<MetadataEntity> result = subject.metadata(album);

        assertEquals(1, result.size());
        assertEquals(meta, result.get(0));
    }

    @Test
    void albumsWithNoFilterReturnsFindAll() {
        AlbumEntity album = AlbumEntity.builder().name("Abbey Road").releaseYear(1969).build();
        Page<AlbumEntity> page = new PageImpl<>(List.of(album));
        when(albumRepository.findAll(any(Pageable.class))).thenReturn(page);
        when(libraryAccessService.allowedLibraryIds(any())).thenReturn(Optional.empty());

        Page<AlbumEntity> result = subject.albums(
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty(), authentication);

        assertEquals(1, result.getContent().size());
    }

    @Test
    void albumsWithDescendingOrderUsesDescendingSort() {
        AlbumEntity album = AlbumEntity.builder().name("Abbey Road").releaseYear(1969).build();
        Page<AlbumEntity> page = new PageImpl<>(List.of(album));
        when(albumRepository.findAll(any(Pageable.class))).thenReturn(page);
        when(libraryAccessService.allowedLibraryIds(any())).thenReturn(Optional.empty());

        Page<AlbumEntity> result = subject.albums(
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.of(SortingOrder.DESCENDING),
                Optional.empty(), Optional.empty(), authentication);

        assertEquals(1, result.getContent().size());
    }
}
