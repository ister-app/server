package app.ister.disk.events.epubfilefound;

import app.ister.core.entity.BookEntity;
import app.ister.core.entity.DirectoryEntity;
import app.ister.core.entity.ImageEntity;
import app.ister.core.entity.LibraryEntity;
import app.ister.core.entity.MediaFileEntity;
import app.ister.core.entity.MetadataEntity;
import app.ister.core.entity.NodeEntity;
import app.ister.core.entity.PersonEntity;
import app.ister.core.enums.DirectoryType;
import app.ister.core.enums.EventType;
import app.ister.core.enums.ImageType;
import app.ister.core.enums.LibraryType;
import app.ister.core.enums.SearchEntityType;
import app.ister.core.eventdata.EpubFileFoundData;
import app.ister.core.eventdata.ImageFoundData;
import app.ister.core.repository.BookRepository;
import app.ister.core.repository.DirectoryRepository;
import app.ister.core.repository.ImageRepository;
import app.ister.core.repository.MediaFileRepository;
import app.ister.core.repository.MetadataRepository;
import app.ister.core.service.BookSeriesService;
import app.ister.core.service.MessageSender;
import app.ister.core.service.ScannerHelperService;
import app.ister.core.service.ServerEventService;
import app.ister.disk.epub.EpubInfo;
import app.ister.disk.epub.EpubParser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class HandleEpubFileFoundTest {

    private static final byte[] COVER = "cover-bytes".getBytes(StandardCharsets.UTF_8);

    @Mock
    private DirectoryRepository directoryRepository;
    @Mock
    private MediaFileRepository mediaFileRepository;
    @Mock
    private MetadataRepository metadataRepository;
    @Mock
    private BookRepository bookRepository;
    @Mock
    private ImageRepository imageRepository;
    @Mock
    private EpubParser epubParser;
    @Mock
    private MessageSender messageSender;
    @Mock
    private ServerEventService serverEventService;
    @Mock
    private ScannerHelperService scannerHelperService;
    @Mock
    private BookSeriesService bookSeriesService;

    @InjectMocks
    private HandleEpubFileFound subject;

    @TempDir
    Path cachePath;

    private final UUID directoryId = UUID.randomUUID();
    private final UUID mediaFileId = UUID.randomUUID();
    private final UUID bookId = UUID.randomUUID();
    private final UUID cacheDirId = UUID.randomUUID();
    private static final String EPUB_PATH = "/books/Author/Book.epub";

    private NodeEntity node;
    private DirectoryEntity libraryDir;
    private DirectoryEntity cacheDir;
    private MediaFileEntity mediaFile;
    private BookEntity book;

    @BeforeEach
    void setUp() {
        node = NodeEntity.builder().name("node1").build();
        LibraryEntity library = LibraryEntity.builder().libraryType(LibraryType.BOOK).name("Books").build();
        libraryDir = DirectoryEntity.builder()
                .id(directoryId)
                .nodeEntity(node)
                .libraryEntity(library)
                .name("books-dir")
                .path("/books")
                .directoryType(DirectoryType.LIBRARY)
                .build();
        cacheDir = DirectoryEntity.builder()
                .id(cacheDirId)
                .nodeEntity(node)
                .name("node1-cache-directory")
                .path(cachePath.toString())
                .directoryType(DirectoryType.CACHE)
                .build();

        PersonEntity author = PersonEntity.builder().libraryEntity(library).name("Author").build();
        book = BookEntity.builder().id(bookId).libraryEntity(library).personEntity(author).name("Book").build();
        mediaFile = MediaFileEntity.builder().path(EPUB_PATH).size(100L).build();
        mediaFile.setId(mediaFileId);

        lenient().when(directoryRepository.findById(directoryId)).thenReturn(Optional.of(libraryDir));
        lenient().when(mediaFileRepository.findById(mediaFileId)).thenReturn(Optional.of(mediaFile));
        lenient().when(bookRepository.findById(bookId)).thenReturn(Optional.of(book));
    }

    private EpubFileFoundData event() {
        return EpubFileFoundData.builder()
                .eventType(EventType.EPUB_FILE_FOUND)
                .directoryEntityUUID(directoryId)
                .mediaFileEntityUUID(mediaFileId)
                .bookEntityUUID(bookId)
                .path(EPUB_PATH)
                .build();
    }

    private EpubInfo info(String coverEntry, boolean mediaOverlays, long duration) {
        return new EpubInfo("Book Title", "Author", "nl", "A description", 2020, null, null, null,
                coverEntry, mediaOverlays, duration);
    }

    @Test
    void handlesReturnsCorrectEventType() {
        assertEquals(EventType.EPUB_FILE_FOUND, subject.handles());
    }

    @Test
    void listenerThrowsOnWrongEventType() {
        EpubFileFoundData data = EpubFileFoundData.builder().eventType(EventType.AUDIO_FILE_FOUND).build();
        assertThrows(IllegalArgumentException.class, () -> subject.listener(data));
    }

    @Test
    void skipsWhenMediaFileMissing() {
        when(mediaFileRepository.findById(mediaFileId)).thenReturn(Optional.empty());

        subject.handle(event());

        verifyNoInteractions(epubParser, metadataRepository, messageSender);
        verify(mediaFileRepository, never()).save(any(MediaFileEntity.class));
    }

    @Test
    void skipsWhenBookMissing() {
        when(bookRepository.findById(bookId)).thenReturn(Optional.empty());

        subject.handle(event());

        verifyNoInteractions(epubParser, metadataRepository, messageSender);
    }

    @Test
    void skipsWhenEpubCannotBeParsed() {
        when(epubParser.parse(Path.of(EPUB_PATH))).thenReturn(Optional.empty());

        subject.handle(event());

        verify(mediaFileRepository, never()).save(any(MediaFileEntity.class));
        verifyNoInteractions(metadataRepository, messageSender);
    }

    @Test
    void storesMediaOverlaysDurationAndMetadata() {
        when(epubParser.parse(Path.of(EPUB_PATH))).thenReturn(Optional.of(info(null, true, 3_600_000L)));
        when(metadataRepository.findByBookEntityId(bookId)).thenReturn(List.of());

        subject.handle(event());

        verify(mediaFileRepository).save(mediaFile);
        assertTrue(mediaFile.getMediaOverlays());
        assertEquals(3_600_000L, mediaFile.getDurationInMilliseconds());

        ArgumentCaptor<MetadataEntity> saved = ArgumentCaptor.forClass(MetadataEntity.class);
        verify(metadataRepository).save(saved.capture());
        assertEquals("Book Title", saved.getValue().getTitle());
        assertEquals("A description", saved.getValue().getDescription());
        // The epub's own language (nl) is stored as ISO-639-3.
        assertEquals("nld", saved.getValue().getLanguage());
        assertEquals("file://" + EPUB_PATH, saved.getValue().getSourceUri());
        // The epub's dc:date year is persisted, so it can drive the display year.
        assertEquals(java.time.LocalDate.of(2020, 1, 1), saved.getValue().getReleased());
        verify(scannerHelperService).refreshBookReleaseYear(book);
        verify(serverEventService).createSearchIndexEvent(SearchEntityType.BOOK, bookId);
    }

    /**
     * A row from another source (the nfo) never blocks the epub row — they coexist, keyed on
     * sourceUri; display preference between them is the API's ordering concern.
     */
    @Test
    void addsTheEpubRowAlongsideAnNfoRow() {
        when(epubParser.parse(Path.of(EPUB_PATH))).thenReturn(Optional.of(info(null, false, 0)));
        when(metadataRepository.findByBookEntityId(bookId))
                .thenReturn(List.of(MetadataEntity.builder().bookEntity(book)
                        .sourceUri("file:///books/Author/Book/album.nfo").title("Existing").build()));

        subject.handle(event());

        ArgumentCaptor<MetadataEntity> saved = ArgumentCaptor.forClass(MetadataEntity.class);
        verify(metadataRepository).save(saved.capture());
        assertEquals("file://" + EPUB_PATH, saved.getValue().getSourceUri());
        assertEquals("Book Title", saved.getValue().getTitle());
    }

    /** Re-parsing the same epub updates its row in place instead of duplicating it. */
    @Test
    void reparsingTheSameEpubUpdatesItsOwnRow() {
        when(epubParser.parse(Path.of(EPUB_PATH))).thenReturn(Optional.of(info(null, false, 0)));
        MetadataEntity own = MetadataEntity.builder().bookEntity(book)
                .sourceUri("file://" + EPUB_PATH).title("Stale title").build();
        when(metadataRepository.findByBookEntityId(bookId)).thenReturn(List.of(own));

        subject.handle(event());

        verify(metadataRepository).save(own);
        assertEquals("Book Title", own.getTitle());
        assertEquals(java.time.LocalDate.of(2020, 1, 1), own.getReleased());
    }

    @Test
    void storesTheIsbnAndChainsBookFoundWhenItIsNew() {
        when(epubParser.parse(Path.of(EPUB_PATH))).thenReturn(Optional.of(
                new EpubInfo("T", "A", "nl", "D", 0, "9789025747855", null, null, null, false, 0)));
        when(metadataRepository.findByBookEntityId(bookId)).thenReturn(List.of());

        subject.handle(event());

        assertEquals("9789025747855", mediaFile.getIsbn());
        verify(serverEventService).createBookFoundEvent(bookId);
    }

    @Test
    void doesNotChainBookFoundWhenTheIsbnIsUnchanged() {
        mediaFile.setIsbn("9789025747855");
        when(epubParser.parse(Path.of(EPUB_PATH))).thenReturn(Optional.of(
                new EpubInfo("T", "A", "nl", "D", 0, "9789025747855", null, null, null, false, 0)));
        when(metadataRepository.findByBookEntityId(bookId)).thenReturn(List.of());

        subject.handle(event());

        verify(serverEventService, never()).createBookFoundEvent(any());
    }

    @Test
    void assignsTheSeriesFromEpubMetadata() {
        when(epubParser.parse(Path.of(EPUB_PATH))).thenReturn(Optional.of(
                new EpubInfo("T", "A", "nl", "D", 0, null, "De Grijze Jager", 1.0, null, false, 0)));
        when(metadataRepository.findByBookEntityId(bookId)).thenReturn(List.of());

        subject.handle(event());

        verify(bookSeriesService).assignFromEpub(book, "De Grijze Jager", 1.0);
    }

    @Test
    void skipsMetadataWhenEpubHasNoTitleOrDescription() {
        when(epubParser.parse(Path.of(EPUB_PATH)))
                .thenReturn(Optional.of(new EpubInfo(null, null, "en", null, 0, null, null, null, null, false, 0)));

        subject.handle(event());

        verify(mediaFileRepository).save(mediaFile);
        verify(metadataRepository, never()).save(any(MetadataEntity.class));
    }

    @Test
    void extractsCoverAndSendsImageFound() throws Exception {
        when(epubParser.parse(Path.of(EPUB_PATH))).thenReturn(Optional.of(info("OEBPS/cover.png", false, 0)));
        when(metadataRepository.findByBookEntityId(bookId)).thenReturn(List.of());
        when(imageRepository.findByBookEntityId(bookId)).thenReturn(List.of());
        when(directoryRepository.findByDirectoryTypeAndNodeEntity(DirectoryType.CACHE, node))
                .thenReturn(List.of(cacheDir));
        when(epubParser.readEntry(Path.of(EPUB_PATH), "OEBPS/cover.png")).thenReturn(Optional.of(COVER));

        subject.handle(event());

        Path expected = cachePath.resolve("book-covers").resolve(bookId.toString()).resolve("cover.png");
        assertTrue(Files.exists(expected));
        assertArrayEquals(COVER, Files.readAllBytes(expected));

        ArgumentCaptor<ImageFoundData> data = ArgumentCaptor.forClass(ImageFoundData.class);
        verify(messageSender).sendImageFound(data.capture(), eq("node1-cache-directory"));
        assertEquals(expected.toString(), data.getValue().getPath());
        assertEquals(ImageType.COVER, data.getValue().getImageType());
        assertEquals(bookId, data.getValue().getBookEntityId());
        assertEquals(cacheDirId, data.getValue().getDirectoryEntityId());
    }

    @Test
    void skipsCoverWhenBookAlreadyHasImage() {
        when(epubParser.parse(Path.of(EPUB_PATH))).thenReturn(Optional.of(info("OEBPS/cover.jpg", false, 0)));
        when(metadataRepository.findByBookEntityId(bookId)).thenReturn(List.of());
        when(imageRepository.findByBookEntityId(bookId))
                .thenReturn(List.of(ImageEntity.builder().path("/cache/cover.jpg").build()));

        subject.handle(event());

        verify(epubParser, never()).readEntry(any(Path.class), any(String.class));
        verifyNoInteractions(messageSender);
    }

    @Test
    void skipsCoverWhenNoCacheDirectory() {
        when(epubParser.parse(Path.of(EPUB_PATH))).thenReturn(Optional.of(info("OEBPS/cover.jpg", false, 0)));
        when(metadataRepository.findByBookEntityId(bookId)).thenReturn(List.of());
        when(imageRepository.findByBookEntityId(bookId)).thenReturn(List.of());
        when(directoryRepository.findByDirectoryTypeAndNodeEntity(DirectoryType.CACHE, node)).thenReturn(List.of());

        subject.handle(event());

        verifyNoInteractions(messageSender);
    }

    @Test
    void skipsCoverWhenEntryCannotBeRead() {
        when(epubParser.parse(Path.of(EPUB_PATH))).thenReturn(Optional.of(info("OEBPS/cover.jpg", false, 0)));
        when(metadataRepository.findByBookEntityId(bookId)).thenReturn(List.of());
        when(imageRepository.findByBookEntityId(bookId)).thenReturn(List.of());
        when(directoryRepository.findByDirectoryTypeAndNodeEntity(DirectoryType.CACHE, node))
                .thenReturn(List.of(cacheDir));
        when(epubParser.readEntry(Path.of(EPUB_PATH), "OEBPS/cover.jpg")).thenReturn(Optional.empty());

        subject.handle(event());

        verifyNoInteractions(messageSender);
    }

    @Test
    void unparseableLanguageBecomesNullLanguage() {
        when(epubParser.parse(Path.of(EPUB_PATH)))
                .thenReturn(Optional.of(new EpubInfo("T", "A", "  ", "D", 0, null, null, null, null, false, 0)));
        when(metadataRepository.findByBookEntityId(bookId)).thenReturn(List.of());

        subject.handle(event());

        ArgumentCaptor<MetadataEntity> saved = ArgumentCaptor.forClass(MetadataEntity.class);
        verify(metadataRepository).save(saved.capture());
        org.junit.jupiter.api.Assertions.assertNull(saved.getValue().getLanguage());
    }
}
