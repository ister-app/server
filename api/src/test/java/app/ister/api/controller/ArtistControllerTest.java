package app.ister.api.controller;

import app.ister.core.entity.AlbumEntity;
import app.ister.core.entity.ArtistEntity;
import app.ister.core.entity.ImageEntity;
import app.ister.core.entity.LibraryEntity;
import app.ister.core.entity.MetadataEntity;
import app.ister.core.enums.SortingEnum;
import app.ister.core.enums.SortingOrder;
import app.ister.core.repository.ArtistRepository;
import app.ister.core.repository.ImageRepository;
import app.ister.core.repository.LibraryRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ArtistControllerTest {

    @InjectMocks
    private ArtistController subject;

    @Mock
    private ArtistRepository artistRepository;

    @Mock
    private ImageRepository imageRepository;

    @Mock
    private LibraryRepository libraryRepository;

    @Test
    void artistByIdReturnsFromRepository() {
        UUID id = UUID.randomUUID();
        ArtistEntity artist = ArtistEntity.builder().name("The Beatles").build();
        when(artistRepository.findById(id)).thenReturn(Optional.of(artist));

        Optional<ArtistEntity> result = subject.artistById(id);

        assertTrue(result.isPresent());
        assertEquals("The Beatles", result.get().getName());
    }

    @Test
    void artistByIdReturnsEmptyWhenNotFound() {
        UUID id = UUID.randomUUID();
        when(artistRepository.findById(id)).thenReturn(Optional.empty());

        assertTrue(subject.artistById(id).isEmpty());
    }

    @Test
    void artistsWithoutLibraryFilterReturnsAll() {
        ArtistEntity artist = ArtistEntity.builder().name("Artist A").build();
        Page<ArtistEntity> page = new PageImpl<>(List.of(artist));
        when(artistRepository.findAll(any(Pageable.class))).thenReturn(page);

        Page<ArtistEntity> result = subject.artists(
                Optional.of(0), Optional.of(10),
                Optional.of(SortingEnum.NAME), Optional.of(SortingOrder.ASCENDING),
                Optional.empty());

        assertEquals(1, result.getContent().size());
        verify(artistRepository).findAll(any(Pageable.class));
        verify(libraryRepository, never()).findById(any());
    }

    @Test
    void artistsWithLibraryFilterFiltersOnLibrary() {
        UUID libraryId = UUID.randomUUID();
        LibraryEntity library = LibraryEntity.builder().name("Music").build();
        ArtistEntity artist = ArtistEntity.builder().name("Artist A").build();
        Page<ArtistEntity> page = new PageImpl<>(List.of(artist));
        when(libraryRepository.findById(libraryId)).thenReturn(Optional.of(library));
        when(artistRepository.findByLibraryEntity(eq(library), any(Pageable.class))).thenReturn(page);

        Page<ArtistEntity> result = subject.artists(
                Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty(),
                Optional.of(libraryId));

        assertEquals(1, result.getContent().size());
    }

    @Test
    void albumsSchemaMappingReturnsAlbumList() {
        AlbumEntity album = AlbumEntity.builder().name("Abbey Road").releaseYear(1969).build();
        ArtistEntity artist = ArtistEntity.builder().name("The Beatles").albumEntities(List.of(album)).build();

        List<AlbumEntity> result = subject.albums(artist);

        assertEquals(1, result.size());
        assertEquals("Abbey Road", result.get(0).getName());
    }

    @Test
    void imagesSchemaMappingQueriesRepository() {
        UUID artistId = UUID.randomUUID();
        ArtistEntity artist = ArtistEntity.builder().build();
        org.springframework.test.util.ReflectionTestUtils.setField(artist, "id", artistId);
        ImageEntity image = ImageEntity.builder().build();
        when(imageRepository.findByArtistEntityId(artistId)).thenReturn(List.of(image));

        List<ImageEntity> result = subject.images(artist);

        assertEquals(1, result.size());
    }

    @Test
    void metadataSchemaMappingReturnsMetadata() {
        MetadataEntity meta = MetadataEntity.builder().title("The Beatles bio").build();
        ArtistEntity artist = ArtistEntity.builder().name("The Beatles").metadataEntities(List.of(meta)).build();

        List<MetadataEntity> result = subject.metadata(artist);

        assertEquals(1, result.size());
        assertEquals("The Beatles bio", result.get(0).getTitle());
    }
}
