package app.ister.api.controller;

import app.ister.core.entity.BookEntity;
import app.ister.core.entity.ImageEntity;
import app.ister.core.entity.PersonEntity;
import app.ister.core.entity.SeriesEntity;
import app.ister.core.entity.UserSeriesPreferenceEntity;
import app.ister.core.enums.ImageType;
import app.ister.core.enums.ReadingDirection;
import app.ister.core.repository.ImageRepository;
import app.ister.core.repository.MetadataRepository;
import app.ister.core.repository.SeriesRepository;
import app.ister.core.repository.UserSeriesPreferenceRepository;
import app.ister.core.service.SeriesPreferenceService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.Authentication;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SeriesControllerTest {

    @InjectMocks
    private SeriesController subject;

    @Mock
    private SeriesRepository seriesRepository;

    @Mock
    private ImageRepository imageRepository;

    @Mock
    private MetadataRepository metadataRepository;

    @Mock
    private UserSeriesPreferenceRepository userSeriesPreferenceRepository;

    @Mock
    private SeriesPreferenceService seriesPreferenceService;

    @Mock
    private Authentication authentication;

    private final PersonEntity author = PersonEntity.builder().id(UUID.randomUUID()).name("John Flanagan").build();

    private BookEntity book() {
        return BookEntity.builder().id(UUID.randomUUID()).personEntity(author).name("Book").build();
    }

    @Test
    void seriesByIdDelegatesToTheRepository() {
        UUID id = UUID.randomUUID();
        SeriesEntity series = SeriesEntity.builder().id(id).name("De Grijze Jager").build();
        when(seriesRepository.findById(id)).thenReturn(Optional.of(series));

        assertEquals(Optional.of(series), subject.seriesById(id));
    }

    @Test
    void authorAndBooksComeFromTheEntity() {
        List<BookEntity> books = List.of(book(), book());
        SeriesEntity series = SeriesEntity.builder()
                .personEntity(author).name("De Grijze Jager").bookEntities(books).build();

        assertEquals(author, subject.author(series));
        assertEquals(books, subject.books(series));
    }

    /** Without own artwork, the first book with a COVER image represents the series. */
    @Test
    void coverFallsBackToTheFirstBooksCover() {
        BookEntity first = book();
        BookEntity second = book();
        SeriesEntity series = SeriesEntity.builder().id(UUID.randomUUID())
                .name("S").bookEntities(List.of(first, second)).build();
        ImageEntity background = ImageEntity.builder().type(ImageType.BACKGROUND).build();
        ImageEntity cover = ImageEntity.builder().type(ImageType.COVER).build();
        when(imageRepository.findBySeriesEntityId(series.getId())).thenReturn(List.of());
        when(imageRepository.findByBookEntityId(first.getId())).thenReturn(List.of(background));
        when(imageRepository.findByBookEntityId(second.getId())).thenReturn(List.of(cover));

        assertEquals(cover, subject.cover(series));
    }

    /** A comic series' own artwork (folder.jpg / wiki thumbnail) wins over volume covers. */
    @Test
    void ownSeriesArtworkWinsOverVolumeCovers() {
        BookEntity volume = book();
        SeriesEntity series = SeriesEntity.builder().id(UUID.randomUUID())
                .name("S").bookEntities(List.of(volume)).build();
        ImageEntity own = ImageEntity.builder().type(ImageType.COVER).build();
        when(imageRepository.findBySeriesEntityId(series.getId())).thenReturn(List.of(own));

        assertEquals(own, subject.cover(series));
    }

    @Test
    void coverIsNullWhenNoBookHasOne() {
        BookEntity only = book();
        SeriesEntity series = SeriesEntity.builder().id(UUID.randomUUID())
                .name("S").bookEntities(List.of(only)).build();
        when(imageRepository.findBySeriesEntityId(series.getId())).thenReturn(List.of());
        when(imageRepository.findByBookEntityId(only.getId())).thenReturn(List.of());

        assertNull(subject.cover(series));
    }

    @Test
    void seriesQueryFiltersByLibrary() {
        UUID libraryId = UUID.randomUUID();
        SeriesEntity series = SeriesEntity.builder().name("De Grijze Jager").build();
        when(seriesRepository.findByLibraryEntityId(org.mockito.ArgumentMatchers.eq(libraryId), org.mockito.ArgumentMatchers.any()))
                .thenReturn(new org.springframework.data.domain.PageImpl<>(List.of(series)));

        assertEquals(List.of(series), subject.series(Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.of(libraryId)).getContent());
    }

    @Test
    void setSeriesReadingDirectionDelegatesToTheService() {
        UUID seriesId = UUID.randomUUID();

        assertTrue(subject.setSeriesReadingDirection(seriesId, ReadingDirection.RTL, authentication));

        verify(seriesPreferenceService).setReadingDirection(authentication, seriesId, ReadingDirection.RTL);
    }

    /** Override wins; without one the detected series default; without either LTR. */
    @Test
    void readingDirectionResolvesPreferenceOverDefaultOverLtr() {
        SeriesEntity withOverride = SeriesEntity.builder().id(UUID.randomUUID()).name("A").build();
        SeriesEntity manga = SeriesEntity.builder().id(UUID.randomUUID()).name("B")
                .defaultReadingDirection(ReadingDirection.RTL).build();
        SeriesEntity plain = SeriesEntity.builder().id(UUID.randomUUID()).name("C").build();
        List<SeriesEntity> all = List.of(withOverride, manga, plain);
        when(authentication.getName()).thenReturn("user-1");
        when(userSeriesPreferenceRepository.findByUserEntityExternalIdAndSeriesEntityIn("user-1", all))
                .thenReturn(List.of(UserSeriesPreferenceEntity.builder()
                        .seriesEntity(withOverride).readingDirection(ReadingDirection.RTL).build()));

        Map<SeriesEntity, ReadingDirection> resolved = subject.readingDirection(all, authentication);

        assertEquals(ReadingDirection.RTL, resolved.get(withOverride));
        assertEquals(ReadingDirection.RTL, resolved.get(manga));
        assertEquals(ReadingDirection.LTR, resolved.get(plain));
    }

    /** Only series with an explicit override appear; the rest resolve to null (default applies). */
    @Test
    void userReadingDirectionOnlyMapsExplicitOverrides() {
        SeriesEntity withOverride = SeriesEntity.builder().id(UUID.randomUUID()).name("A").build();
        SeriesEntity without = SeriesEntity.builder().id(UUID.randomUUID()).name("B").build();
        List<SeriesEntity> all = List.of(withOverride, without);
        when(authentication.getName()).thenReturn("user-1");
        when(userSeriesPreferenceRepository.findByUserEntityExternalIdAndSeriesEntityIn("user-1", all))
                .thenReturn(List.of(UserSeriesPreferenceEntity.builder()
                        .seriesEntity(withOverride).readingDirection(ReadingDirection.LTR).build()));

        Map<SeriesEntity, ReadingDirection> resolved = subject.userReadingDirection(all, authentication);

        assertEquals(ReadingDirection.LTR, resolved.get(withOverride));
        assertFalse(resolved.containsKey(without));
    }

    @Test
    void seriesByIdReturnsEmptyForAnUnknownId() {
        UUID id = UUID.randomUUID();
        when(seriesRepository.findById(id)).thenReturn(Optional.empty());

        assertTrue(subject.seriesById(id).isEmpty());
    }
}
