package app.ister.disk.scanner.scanners;

import app.ister.core.entity.BookEntity;
import app.ister.core.entity.DirectoryEntity;
import app.ister.core.entity.LibraryEntity;
import app.ister.core.entity.MediaFileEntity;
import app.ister.core.entity.PersonEntity;
import app.ister.core.enums.LibraryType;
import app.ister.core.eventdata.EpubFileFoundData;
import app.ister.core.repository.MediaFileRepository;
import app.ister.core.service.MessageSender;
import app.ister.core.service.ScannerHelperService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.nio.file.Path;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class EpubScannerTest {

    private static final Path EPUB_PATH = Path.of("/books/Author/Book (2020).epub");

    @Mock
    private ScannerHelperService scannerHelperService;
    @Mock
    private MediaFileRepository mediaFileRepository;
    @Mock
    private MessageSender messageSender;

    @InjectMocks
    private EpubScanner subject;

    private DirectoryEntity bookDir;
    private LibraryEntity library;
    private PersonEntity author;
    private BookEntity book;
    private final UUID bookId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        TransactionSynchronizationManager.initSynchronization();
        library = LibraryEntity.builder().libraryType(LibraryType.BOOK).name("Books").build();
        bookDir = DirectoryEntity.builder()
                .id(UUID.randomUUID())
                .libraryEntity(library)
                .name("books-dir")
                .path("/books")
                .build();
        author = PersonEntity.builder().libraryEntity(library).name("Author").build();
        book = BookEntity.builder().id(bookId).libraryEntity(library).personEntity(author).name("Book").build();
        lenient().when(scannerHelperService.getOrCreatePerson(library, "Author", 0)).thenReturn(author);
        lenient().when(scannerHelperService.getOrCreateBook(library, author, "Book", 2020)).thenReturn(book);
    }

    @AfterEach
    void tearDown() {
        TransactionSynchronizationManager.clearSynchronization();
    }

    // ========== analyzable ==========

    @Test
    void analyzableReturnsTrueForEpubFile() {
        assertTrue(subject.analyzable(EPUB_PATH, true, 1000));
    }

    @Test
    void analyzableReturnsFalseForNonEpubFile() {
        assertFalse(subject.analyzable(Path.of("/books/Author/Book/001_Chapter.mp3"), true, 1000));
    }

    @Test
    void analyzableReturnsFalseForDirectory() {
        assertFalse(subject.analyzable(Path.of("/books/Author"), false, 0));
    }

    @Test
    void analyzableWithDirectoryReturnsTrueForEpubInBookLibrary() {
        assertTrue(subject.analyzable(EPUB_PATH, true, bookDir));
    }

    @Test
    void analyzableWithDirectoryReturnsFalseForNonRegularFile() {
        assertFalse(subject.analyzable(EPUB_PATH, false, bookDir));
    }

    @Test
    void analyzableWithDirectoryReturnsFalseWhenNoLibrary() {
        DirectoryEntity dir = DirectoryEntity.builder().path("/books").build();
        assertFalse(subject.analyzable(EPUB_PATH, true, dir));
    }

    @Test
    void analyzableWithDirectoryReturnsFalseForNonBookLibrary() {
        LibraryEntity music = LibraryEntity.builder().libraryType(LibraryType.MUSIC).name("Music").build();
        DirectoryEntity dir = DirectoryEntity.builder().libraryEntity(music).path("/books").build();
        assertFalse(subject.analyzable(EPUB_PATH, true, dir));
    }

    @Test
    void analyzableWithDirectoryReturnsFalseForAudioFile() {
        assertFalse(subject.analyzable(Path.of("/books/Author/Book/001_Chapter.mp3"), true, bookDir));
    }

    // ========== analyze ==========

    @Test
    void analyzeCreatesMediaFileAndSendsEventAfterCommit() {
        when(mediaFileRepository.findByDirectoryEntityAndPath(bookDir, EPUB_PATH.toString()))
                .thenReturn(Optional.empty());

        var result = subject.analyze(bookDir, EPUB_PATH, true, 4200);

        assertTrue(result.isPresent());
        assertEquals(book, result.get());
        ArgumentCaptor<MediaFileEntity> saved = ArgumentCaptor.forClass(MediaFileEntity.class);
        verify(mediaFileRepository).save(saved.capture());
        assertEquals(EPUB_PATH.toString(), saved.getValue().getPath());
        assertEquals(book, saved.getValue().getBookEntity());

        verifyNoInteractions(messageSender);
        TransactionSynchronizationManager.getSynchronizations().forEach(TransactionSynchronization::afterCommit);
        ArgumentCaptor<EpubFileFoundData> data = ArgumentCaptor.forClass(EpubFileFoundData.class);
        verify(messageSender).sendEpubFileFound(data.capture(), eq("books-dir"));
        assertEquals(bookId, data.getValue().getBookEntityUUID());
        assertEquals(EPUB_PATH.toString(), data.getValue().getPath());
    }

    @Test
    void analyzeReturnsEmptyForNonEpubPath() {
        var result = subject.analyze(bookDir, Path.of("/books/Author/Book/cover.jpg"), true, 100);

        assertTrue(result.isEmpty());
        verify(mediaFileRepository, never()).save(any());
    }

    @Test
    void analyzeDoesNotResendEventForUnchangedExistingFile() {
        MediaFileEntity existing = MediaFileEntity.builder()
                .path(EPUB_PATH.toString())
                .bookEntity(book)
                .build();
        when(mediaFileRepository.findByDirectoryEntityAndPath(bookDir, EPUB_PATH.toString()))
                .thenReturn(Optional.of(existing));

        var result = subject.analyze(bookDir, EPUB_PATH, true, 4200);

        assertTrue(result.isPresent());
        verify(mediaFileRepository, never()).save(any());
        assertTrue(TransactionSynchronizationManager.getSynchronizations().isEmpty());
    }

    @Test
    void analyzeFixesWrongBookAssociation() {
        BookEntity otherBook = BookEntity.builder()
                .id(UUID.randomUUID()).libraryEntity(library).personEntity(author).name("Other").build();
        MediaFileEntity existing = MediaFileEntity.builder()
                .path(EPUB_PATH.toString())
                .bookEntity(otherBook)
                .build();
        when(mediaFileRepository.findByDirectoryEntityAndPath(bookDir, EPUB_PATH.toString()))
                .thenReturn(Optional.of(existing));

        subject.analyze(bookDir, EPUB_PATH, true, 4200);

        verify(mediaFileRepository).save(existing);
        assertEquals(book, existing.getBookEntity());
        TransactionSynchronizationManager.getSynchronizations().forEach(TransactionSynchronization::afterCommit);
        verify(messageSender).sendEpubFileFound(any(EpubFileFoundData.class), eq("books-dir"));
    }

    @Test
    void analyzeAttachesEpubToBookWhenExistingFileHasNoBook() {
        MediaFileEntity existing = MediaFileEntity.builder()
                .path(EPUB_PATH.toString())
                .bookEntity(null)
                .build();
        when(mediaFileRepository.findByDirectoryEntityAndPath(bookDir, EPUB_PATH.toString()))
                .thenReturn(Optional.of(existing));

        subject.analyze(bookDir, EPUB_PATH, true, 4200);

        verify(mediaFileRepository).save(existing);
        assertEquals(book, existing.getBookEntity());
    }
}
