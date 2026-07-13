package app.ister.worker.events.bookfound;

import app.ister.core.entity.BookEntity;
import app.ister.core.entity.ImageEntity;
import app.ister.core.entity.MetadataEntity;
import app.ister.core.entity.PersonEntity;
import app.ister.core.enums.EventType;
import app.ister.core.enums.ImageType;
import app.ister.core.enums.SearchEntityType;
import app.ister.core.eventdata.BookFoundData;
import app.ister.core.repository.BookRepository;
import app.ister.core.repository.ImageRepository;
import app.ister.core.repository.MetadataRepository;
import app.ister.core.service.ServerEventService;
import app.ister.worker.events.openlibrary.OpenLibraryService;
import app.ister.worker.events.tmdbmetadata.ImageDownloadService;
import app.ister.worker.events.tmdbmetadata.ImageSave;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.time.LocalDate;
import java.time.Month;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
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
    private OpenLibraryService openLibraryService;

    @Mock
    private ImageDownloadService imageDownloadService;

    @Mock
    private ServerEventService serverEventService;

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
    void handleDoesNothingWhenCoverAndDescriptionAlreadyPresent() {
        when(bookRepository.findById(bookId)).thenReturn(Optional.of(book));
        when(metadataRepository.findByBookEntityId(bookId))
                .thenReturn(List.of(MetadataEntity.builder().description("Already there").build()));
        when(imageRepository.findByBookEntityId(bookId)).thenReturn(List.of(ImageEntity.builder().build()));

        subject.handle(data);

        verifyNoInteractions(openLibraryService, imageDownloadService, serverEventService);
        verify(metadataRepository, never()).save(any());
    }

    @Test
    void handleDoesNothingWhenOpenLibraryHasNoMatch() {
        when(bookRepository.findById(bookId)).thenReturn(Optional.of(book));
        when(metadataRepository.findByBookEntityId(bookId)).thenReturn(List.of());
        when(imageRepository.findByBookEntityId(bookId)).thenReturn(List.of());
        when(openLibraryService.getBookInfo("The Hobbit", "J.R.R. Tolkien")).thenReturn(Optional.empty());

        subject.handle(data);

        verifyNoInteractions(imageDownloadService, serverEventService);
        verify(metadataRepository, never()).save(any());
    }

    @Test
    void handleDownloadsCoverAndSavesDescription() throws IOException {
        when(bookRepository.findById(bookId)).thenReturn(Optional.of(book));
        when(metadataRepository.findByBookEntityId(bookId)).thenReturn(List.of());
        when(imageRepository.findByBookEntityId(bookId)).thenReturn(List.of());
        when(openLibraryService.getBookInfo("The Hobbit", "J.R.R. Tolkien"))
                .thenReturn(Optional.of(new OpenLibraryService.BookInfo("A hobbit goes on an adventure.", COVER_URL, 1937)));

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
        assertEquals("openlibrary://book/The Hobbit", saved.getSourceUri());
        verify(serverEventService).createSearchIndexEvent(SearchEntityType.BOOK, bookId);
    }

    @Test
    void handleSwallowsCoverDownloadFailureAndStillSavesDescription() throws IOException {
        when(bookRepository.findById(bookId)).thenReturn(Optional.of(book));
        when(metadataRepository.findByBookEntityId(bookId)).thenReturn(List.of());
        when(imageRepository.findByBookEntityId(bookId)).thenReturn(List.of());
        when(openLibraryService.getBookInfo("The Hobbit", "J.R.R. Tolkien"))
                .thenReturn(Optional.of(new OpenLibraryService.BookInfo("A description", COVER_URL, 1937)));
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
        when(openLibraryService.getBookInfo("The Hobbit", "J.R.R. Tolkien"))
                .thenReturn(Optional.of(new OpenLibraryService.BookInfo("A description", COVER_URL, 1937)));

        subject.handle(data);

        verify(imageDownloadService, never()).downloadAndSave(anyString(), any(), anyString(), anyString(), any());
        verify(metadataRepository).save(any(MetadataEntity.class));
    }

    @Test
    void handleSkipsDescriptionWhenAlreadyPresent() throws IOException {
        when(bookRepository.findById(bookId)).thenReturn(Optional.of(book));
        when(metadataRepository.findByBookEntityId(bookId))
                .thenReturn(List.of(MetadataEntity.builder().description("Already there").build()));
        when(imageRepository.findByBookEntityId(bookId)).thenReturn(List.of());
        when(openLibraryService.getBookInfo("The Hobbit", "J.R.R. Tolkien"))
                .thenReturn(Optional.of(new OpenLibraryService.BookInfo("A description", COVER_URL, 1937)));

        subject.handle(data);

        verify(imageDownloadService).downloadAndSave(anyString(), any(), anyString(), anyString(), any());
        verify(metadataRepository, never()).save(any());
        verifyNoInteractions(serverEventService);
    }

    @Test
    void handleMergesDescriptionIntoExistingMetadataWithoutDescription() {
        MetadataEntity existing = MetadataEntity.builder()
                .title("The Hobbit")
                .released(LocalDate.of(1937, Month.SEPTEMBER, 21))
                .genre("Fantasy")
                .language("eng")
                .sourceUri("opf://book")
                .build();
        when(bookRepository.findById(bookId)).thenReturn(Optional.of(book));
        when(metadataRepository.findByBookEntityId(bookId)).thenReturn(List.of(existing));
        when(imageRepository.findByBookEntityId(bookId)).thenReturn(List.of(ImageEntity.builder().build()));
        when(openLibraryService.getBookInfo("The Hobbit", "J.R.R. Tolkien"))
                .thenReturn(Optional.of(new OpenLibraryService.BookInfo("Open Library description", null, 1937)));

        subject.handle(data);

        verify(metadataRepository).deleteAll(List.of(existing));
        ArgumentCaptor<MetadataEntity> captor = ArgumentCaptor.forClass(MetadataEntity.class);
        verify(metadataRepository).save(captor.capture());
        MetadataEntity saved = captor.getValue();
        assertEquals("The Hobbit", saved.getTitle());
        assertEquals("Open Library description", saved.getDescription());
        assertEquals(LocalDate.of(1937, Month.SEPTEMBER, 21), saved.getReleased());
        assertEquals("Fantasy", saved.getGenre());
        assertEquals("eng", saved.getLanguage());
        assertEquals("opf://book", saved.getSourceUri());
        assertEquals(book, saved.getBookEntity());
        verify(serverEventService).createSearchIndexEvent(SearchEntityType.BOOK, bookId);
    }

    @Test
    void handleDoesNotDownloadCoverWhenOpenLibraryHasNone() throws IOException {
        when(bookRepository.findById(bookId)).thenReturn(Optional.of(book));
        when(metadataRepository.findByBookEntityId(bookId)).thenReturn(List.of());
        when(imageRepository.findByBookEntityId(bookId)).thenReturn(List.of());
        when(openLibraryService.getBookInfo("The Hobbit", "J.R.R. Tolkien"))
                .thenReturn(Optional.of(new OpenLibraryService.BookInfo(null, null, 1937)));

        subject.handle(data);

        verify(imageDownloadService, never()).downloadAndSave(anyString(), any(), anyString(), anyString(), any());
        verify(metadataRepository, never()).save(any());
    }
}
