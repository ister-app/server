package app.ister.core.service;

import app.ister.core.entity.BookEntity;
import app.ister.core.entity.LibraryEntity;
import app.ister.core.entity.PersonEntity;
import app.ister.core.entity.SeriesEntity;
import app.ister.core.enums.SearchEntityType;
import app.ister.core.repository.BookRepository;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BookSeriesServiceTest {

    @InjectMocks
    private BookSeriesService subject;

    @Mock
    private SeriesRepository seriesRepository;
    @Mock
    private BookRepository bookRepository;
    @Mock
    private ServerEventService serverEventService;

    private final LibraryEntity library = LibraryEntity.builder().build();
    private final PersonEntity author = PersonEntity.builder().name("John Flanagan").build();

    private BookEntity book(String name) {
        BookEntity book = BookEntity.builder()
                .id(UUID.randomUUID())
                .libraryEntity(library)
                .personEntity(author)
                .name(name)
                .build();
        lenient().when(bookRepository.findByPersonEntityId(author.getId()))
                .thenReturn(List.of(book));
        return book;
    }

    private void mockSeriesCreation() {
        lenient().when(seriesRepository.findByPersonEntityAndName(any(), any())).thenReturn(Optional.empty());
        lenient().when(seriesRepository.save(any(SeriesEntity.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    // ===== assignFromEpub =====

    @Test
    void assignFromEpubLinksTheSeriesAndStripsTheTitlePrefix() {
        mockSeriesCreation();
        BookEntity book = book("De Grijze Jager - De ruïnes van Gorlan");

        subject.assignFromEpub(book, "De Grijze Jager", 1.0);

        assertEquals("De Grijze Jager", book.getSeriesEntity().getName());
        assertEquals(1.0, book.getSeriesIndex());
        assertEquals("De ruïnes van Gorlan", book.getTitle());
        verify(bookRepository).save(book);
        verify(serverEventService).createSearchIndexEvent(SearchEntityType.BOOK, book.getId());
    }

    @Test
    void assignFromEpubStripsAColonSeparatorToo() {
        mockSeriesCreation();
        BookEntity book = book("De Grijze Jager: Losgeld voor Erak");

        subject.assignFromEpub(book, "De Grijze Jager", 7.0);

        assertEquals("Losgeld voor Erak", book.getTitle());
    }

    @Test
    void assignFromEpubStripsAnEnDashSeparator() {
        mockSeriesCreation();
        BookEntity book = book("De Grijze Jager – Het ijzige land");

        subject.assignFromEpub(book, "De Grijze Jager", 3.0);

        assertEquals("Het ijzige land", book.getTitle());
    }

    @Test
    void assignFromEpubMatchesTheSeriesPrefixCaseInsensitively() {
        mockSeriesCreation();
        BookEntity book = book("De Grijze jager - De brandende brug");

        subject.assignFromEpub(book, "De Grijze Jager", 2.0);

        assertEquals("De brandende brug", book.getTitle());
    }

    /** "Harry Potter en de Steen der Wijzen": series name in the title without a separator. */
    @Test
    void assignFromEpubKeepsATitleWhereTheSeriesNameHasNoSeparator() {
        mockSeriesCreation();
        BookEntity book = book("Harry Potter en de Steen der Wijzen");

        subject.assignFromEpub(book, "Harry Potter", 1.0);

        assertEquals("Harry Potter", book.getSeriesEntity().getName());
        assertNull(book.getTitle());
        verify(serverEventService, never()).createSearchIndexEvent(any(), any());
    }

    @Test
    void assignFromEpubIsANoOpWithoutASeriesName() {
        BookEntity book = book("De ruïnes van Gorlan");

        subject.assignFromEpub(book, "  ", 1.0);

        assertNull(book.getSeriesEntity());
        verify(bookRepository, never()).save(any());
    }

    @Test
    void assignFromEpubReusesAnExistingSeriesOfTheAuthor() {
        SeriesEntity existing = SeriesEntity.builder().personEntity(author).name("De Grijze Jager").build();
        when(seriesRepository.findByPersonEntityAndName(author, "De Grijze Jager"))
                .thenReturn(Optional.of(existing));
        BookEntity book = book("De Grijze Jager - Het ijzige land");

        subject.assignFromEpub(book, "De Grijze Jager", 3.0);

        assertEquals(existing, book.getSeriesEntity());
        verify(seriesRepository, never()).save(any());
    }

    /** A new series re-fires BOOK_FOUND for the author's series-less books (discovery may have
     * run before the series existed), but never for the book that just got linked. */
    @Test
    void assignFromEpubRefiresSerieslessSiblingsWhenTheSeriesIsNew() {
        mockSeriesCreation();
        BookEntity linked = book("De Grijze Jager - De ruïnes van Gorlan");
        BookEntity seriesless = book("Alleen - maar niet eenzaam");
        BookEntity alreadyInSeries = book("Elders - deel 1");
        alreadyInSeries.setSeriesEntity(SeriesEntity.builder().name("Elders").build());
        when(bookRepository.findByPersonEntityId(author.getId()))
                .thenReturn(List.of(linked, seriesless, alreadyInSeries));

        subject.assignFromEpub(linked, "De Grijze Jager", 1.0);

        verify(serverEventService).createBookFoundEvent(seriesless.getId());
        verify(serverEventService, never()).createBookFoundEvent(linked.getId());
        verify(serverEventService, never()).createBookFoundEvent(alreadyInSeries.getId());
    }

    /** Volume two of an existing series must not re-fire: once per new series is the bound. */
    @Test
    void assignFromEpubDoesNotRefireForAnExistingSeries() {
        SeriesEntity existing = SeriesEntity.builder().personEntity(author).name("De Grijze Jager").build();
        when(seriesRepository.findByPersonEntityAndName(author, "De Grijze Jager"))
                .thenReturn(Optional.of(existing));
        BookEntity book = book("De Grijze Jager - De brandende brug");

        subject.assignFromEpub(book, "De Grijze Jager", 2.0);

        verify(serverEventService, never()).createBookFoundEvent(any());
    }

    // ===== applyPrefixHeuristic =====

    @Test
    void heuristicGroupsTwoBooksSharingAPrefixIntoOneSeries() {
        mockSeriesCreation();
        BookEntity first = book("De Grijze Jager - De ruïnes van Gorlan");
        BookEntity second = book("De Grijze Jager - De brandende brug");
        when(bookRepository.findByPersonEntityId(author.getId())).thenReturn(List.of(first, second));

        subject.applyPrefixHeuristic(author);

        assertEquals("De Grijze Jager", first.getSeriesEntity().getName());
        assertEquals(first.getSeriesEntity(), second.getSeriesEntity());
        assertEquals("De ruïnes van Gorlan", first.getTitle());
        assertEquals("De brandende brug", second.getTitle());
        assertNull(first.getSeriesIndex());
    }

    /** A standalone book with " - " in its title must never be split. */
    @Test
    void heuristicSkipsASingleBookWithASeparator() {
        BookEntity single = book("Alleen - maar niet eenzaam");
        when(bookRepository.findByPersonEntityId(author.getId())).thenReturn(List.of(single));

        subject.applyPrefixHeuristic(author);

        assertNull(single.getSeriesEntity());
        verify(seriesRepository, never()).save(any());
        verify(bookRepository, never()).save(any());
    }

    @Test
    void heuristicNeverOverwritesAnEpubAssignedSeries() {
        mockSeriesCreation();
        SeriesEntity epubSeries = SeriesEntity.builder().personEntity(author).name("Ranger's Apprentice").build();
        BookEntity first = book("De Grijze Jager - De ruïnes van Gorlan");
        first.setSeriesEntity(epubSeries);
        BookEntity second = book("De Grijze Jager - De brandende brug");
        when(bookRepository.findByPersonEntityId(author.getId())).thenReturn(List.of(first, second));

        subject.applyPrefixHeuristic(author);

        assertEquals(epubSeries, first.getSeriesEntity());
        assertEquals("De Grijze Jager", second.getSeriesEntity().getName());
    }

    @Test
    void heuristicIgnoresBooksWithoutASeparator() {
        BookEntity first = book("Harry Potter en de Steen der Wijzen");
        BookEntity second = book("Harry Potter en de Geheime Kamer");
        when(bookRepository.findByPersonEntityId(author.getId())).thenReturn(List.of(first, second));

        subject.applyPrefixHeuristic(author);

        assertNull(first.getSeriesEntity());
        assertNull(second.getSeriesEntity());
        verify(seriesRepository, never()).save(any());
    }

    @Test
    void heuristicGroupsPrefixesCaseInsensitively() {
        mockSeriesCreation();
        BookEntity first = book("De Grijze Jager - De ruïnes van Gorlan");
        BookEntity second = book("De Grijze jager - De brandende brug");
        when(bookRepository.findByPersonEntityId(author.getId())).thenReturn(List.of(first, second));

        subject.applyPrefixHeuristic(author);

        assertEquals(first.getSeriesEntity(), second.getSeriesEntity());
    }

    // ===== updateDisplayTitle =====

    @Test
    void updateDisplayTitleClearsTheTitleWhenTheBookLeavesItsSeries() {
        BookEntity book = book("De Grijze Jager - De ruïnes van Gorlan");
        book.setTitle("De ruïnes van Gorlan");

        subject.updateDisplayTitle(book);

        assertNull(book.getTitle());
        verify(serverEventService).createSearchIndexEvent(SearchEntityType.BOOK, book.getId());
    }

    @Test
    void cleanupDelegatesToTheRepository() {
        subject.cleanupOrphanSeries();

        verify(seriesRepository).deleteByBookEntitiesIsEmpty();
    }
}
