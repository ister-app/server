package app.ister.api.controller;

import app.ister.core.entity.BookEntity;
import app.ister.core.entity.ImageEntity;
import app.ister.core.entity.PersonEntity;
import app.ister.core.entity.SeriesEntity;
import app.ister.core.enums.ImageType;
import app.ister.core.repository.ImageRepository;
import app.ister.core.repository.SeriesRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SeriesControllerTest {

    @InjectMocks
    private SeriesController subject;

    @Mock
    private SeriesRepository seriesRepository;

    @Mock
    private ImageRepository imageRepository;

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

    /** A series has no artwork of its own: the first book with a COVER image represents it. */
    @Test
    void coverIsTheFirstBooksCover() {
        BookEntity first = book();
        BookEntity second = book();
        SeriesEntity series = SeriesEntity.builder().name("S").bookEntities(List.of(first, second)).build();
        ImageEntity background = ImageEntity.builder().type(ImageType.BACKGROUND).build();
        ImageEntity cover = ImageEntity.builder().type(ImageType.COVER).build();
        when(imageRepository.findByBookEntityId(first.getId())).thenReturn(List.of(background));
        when(imageRepository.findByBookEntityId(second.getId())).thenReturn(List.of(cover));

        assertEquals(cover, subject.cover(series));
    }

    @Test
    void coverIsNullWhenNoBookHasOne() {
        BookEntity only = book();
        SeriesEntity series = SeriesEntity.builder().name("S").bookEntities(List.of(only)).build();
        when(imageRepository.findByBookEntityId(only.getId())).thenReturn(List.of());

        assertNull(subject.cover(series));
    }

    @Test
    void seriesByIdReturnsEmptyForAnUnknownId() {
        UUID id = UUID.randomUUID();
        when(seriesRepository.findById(id)).thenReturn(Optional.empty());

        assertTrue(subject.seriesById(id).isEmpty());
    }
}
