package app.ister.api.controller;

import app.ister.core.entity.BookEntity;
import app.ister.core.entity.ChapterEntity;
import app.ister.core.entity.ImageEntity;
import app.ister.core.entity.MediaFileEntity;
import app.ister.core.entity.MetadataEntity;
import app.ister.core.entity.PersonEntity;
import app.ister.core.entity.WatchStatusEntity;
import app.ister.core.enums.SortingEnum;
import app.ister.core.enums.SortingOrder;
import app.ister.core.repository.BookRepository;
import app.ister.core.repository.ImageRepository;
import app.ister.core.repository.MediaFileRepository;
import app.ister.core.repository.PersonRepository;
import app.ister.core.repository.WatchStatusRepository;
import app.ister.core.service.ContinueWatchingService;
import app.ister.core.service.WatchStatusService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.security.core.Authentication;

import java.time.LocalDate;
import java.time.Month;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BookControllerTest {

    @InjectMocks
    private BookController subject;

    @Mock
    private BookRepository bookRepository;

    @Mock
    private PersonRepository personRepository;

    @Mock
    private ImageRepository imageRepository;

    @Mock
    private MediaFileRepository mediaFileRepository;

    @Mock
    private WatchStatusRepository watchStatusRepository;

    @Mock
    private WatchStatusService watchStatusService;

    @Mock
    private ContinueWatchingService continueWatchingService;

    @Mock
    private Authentication authentication;

    private BookEntity book(String name) {
        BookEntity book = BookEntity.builder().name(name).build();
        book.setId(UUID.randomUUID());
        return book;
    }

    @Test
    void bookByIdDelegatesToRepository() {
        BookEntity book = book("Dit zijn de namen");
        when(bookRepository.findById(book.getId())).thenReturn(Optional.of(book));

        Optional<BookEntity> result = subject.bookById(book.getId());

        assertTrue(result.isPresent());
    }

    @Test
    void booksReturnsAllBooksWhenNoFilterIsGiven() {
        when(bookRepository.findAll(any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(book("Dit zijn de namen"))));

        Page<BookEntity> result = subject.books(Optional.empty(), Optional.empty(),
                Optional.of(SortingEnum.NAME), Optional.of(SortingOrder.ASCENDING),
                Optional.empty(), Optional.empty());

        assertEquals(1, result.getTotalElements());
    }

    @Test
    void booksFiltersOnAuthor() {
        UUID authorId = UUID.randomUUID();
        PersonEntity author = PersonEntity.builder().name("Tommy Wieringa").build();
        BookEntity book = book("Dit zijn de namen");
        when(personRepository.findById(authorId)).thenReturn(Optional.of(author));
        when(bookRepository.findByPersonEntity(eq(author), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(book)));

        Page<BookEntity> result = subject.books(Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.of(authorId), Optional.empty());

        assertEquals(List.of(book), result.getContent());
    }

    @Test
    void booksReturnsAnEmptyPageForAnUnknownAuthor() {
        UUID authorId = UUID.randomUUID();
        when(personRepository.findById(authorId)).thenReturn(Optional.empty());

        Page<BookEntity> result = subject.books(Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.of(authorId), Optional.empty());

        assertTrue(result.isEmpty());
        verifyNoInteractions(bookRepository);
    }

    @Test
    void booksFiltersOnLibrary() {
        UUID libraryId = UUID.randomUUID();
        when(bookRepository.findByLibraryEntityId(eq(libraryId), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(book("Dit zijn de namen"))));

        Page<BookEntity> result = subject.books(Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.of(libraryId));

        assertEquals(1, result.getTotalElements());
        verifyNoInteractions(personRepository);
    }

    @Test
    void updateReadingProgressStoresLocationAndProgress() {
        BookEntity book = book("Dit zijn de namen");
        WatchStatusEntity status = WatchStatusEntity.builder().bookEntity(book).build();
        when(bookRepository.findById(book.getId())).thenReturn(Optional.of(book));
        when(watchStatusService.getOrCreateForBook(authentication, book)).thenReturn(status);

        WatchStatusEntity result = subject.updateReadingProgress(book.getId(), "epubcfi(/6/4!/4/2)", 0.5, authentication);

        assertEquals("epubcfi(/6/4!/4/2)", result.getReadingLocation());
        assertEquals(0.5, result.getReadingProgress());
        assertFalse(result.isWatched());
        verify(watchStatusRepository).save(status);
    }

    /** Near the end of the epub the book counts as read, and progress never exceeds 1.0. */
    @Test
    void updateReadingProgressMarksTheBookAsReadAndClampsProgress() {
        BookEntity book = book("Dit zijn de namen");
        WatchStatusEntity status = WatchStatusEntity.builder().bookEntity(book).build();
        when(bookRepository.findById(book.getId())).thenReturn(Optional.of(book));
        when(watchStatusService.getOrCreateForBook(authentication, book)).thenReturn(status);

        WatchStatusEntity result = subject.updateReadingProgress(book.getId(), "epubcfi(/6/40)", 1.5, authentication);

        assertEquals(1.0, result.getReadingProgress());
        assertTrue(result.isWatched());
    }

    @Test
    void updateReadingProgressFailsForAnUnknownBook() {
        UUID bookId = UUID.randomUUID();
        when(bookRepository.findById(bookId)).thenReturn(Optional.empty());

        assertThrows(NoSuchElementException.class,
                () -> subject.updateReadingProgress(bookId, "epubcfi(/6/4)", 0.1, authentication));

        verifyNoInteractions(watchStatusService, watchStatusRepository);
    }

    @Test
    void authorReturnsTheBooksPerson() {
        PersonEntity person = PersonEntity.builder().name("Tommy Wieringa").build();
        BookEntity book = BookEntity.builder().name("Dit zijn de namen").personEntity(person).build();

        assertEquals(person, subject.author(book));
    }

    @Test
    void releaseYearPrefersTheBooksOwnYear() {
        BookEntity book = BookEntity.builder().name("Dit zijn de namen").releaseYear(2012).build();

        assertEquals(2012, subject.releaseYear(book));
    }

    /** Without a "(YYYY)" suffix the year comes from the metadata; the earliest release wins. */
    @Test
    void releaseYearFallsBackToTheEarliestMetadataYear() {
        BookEntity book = BookEntity.builder().name("Dit zijn de namen")
                .metadataEntities(List.of(
                        MetadataEntity.builder().released(LocalDate.of(2015, Month.JANUARY, 1)).build(),
                        MetadataEntity.builder().build(),
                        MetadataEntity.builder().released(LocalDate.of(2012, Month.JUNE, 1)).build()))
                .build();

        assertEquals(2012, subject.releaseYear(book));
    }

    @Test
    void releaseYearIsZeroWhenNothingKnowsTheYear() {
        BookEntity book = BookEntity.builder().name("Dit zijn de namen")
                .metadataEntities(List.of(MetadataEntity.builder().build()))
                .build();

        assertEquals(0, subject.releaseYear(book));
    }

    @Test
    void chaptersAndMetadataAreReadFromTheBook() {
        ChapterEntity chapter = ChapterEntity.builder().number(1).build();
        MetadataEntity metadata = MetadataEntity.builder().title("Dit zijn de namen").build();
        BookEntity book = BookEntity.builder().name("Dit zijn de namen")
                .chapterEntities(List.of(chapter)).metadataEntities(List.of(metadata)).build();

        assertEquals(List.of(chapter), subject.chapters(book));
        assertEquals(List.of(metadata), subject.metadata(book));
    }

    /** Deterministic source order: nfo, then epub, then Open Library — never raw JPA row order. */
    @Test
    void metadataIsOrderedNfoThenEpubThenOpenLibrary() {
        MetadataEntity openLibrary = MetadataEntity.builder().sourceUri("openlibrary://works/OL1W").build();
        MetadataEntity epub = MetadataEntity.builder().sourceUri("file:///books/Book.epub").build();
        MetadataEntity nfo = MetadataEntity.builder().sourceUri("file:///books/Book/album.nfo").build();
        BookEntity book = BookEntity.builder().name("Dit zijn de namen")
                .metadataEntities(List.of(openLibrary, epub, nfo)).build();

        assertEquals(List.of(nfo, epub, openLibrary), subject.metadata(book));
    }

    @Test
    void titleFallsBackToTheNameWhenNoCleanTitleIsSet() {
        BookEntity withTitle = BookEntity.builder()
                .name("De Grijze Jager - De ruïnes van Gorlan").title("De ruïnes van Gorlan").build();
        BookEntity withoutTitle = BookEntity.builder().name("Night Flight").build();

        assertEquals("De ruïnes van Gorlan", subject.title(withTitle));
        assertEquals("Night Flight", subject.title(withoutTitle));
    }

    @Test
    void epubFilesAreLookedUpByBookId() {
        BookEntity book = book("Dit zijn de namen");
        MediaFileEntity epub = MediaFileEntity.builder().build();
        when(mediaFileRepository.findByBookEntityId(book.getId())).thenReturn(List.of(epub));

        assertEquals(List.of(epub), subject.epubFiles(book));
    }

    @Test
    void imagesBatchMapsBooksWithoutImagesToAnEmptyList() {
        BookEntity withCover = book("Dit zijn de namen");
        BookEntity withoutCover = book("Joe Speedboot");
        ImageEntity image = ImageEntity.builder().build();
        image.setBookEntityId(withCover.getId());
        when(imageRepository.findByBookEntityIdIn(List.of(withCover.getId(), withoutCover.getId())))
                .thenReturn(List.of(image));

        Map<BookEntity, List<ImageEntity>> result = subject.images(List.of(withCover, withoutCover));

        assertEquals(List.of(image), result.get(withCover));
        assertEquals(List.of(), result.get(withoutCover));
    }

    @Test
    void watchStatusBatchMapsBooksWithoutStatusToAnEmptyList() {
        BookEntity read = book("Dit zijn de namen");
        BookEntity unread = book("Joe Speedboot");
        WatchStatusEntity status = WatchStatusEntity.builder().bookEntity(read).build();
        when(authentication.getName()).thenReturn("user-1");
        when(watchStatusRepository.findByUserEntityExternalIdAndBookEntityIn(eq("user-1"),
                eq(List.of(read, unread)), any())).thenReturn(List.of(status));

        Map<BookEntity, List<WatchStatusEntity>> result =
                subject.watchStatus(List.of(read, unread), authentication);

        assertEquals(List.of(status), result.get(read));
        assertEquals(List.of(), result.get(unread));
    }
}
