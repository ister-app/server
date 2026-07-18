package app.ister.worker.events.bookfound;

import app.ister.core.entity.BookEntity;
import app.ister.core.entity.ImageEntity;
import app.ister.core.entity.MediaFileEntity;
import app.ister.core.entity.MetadataEntity;
import app.ister.core.entity.PersonEntity;
import app.ister.core.enums.EventType;
import app.ister.core.enums.ImageType;
import app.ister.core.enums.SearchEntityType;
import app.ister.core.eventdata.BookFoundData;
import app.ister.core.repository.BookRepository;
import app.ister.core.repository.ImageRepository;
import app.ister.core.repository.MediaFileRepository;
import app.ister.core.repository.MetadataRepository;
import app.ister.core.repository.SeriesRepository;
import app.ister.core.config.LanguageProperties;
import app.ister.core.entity.SeriesEntity;
import app.ister.core.service.BookSeriesService;
import app.ister.core.service.ScannerHelperService;
import app.ister.core.service.ServerEventService;
import app.ister.worker.events.openlibrary.OpenLibraryService;
import app.ister.worker.events.tmdbmetadata.ImageDownloadService;
import app.ister.worker.events.tmdbmetadata.ImageSave;
import app.ister.worker.events.wikipedia.WikidataBookSeriesService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class HandleBookFoundTest {

    private static final String COVER_URL = "https://covers.openlibrary.org/b/id/1234-L.jpg";

    @InjectMocks
    private HandleBookFound subject;

    @Mock
    private BookRepository bookRepository;

    @Mock
    private ImageRepository imageRepository;

    @Mock
    private MetadataRepository metadataRepository;

    @Mock
    private MediaFileRepository mediaFileRepository;

    @Mock
    private SeriesRepository seriesRepository;

    @Mock
    private BookSeriesService bookSeriesService;

    @Mock
    private OpenLibraryService openLibraryService;

    @Mock
    private ImageDownloadService imageDownloadService;

    @Mock
    private ServerEventService serverEventService;

    @Mock
    private ScannerHelperService scannerHelperService;

    @Mock
    private WikidataBookSeriesService wikidataBookSeriesService;

    @Spy
    private LanguageProperties languageProperties = new LanguageProperties();

    private final UUID bookId = UUID.randomUUID();
    private final BookEntity book = BookEntity.builder()
            .id(bookId)
            .name("The Hobbit")
            .personEntity(PersonEntity.builder().name("J.R.R. Tolkien").build())
            .build();
    private final BookFoundData data = BookFoundData.builder()
            .eventType(EventType.BOOK_FOUND)
            .bookId(bookId)
            .build();

    private OpenLibraryService.BookInfo info(String description, String coverUrl, int year) {
        return new OpenLibraryService.BookInfo(description, coverUrl, year, "/works/OL1W");
    }

    @Test
    void handles() {
        assertEquals(EventType.BOOK_FOUND, subject.handles());
    }

    @Test
    void listenerThrowsOnWrongEventType() {
        BookFoundData wrongData = BookFoundData.builder()
                .eventType(EventType.FILE_SCAN_REQUESTED)
                .build();
        assertThrows(IllegalArgumentException.class, () -> subject.listener(wrongData));
    }

    @Test
    void listenerCallsHandleWithCorrectEventType() {
        when(bookRepository.findById(bookId)).thenReturn(Optional.empty());

        assertDoesNotThrow(() -> subject.listener(data));
    }

    @Test
    void handleDoesNothingWhenBookNotFound() {
        when(bookRepository.findById(bookId)).thenReturn(Optional.empty());

        subject.handle(data);

        verifyNoInteractions(imageRepository, metadataRepository, openLibraryService, imageDownloadService);
    }

    @Test
    void handleDoesNothingWhenDescriptionCoverAndOpenLibraryYearArePresent() {
        when(bookRepository.findById(bookId)).thenReturn(Optional.of(book));
        when(metadataRepository.findByBookEntityId(bookId)).thenReturn(List.of(
                MetadataEntity.builder().description("Already there").sourceUri("file:///b.epub").build(),
                MetadataEntity.builder().sourceUri("openlibrary://works/OL1W")
                        .released(LocalDate.of(1937, 1, 1)).build()));
        when(imageRepository.findByBookEntityId(bookId)).thenReturn(List.of(ImageEntity.builder().build()));

        subject.handle(data);

        verifyNoInteractions(openLibraryService, imageDownloadService, serverEventService);
        verify(metadataRepository, never()).save(any());
    }

    /** Description and cover exist, but the original-year backfill still has to run. */
    @Test
    void handleStillRunsWhenOnlyTheOpenLibraryYearIsMissing() {
        when(bookRepository.findById(bookId)).thenReturn(Optional.of(book));
        when(metadataRepository.findByBookEntityId(bookId)).thenReturn(List.of(
                MetadataEntity.builder().description("Local").sourceUri("file:///b.nfo")
                        .released(LocalDate.of(2011, 1, 1)).build()));
        when(imageRepository.findByBookEntityId(bookId)).thenReturn(List.of(ImageEntity.builder().build()));
        when(mediaFileRepository.findByBookEntityId(bookId)).thenReturn(List.of());
        when(openLibraryService.getBookInfo("The Hobbit", "J.R.R. Tolkien", List.of()))
                .thenReturn(Optional.of(info(null, null, 1937)));

        subject.handle(data);

        ArgumentCaptor<MetadataEntity> captor = ArgumentCaptor.forClass(MetadataEntity.class);
        verify(metadataRepository).save(captor.capture());
        assertEquals("openlibrary://works/OL1W", captor.getValue().getSourceUri());
        assertEquals(LocalDate.of(1937, 1, 1), captor.getValue().getReleased());
        verify(scannerHelperService).refreshBookReleaseYear(book);
    }

    @Test
    void handleDoesNothingWhenOpenLibraryHasNoMatch() {
        when(bookRepository.findById(bookId)).thenReturn(Optional.of(book));
        when(metadataRepository.findByBookEntityId(bookId)).thenReturn(List.of());
        when(imageRepository.findByBookEntityId(bookId)).thenReturn(List.of());
        when(mediaFileRepository.findByBookEntityId(bookId)).thenReturn(List.of());
        when(openLibraryService.getBookInfo("The Hobbit", "J.R.R. Tolkien", List.of()))
                .thenReturn(Optional.empty());

        subject.handle(data);

        verifyNoInteractions(imageDownloadService, serverEventService);
        verify(metadataRepository, never()).save(any());
    }

    @Test
    void handlePassesTheEpubIsbnsToOpenLibrary() {
        MediaFileEntity epub = MediaFileEntity.builder().path("/b.epub").size(1L).build();
        epub.setIsbn("9789025747855");
        MediaFileEntity epubWithoutIsbn = MediaFileEntity.builder().path("/c.epub").size(1L).build();
        when(bookRepository.findById(bookId)).thenReturn(Optional.of(book));
        when(metadataRepository.findByBookEntityId(bookId)).thenReturn(List.of());
        when(imageRepository.findByBookEntityId(bookId)).thenReturn(List.of());
        when(mediaFileRepository.findByBookEntityId(bookId)).thenReturn(List.of(epub, epubWithoutIsbn));
        when(openLibraryService.getBookInfo("The Hobbit", "J.R.R. Tolkien", List.of("9789025747855")))
                .thenReturn(Optional.empty());

        subject.handle(data);

        verify(openLibraryService).getBookInfo("The Hobbit", "J.R.R. Tolkien", List.of("9789025747855"));
    }

    @Test
    void handleDownloadsCoverAndSavesItsOwnMetadataRow() throws IOException {
        when(bookRepository.findById(bookId)).thenReturn(Optional.of(book));
        when(metadataRepository.findByBookEntityId(bookId)).thenReturn(List.of());
        when(imageRepository.findByBookEntityId(bookId)).thenReturn(List.of());
        when(mediaFileRepository.findByBookEntityId(bookId)).thenReturn(List.of());
        when(openLibraryService.getBookInfo("The Hobbit", "J.R.R. Tolkien", List.of()))
                .thenReturn(Optional.of(info("A hobbit goes on an adventure.", COVER_URL, 1937)));

        subject.handle(data);

        verify(imageDownloadService).downloadAndSave(
                COVER_URL, ImageType.COVER, "eng",
                "OpenLibrary://" + COVER_URL,
                new ImageSave.MediaEntityRef(null, null, null, null, null, book));
        ArgumentCaptor<MetadataEntity> captor = ArgumentCaptor.forClass(MetadataEntity.class);
        verify(metadataRepository).save(captor.capture());
        MetadataEntity saved = captor.getValue();
        assertEquals("A hobbit goes on an adventure.", saved.getDescription());
        assertEquals(book, saved.getBookEntity());
        assertEquals("openlibrary://works/OL1W", saved.getSourceUri());
        assertEquals(LocalDate.of(1937, 1, 1), saved.getReleased());
        assertEquals("eng", saved.getLanguage());
        verify(serverEventService).createSearchIndexEvent(SearchEntityType.BOOK, bookId);
    }

    /** Local nfo/epub rows are never deleted or rewritten; Open Library upserts its own row. */
    @Test
    void handleNeverTouchesLocalMetadataRows() {
        MetadataEntity localRow = MetadataEntity.builder()
                .title("The Hobbit").sourceUri("file:///books/The Hobbit/album.nfo").build();
        when(bookRepository.findById(bookId)).thenReturn(Optional.of(book));
        when(metadataRepository.findByBookEntityId(bookId)).thenReturn(List.of(localRow));
        when(imageRepository.findByBookEntityId(bookId)).thenReturn(List.of(ImageEntity.builder().build()));
        when(mediaFileRepository.findByBookEntityId(bookId)).thenReturn(List.of());
        when(openLibraryService.getBookInfo("The Hobbit", "J.R.R. Tolkien", List.of()))
                .thenReturn(Optional.of(info("Open Library description", null, 1937)));

        subject.handle(data);

        verify(metadataRepository, never()).deleteAll(any());
        verify(metadataRepository, never()).delete(any());
        // Only the Open Library row is written; the local nfo row keeps its own data untouched.
        ArgumentCaptor<MetadataEntity> captor = ArgumentCaptor.forClass(MetadataEntity.class);
        verify(metadataRepository).save(captor.capture());
        assertEquals("openlibrary://works/OL1W", captor.getValue().getSourceUri());
        assertEquals("file:///books/The Hobbit/album.nfo", localRow.getSourceUri());
        assertEquals(null, localRow.getDescription());
    }

    /** A second run updates the existing openlibrary:// row instead of adding another. */
    @Test
    void handleUpsertsItsOwnRowOnASecondRun() {
        MetadataEntity olRow = MetadataEntity.builder()
                .sourceUri("openlibrary://works/OL1W").language("eng").build();
        when(bookRepository.findById(bookId)).thenReturn(Optional.of(book));
        when(metadataRepository.findByBookEntityId(bookId)).thenReturn(List.of(olRow));
        when(imageRepository.findByBookEntityId(bookId)).thenReturn(List.of(ImageEntity.builder().build()));
        when(mediaFileRepository.findByBookEntityId(bookId)).thenReturn(List.of());
        when(openLibraryService.getBookInfo("The Hobbit", "J.R.R. Tolkien", List.of()))
                .thenReturn(Optional.of(info("A description", null, 1937)));

        subject.handle(data);

        verify(metadataRepository).save(olRow);
        assertEquals("A description", olRow.getDescription());
        assertEquals(LocalDate.of(1937, 1, 1), olRow.getReleased());
    }

    @Test
    void handleSwallowsCoverDownloadFailureAndStillSavesMetadata() throws IOException {
        when(bookRepository.findById(bookId)).thenReturn(Optional.of(book));
        when(metadataRepository.findByBookEntityId(bookId)).thenReturn(List.of());
        when(imageRepository.findByBookEntityId(bookId)).thenReturn(List.of());
        when(mediaFileRepository.findByBookEntityId(bookId)).thenReturn(List.of());
        when(openLibraryService.getBookInfo("The Hobbit", "J.R.R. Tolkien", List.of()))
                .thenReturn(Optional.of(info("A description", COVER_URL, 1937)));
        doThrow(new IOException("download failed"))
                .when(imageDownloadService).downloadAndSave(anyString(), any(), anyString(), anyString(), any());

        assertDoesNotThrow(() -> subject.handle(data));

        verify(metadataRepository).save(any(MetadataEntity.class));
    }

    @Test
    void handleSkipsCoverWhenImageAlreadyExists() throws IOException {
        when(bookRepository.findById(bookId)).thenReturn(Optional.of(book));
        when(metadataRepository.findByBookEntityId(bookId)).thenReturn(List.of());
        when(imageRepository.findByBookEntityId(bookId)).thenReturn(List.of(ImageEntity.builder().build()));
        when(mediaFileRepository.findByBookEntityId(bookId)).thenReturn(List.of());
        when(openLibraryService.getBookInfo("The Hobbit", "J.R.R. Tolkien", List.of()))
                .thenReturn(Optional.of(info("A description", COVER_URL, 1937)));

        subject.handle(data);

        verify(imageDownloadService, never()).downloadAndSave(anyString(), any(), anyString(), anyString(), any());
        verify(metadataRepository).save(any(MetadataEntity.class));
    }

    private BookEntity seriesBook(Double seriesIndex) {
        return BookEntity.builder()
                .id(bookId)
                .name("De Grijze Jager - Losgeld voor Erak")
                .title("Losgeld voor Erak")
                .seriesIndex(seriesIndex)
                .seriesEntity(SeriesEntity.builder().name("De Grijze Jager").build())
                .personEntity(PersonEntity.builder().name("John Flanagan").build())
                .build();
    }

    private List<MetadataEntity> completeMetadata() {
        return List.of(
                MetadataEntity.builder().description("Already there").sourceUri("file:///b.epub").build(),
                MetadataEntity.builder().sourceUri("openlibrary://works/OL1W")
                        .released(LocalDate.of(2009, 1, 1)).build());
    }

    /** A series book with an unknown position still consults Wikidata when Open Library is done. */
    @Test
    void handleFillsSeriesIndexAndOriginalYearFromWikidata() {
        BookEntity bookInSeries = seriesBook(null);
        when(bookRepository.findById(bookId)).thenReturn(Optional.of(bookInSeries));
        when(metadataRepository.findByBookEntityId(bookId)).thenReturn(completeMetadata());
        when(imageRepository.findByBookEntityId(bookId)).thenReturn(List.of(ImageEntity.builder().build()));
        when(wikidataBookSeriesService.findBookInSeries("Losgeld voor Erak", "De Grijze Jager", List.of("en", "nl")))
                .thenReturn(Optional.of(new WikidataBookSeriesService.BookSeriesInfo("Q3497559", 7.0, 2007)));

        subject.handle(data);

        assertEquals(7.0, bookInSeries.getSeriesIndex());
        verify(bookRepository).save(bookInSeries);
        ArgumentCaptor<MetadataEntity> captor = ArgumentCaptor.forClass(MetadataEntity.class);
        verify(metadataRepository).save(captor.capture());
        assertEquals("wikidata://Q3497559", captor.getValue().getSourceUri());
        assertEquals(LocalDate.of(2007, 1, 1), captor.getValue().getReleased());
        verify(scannerHelperService).refreshBookReleaseYear(bookInSeries);
        verify(serverEventService).createSearchIndexEvent(SearchEntityType.BOOK, bookId);
        verifyNoInteractions(openLibraryService);
    }

    /** The epub-provided index is authoritative: Wikidata only contributes the original year. */
    @Test
    void handleNeverOverwritesAnExistingSeriesIndex() {
        BookEntity bookInSeries = seriesBook(7.0);
        when(bookRepository.findById(bookId)).thenReturn(Optional.of(bookInSeries));
        when(metadataRepository.findByBookEntityId(bookId)).thenReturn(completeMetadata());
        when(imageRepository.findByBookEntityId(bookId)).thenReturn(List.of(ImageEntity.builder().build()));
        when(wikidataBookSeriesService.findBookInSeries("Losgeld voor Erak", "De Grijze Jager", List.of("en", "nl")))
                .thenReturn(Optional.of(new WikidataBookSeriesService.BookSeriesInfo("Q3497559", 8.0, 2007)));

        subject.handle(data);

        assertEquals(7.0, bookInSeries.getSeriesIndex());
        verify(bookRepository, never()).save(any());
        ArgumentCaptor<MetadataEntity> captor = ArgumentCaptor.forClass(MetadataEntity.class);
        verify(metadataRepository).save(captor.capture());
        assertEquals("wikidata://Q3497559", captor.getValue().getSourceUri());
    }

    /** A resolved wikidata:// year row makes the whole handler a no-op for a complete series book. */
    @Test
    void handleDoesNothingWhenWikidataYearAndIndexArePresent() {
        BookEntity bookInSeries = seriesBook(7.0);
        when(bookRepository.findById(bookId)).thenReturn(Optional.of(bookInSeries));
        when(metadataRepository.findByBookEntityId(bookId)).thenReturn(List.of(
                MetadataEntity.builder().description("Already there").sourceUri("file:///b.epub").build(),
                MetadataEntity.builder().sourceUri("openlibrary://works/OL1W")
                        .released(LocalDate.of(2009, 1, 1)).build(),
                MetadataEntity.builder().sourceUri("wikidata://Q3497559")
                        .released(LocalDate.of(2007, 1, 1)).build()));
        when(imageRepository.findByBookEntityId(bookId)).thenReturn(List.of(ImageEntity.builder().build()));

        subject.handle(data);

        verifyNoInteractions(openLibraryService, wikidataBookSeriesService, serverEventService);
        verify(metadataRepository, never()).save(any());
    }

    /** No Wikidata match: nothing is written, and the next analyze retries. */
    @Test
    void handleLeavesTheBookAloneOnAWikidataMiss() {
        BookEntity bookInSeries = seriesBook(null);
        when(bookRepository.findById(bookId)).thenReturn(Optional.of(bookInSeries));
        when(metadataRepository.findByBookEntityId(bookId)).thenReturn(completeMetadata());
        when(imageRepository.findByBookEntityId(bookId)).thenReturn(List.of(ImageEntity.builder().build()));
        when(wikidataBookSeriesService.findBookInSeries("Losgeld voor Erak", "De Grijze Jager", List.of("en", "nl")))
                .thenReturn(Optional.empty());

        subject.handle(data);

        assertEquals(null, bookInSeries.getSeriesIndex());
        verify(metadataRepository, never()).save(any());
        verifyNoInteractions(serverEventService);
    }

    // ===== series discovery for series-less books =====

    private final UUID authorId = UUID.randomUUID();

    private BookEntity serieslessBook() {
        return BookEntity.builder()
                .id(bookId)
                .name("Harry Potter en de Orde van de Feniks")
                .personEntity(PersonEntity.builder().id(authorId).name("J.K. Rowling").build())
                .build();
    }

    /** An audiobook-only book without a series joins an existing series of its author. */
    @Test
    void handleDiscoversTheSeriesOfASerieslessBook() {
        BookEntity seriesless = serieslessBook();
        SeriesEntity harryPotter = SeriesEntity.builder().name("Harry Potter").build();
        when(bookRepository.findById(bookId)).thenReturn(Optional.of(seriesless));
        when(metadataRepository.findByBookEntityId(bookId)).thenReturn(completeMetadata());
        when(imageRepository.findByBookEntityId(bookId)).thenReturn(List.of(ImageEntity.builder().build()));
        when(seriesRepository.findByPersonEntityId(authorId)).thenReturn(List.of(harryPotter));
        when(wikidataBookSeriesService.discoverSeries(
                "Harry Potter en de Orde van de Feniks", "J.K. Rowling",
                List.of("Harry Potter"), List.of("en", "nl")))
                .thenReturn(Optional.of(new WikidataBookSeriesService.DiscoveredSeries(
                        "Q102225", "Harry Potter", 5.0, 2003)));

        subject.handle(data);

        assertEquals(harryPotter, seriesless.getSeriesEntity());
        assertEquals(5.0, seriesless.getSeriesIndex());
        verify(bookSeriesService).updateDisplayTitle(seriesless);
        ArgumentCaptor<MetadataEntity> captor = ArgumentCaptor.forClass(MetadataEntity.class);
        verify(metadataRepository).save(captor.capture());
        assertEquals("wikidata://Q102225", captor.getValue().getSourceUri());
        assertEquals(LocalDate.of(2003, 1, 1), captor.getValue().getReleased());
        verify(scannerHelperService).refreshBookReleaseYear(seriesless);
        verify(serverEventService).createSearchIndexEvent(SearchEntityType.BOOK, bookId);
    }

    /** Discovery never creates a series: without candidates Wikidata is not even consulted. */
    @Test
    void handleSkipsDiscoveryWhenTheAuthorHasNoSeries() {
        BookEntity seriesless = serieslessBook();
        when(bookRepository.findById(bookId)).thenReturn(Optional.of(seriesless));
        when(metadataRepository.findByBookEntityId(bookId)).thenReturn(completeMetadata());
        when(imageRepository.findByBookEntityId(bookId)).thenReturn(List.of(ImageEntity.builder().build()));
        when(seriesRepository.findByPersonEntityId(authorId)).thenReturn(List.of());

        subject.handle(data);

        verifyNoInteractions(wikidataBookSeriesService, bookSeriesService);
        assertEquals(null, seriesless.getSeriesEntity());
    }

    /** A discovery miss leaves the book alone; the next analyze retries. */
    @Test
    void handleLeavesTheBookAloneOnADiscoveryMiss() {
        BookEntity seriesless = serieslessBook();
        SeriesEntity harryPotter = SeriesEntity.builder().name("Harry Potter").build();
        when(bookRepository.findById(bookId)).thenReturn(Optional.of(seriesless));
        when(metadataRepository.findByBookEntityId(bookId)).thenReturn(completeMetadata());
        when(imageRepository.findByBookEntityId(bookId)).thenReturn(List.of(ImageEntity.builder().build()));
        when(seriesRepository.findByPersonEntityId(authorId)).thenReturn(List.of(harryPotter));
        when(wikidataBookSeriesService.discoverSeries(anyString(), anyString(), any(), any()))
                .thenReturn(Optional.empty());

        subject.handle(data);

        assertEquals(null, seriesless.getSeriesEntity());
        verifyNoInteractions(bookSeriesService, serverEventService);
        verify(metadataRepository, never()).save(any());
    }

    /** An unknown first-publish year leaves released null, so a later analyze retries it. */
    @Test
    void handleLeavesReleasedNullWhenOpenLibraryHasNoYear() {
        lenient().when(bookRepository.findById(bookId)).thenReturn(Optional.of(book));
        when(metadataRepository.findByBookEntityId(bookId)).thenReturn(List.of());
        when(imageRepository.findByBookEntityId(bookId)).thenReturn(List.of());
        when(mediaFileRepository.findByBookEntityId(bookId)).thenReturn(List.of());
        when(openLibraryService.getBookInfo("The Hobbit", "J.R.R. Tolkien", List.of()))
                .thenReturn(Optional.of(info("A description", null, 0)));

        subject.handle(data);

        ArgumentCaptor<MetadataEntity> captor = ArgumentCaptor.forClass(MetadataEntity.class);
        verify(metadataRepository).save(captor.capture());
        assertEquals(null, captor.getValue().getReleased());
    }
}
