package app.ister.api.controller;

import app.ister.core.entity.AlbumEntity;
import app.ister.core.entity.CreditEntity;
import app.ister.core.entity.PersonEntity;
import app.ister.core.entity.ImageEntity;
import app.ister.core.entity.LibraryEntity;
import app.ister.core.entity.MetadataEntity;
import app.ister.core.enums.SortingEnum;
import app.ister.core.enums.SortingOrder;
import app.ister.core.repository.CreditRepository;
import app.ister.core.repository.PersonRepository;
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
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PersonControllerTest {

    @InjectMocks
    private PersonController subject;

    @Mock
    private PersonRepository personRepository;

    @Mock
    private ImageRepository imageRepository;

    @Mock
    private LibraryRepository libraryRepository;

    @Mock
    private CreditRepository creditRepository;

    @Test
    void creditsSchemaMappingReturnsFromRepository() {
        UUID id = UUID.randomUUID();
        PersonEntity person = PersonEntity.builder().build();
        org.springframework.test.util.ReflectionTestUtils.setField(person, "id", id);
        CreditEntity credit = CreditEntity.builder().characterName("Neo").build();
        when(creditRepository.findByPersonEntityId(eq(id), any(org.springframework.data.domain.Sort.class)))
                .thenReturn(List.of(credit));

        List<CreditEntity> result = subject.credits(person);

        assertEquals(List.of(credit), result);
    }

    @Test
    void personByIdReturnsFromRepository() {
        UUID id = UUID.randomUUID();
        PersonEntity artist = PersonEntity.builder().name("The Beatles").build();
        when(personRepository.findById(id)).thenReturn(Optional.of(artist));

        Optional<PersonEntity> result = subject.personById(id);

        assertTrue(result.isPresent());
        assertEquals("The Beatles", result.get().getName());
    }

    @Test
    void personByIdReturnsEmptyWhenNotFound() {
        UUID id = UUID.randomUUID();
        when(personRepository.findById(id)).thenReturn(Optional.empty());

        assertTrue(subject.personById(id).isEmpty());
    }

    @Test
    void personsWithoutLibraryFilterReturnsAll() {
        PersonEntity artist = PersonEntity.builder().name("Artist A").build();
        Page<PersonEntity> page = new PageImpl<>(List.of(artist));
        when(personRepository.findAll(any(Pageable.class))).thenReturn(page);

        Page<PersonEntity> result = subject.persons(
                Optional.of(0), Optional.of(10),
                Optional.of(SortingEnum.NAME), Optional.of(SortingOrder.ASCENDING),
                Optional.empty());

        assertEquals(1, result.getContent().size());
        verify(personRepository).findAll(any(Pageable.class));
        verify(libraryRepository, never()).findById(any());
    }

    @Test
    void personsWithLibraryFilterFiltersOnLibrary() {
        UUID libraryId = UUID.randomUUID();
        LibraryEntity library = LibraryEntity.builder().name("Music").build();
        PersonEntity artist = PersonEntity.builder().name("Artist A").build();
        Page<PersonEntity> page = new PageImpl<>(List.of(artist));
        when(libraryRepository.findById(libraryId)).thenReturn(Optional.of(library));
        when(personRepository.findByLibraryEntity(eq(library), any(Pageable.class))).thenReturn(page);

        Page<PersonEntity> result = subject.persons(
                Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty(),
                Optional.of(libraryId));

        assertEquals(1, result.getContent().size());
    }

    @Test
    void albumsSchemaMappingReturnsAlbumList() {
        AlbumEntity album = AlbumEntity.builder().name("Abbey Road").releaseYear(1969).build();
        PersonEntity artist = PersonEntity.builder().name("The Beatles").albumEntities(List.of(album)).build();

        List<AlbumEntity> result = subject.albums(artist);

        assertEquals(1, result.size());
        assertEquals("Abbey Road", result.get(0).getName());
    }

    @Test
    void imagesSchemaMappingQueriesRepository() {
        UUID personId = UUID.randomUUID();
        PersonEntity artist = PersonEntity.builder().build();
        org.springframework.test.util.ReflectionTestUtils.setField(artist, "id", personId);
        ImageEntity image = ImageEntity.builder().build();
        image.setPersonEntity(artist);
        when(imageRepository.findByPersonEntityIdIn(List.of(personId))).thenReturn(List.of(image));

        Map<PersonEntity, List<ImageEntity>> result = subject.images(List.of(artist));

        assertEquals(1, result.get(artist).size());
    }

    @Test
    void metadataSchemaMappingReturnsMetadata() {
        MetadataEntity meta = MetadataEntity.builder().title("The Beatles bio").build();
        PersonEntity artist = PersonEntity.builder().name("The Beatles").metadataEntities(List.of(meta)).build();

        List<MetadataEntity> result = subject.metadata(artist);

        assertEquals(1, result.size());
        assertEquals("The Beatles bio", result.get(0).getTitle());
    }
}
