package app.ister.api.controller;

import app.ister.core.entity.AlbumEntity;
import app.ister.core.entity.ArtistEntity;
import app.ister.core.entity.ImageEntity;
import app.ister.core.entity.MetadataEntity;
import app.ister.core.entity.TrackEntity;
import app.ister.core.enums.SortingOrder;
import app.ister.core.repository.AlbumRepository;
import app.ister.core.repository.ArtistRepository;
import app.ister.core.repository.ImageRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

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
    private ArtistRepository artistRepository;

    @Mock
    private ImageRepository imageRepository;

    @Test
    void albumByIdReturnsFromRepository() {
        UUID id = UUID.randomUUID();
        AlbumEntity album = AlbumEntity.builder().name("Abbey Road").releaseYear(1969).build();
        when(albumRepository.findById(id)).thenReturn(Optional.of(album));

        Optional<AlbumEntity> result = subject.albumById(id);

        assertTrue(result.isPresent());
        assertEquals("Abbey Road", result.get().getName());
    }

    @Test
    void albumByIdReturnsEmptyWhenNotFound() {
        UUID id = UUID.randomUUID();
        when(albumRepository.findById(id)).thenReturn(Optional.empty());

        assertTrue(subject.albumById(id).isEmpty());
    }

    @Test
    void albumsWithArtistIdFiltersOnArtist() {
        UUID artistId = UUID.randomUUID();
        ArtistEntity artist = ArtistEntity.builder().name("The Beatles").build();
        AlbumEntity album = AlbumEntity.builder().name("Abbey Road").releaseYear(1969).build();
        Page<AlbumEntity> page = new PageImpl<>(List.of(album));
        when(artistRepository.findById(artistId)).thenReturn(Optional.of(artist));
        when(albumRepository.findByArtistEntity(eq(artist), any(Pageable.class))).thenReturn(page);

        Page<AlbumEntity> result = subject.albums(
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.of(artistId), Optional.empty());

        assertEquals(1, result.getContent().size());
        assertEquals("Abbey Road", result.getContent().get(0).getName());
    }

    @Test
    void albumsWithUnknownArtistIdReturnsEmpty() {
        UUID artistId = UUID.randomUUID();
        when(artistRepository.findById(artistId)).thenReturn(Optional.empty());

        Page<AlbumEntity> result = subject.albums(
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.of(artistId), Optional.empty());

        assertTrue(result.isEmpty());
    }

    @Test
    void albumsWithLibraryIdFiltersOnLibrary() {
        UUID libraryId = UUID.randomUUID();
        AlbumEntity album = AlbumEntity.builder().name("Abbey Road").releaseYear(1969).build();
        Page<AlbumEntity> page = new PageImpl<>(List.of(album));
        when(albumRepository.findByLibraryEntityId(eq(libraryId), any(Pageable.class))).thenReturn(page);

        Page<AlbumEntity> result = subject.albums(
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.of(libraryId));

        assertEquals(1, result.getContent().size());
    }

    @Test
    void artistSchemaMappingReturnsArtist() {
        ArtistEntity artist = ArtistEntity.builder().name("The Beatles").build();
        AlbumEntity album = AlbumEntity.builder().name("Abbey Road").artistEntity(artist).build();

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

        Page<AlbumEntity> result = subject.albums(
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty());

        assertEquals(1, result.getContent().size());
    }

    @Test
    void albumsWithDescendingOrderUsesDescendingSort() {
        AlbumEntity album = AlbumEntity.builder().name("Abbey Road").releaseYear(1969).build();
        Page<AlbumEntity> page = new PageImpl<>(List.of(album));
        when(albumRepository.findAll(any(Pageable.class))).thenReturn(page);

        Page<AlbumEntity> result = subject.albums(
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.of(SortingOrder.DESCENDING),
                Optional.empty(), Optional.empty());

        assertEquals(1, result.getContent().size());
    }
}
