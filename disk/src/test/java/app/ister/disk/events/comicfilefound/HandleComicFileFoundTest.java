package app.ister.disk.events.comicfilefound;

import app.ister.core.entity.BookEntity;
import app.ister.core.entity.DirectoryEntity;
import app.ister.core.entity.ImageEntity;
import app.ister.core.entity.LibraryEntity;
import app.ister.core.entity.MediaFileEntity;
import app.ister.core.entity.MetadataEntity;
import app.ister.core.entity.NodeEntity;
import app.ister.core.entity.SeriesEntity;
import app.ister.core.enums.DirectoryType;
import app.ister.core.enums.EventType;
import app.ister.core.enums.ImageType;
import app.ister.core.enums.LibraryType;
import app.ister.core.enums.ReadingDirection;
import app.ister.core.enums.SearchEntityType;
import app.ister.core.eventdata.ComicFileFoundData;
import app.ister.core.eventdata.ImageFoundData;
import app.ister.core.repository.BookRepository;
import app.ister.core.repository.DirectoryRepository;
import app.ister.core.repository.ImageRepository;
import app.ister.core.repository.MediaFileRepository;
import app.ister.core.repository.MetadataRepository;
import app.ister.core.repository.SeriesRepository;
import app.ister.core.service.MessageSender;
import app.ister.core.service.ScannerHelperService;
import app.ister.core.service.ServerEventService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class HandleComicFileFoundTest {

    @Mock
    private DirectoryRepository directoryRepository;
    @Mock
    private MediaFileRepository mediaFileRepository;
    @Mock
    private MetadataRepository metadataRepository;
    @Mock
    private BookRepository bookRepository;
    @Mock
    private SeriesRepository seriesRepository;
    @Mock
    private ImageRepository imageRepository;
    @Mock
    private MessageSender messageSender;
    @Mock
    private ServerEventService serverEventService;
    @Mock
    private ScannerHelperService scannerHelperService;

    private final CbzParser cbzParser = new CbzParser();
    private final PdfParser pdfParser = new PdfParser();

    private HandleComicFileFound subject;

    @TempDir
    Path cachePath;

    @TempDir
    Path libraryPath;

    private final UUID directoryId = UUID.randomUUID();
    private final UUID mediaFileId = UUID.randomUUID();
    private final UUID volumeId = UUID.randomUUID();
    private final UUID cacheDirId = UUID.randomUUID();

    private NodeEntity node;
    private DirectoryEntity libraryDir;
    private DirectoryEntity cacheDir;
    private MediaFileEntity mediaFile;
    private BookEntity volume;
    private SeriesEntity series;

    @BeforeEach
    void setUp() {
        subject = new HandleComicFileFound(directoryRepository, mediaFileRepository, metadataRepository,
                bookRepository, seriesRepository, imageRepository, cbzParser, pdfParser, messageSender,
                serverEventService, scannerHelperService);
        node = NodeEntity.builder().name("node1").build();
        LibraryEntity library = LibraryEntity.builder().libraryType(LibraryType.COMIC).name("Comics").build();
        libraryDir = DirectoryEntity.builder()
                .id(directoryId).nodeEntity(node).libraryEntity(library)
                .name("comics-dir").path(libraryPath.toString()).directoryType(DirectoryType.LIBRARY)
                .build();
        cacheDir = DirectoryEntity.builder()
                .id(cacheDirId).nodeEntity(node)
                .name("node1-cache-directory").path(cachePath.toString()).directoryType(DirectoryType.CACHE)
                .build();
        series = SeriesEntity.builder().id(UUID.randomUUID()).name("Fairy Tail").build();
        volume = BookEntity.builder().id(volumeId).libraryEntity(library)
                .seriesEntity(series).name("fairytail_vol12").build();
        mediaFile = MediaFileEntity.builder().path("unset").size(1L).build();
        mediaFile.setId(mediaFileId);

        lenient().when(directoryRepository.findById(directoryId)).thenReturn(Optional.of(libraryDir));
        lenient().when(mediaFileRepository.findById(mediaFileId)).thenReturn(Optional.of(mediaFile));
        lenient().when(bookRepository.findById(volumeId)).thenReturn(Optional.of(volume));
        lenient().when(directoryRepository.findByDirectoryTypeAndNodeEntity(DirectoryType.CACHE, node))
                .thenReturn(List.of(cacheDir));
    }

    private ComicFileFoundData event(Path path) {
        return ComicFileFoundData.builder()
                .eventType(EventType.COMIC_FILE_FOUND)
                .directoryEntityUUID(directoryId)
                .mediaFileEntityUUID(mediaFileId)
                .bookEntityUUID(volumeId)
                .path(path.toString())
                .build();
    }

    private Path writeCbz(Map<String, byte[]> entries) throws IOException {
        Path cbz = libraryPath.resolve("Fairy Tail").resolve("fairytail_vol12.cbz");
        Files.createDirectories(cbz.getParent());
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(cbz))) {
            for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
                zip.putNextEntry(new ZipEntry(entry.getKey()));
                zip.write(entry.getValue());
                zip.closeEntry();
            }
        }
        return cbz;
    }

    @Test
    void handlesReturnsCorrectEventType() {
        assertEquals(EventType.COMIC_FILE_FOUND, subject.handles());
    }

    @Test
    void listenerThrowsOnWrongEventType() {
        ComicFileFoundData data = ComicFileFoundData.builder().eventType(EventType.EPUB_FILE_FOUND).build();
        assertThrows(IllegalArgumentException.class, () -> subject.listener(data));
    }

    @Test
    void cbzStoresPageCountComicInfoAndCover() throws IOException {
        String comicInfo = """
                <ComicInfo><Number>12</Number><Title>The Guild</Title>
                <Summary>A wizard guild.</Summary><Year>2008</Year></ComicInfo>""";
        Path cbz = writeCbz(Map.of(
                "page2.jpg", new byte[]{2},
                "page1.jpg", new byte[]{1},
                "ComicInfo.xml", comicInfo.getBytes(StandardCharsets.UTF_8)));
        when(metadataRepository.findByBookEntityId(volumeId)).thenReturn(List.of());
        when(imageRepository.findByBookEntityId(volumeId)).thenReturn(List.of());

        subject.handle(event(cbz));

        assertEquals(2, mediaFile.getPageCount());
        ArgumentCaptor<MetadataEntity> saved = ArgumentCaptor.forClass(MetadataEntity.class);
        verify(metadataRepository).save(saved.capture());
        assertEquals("The Guild", saved.getValue().getTitle());
        assertEquals("A wizard guild.", saved.getValue().getDescription());
        assertEquals(LocalDate.of(2008, 1, 1), saved.getValue().getReleased());
        // ComicInfo refines the filename-derived position and title.
        assertEquals(12.0, volume.getSeriesIndex());
        assertEquals("The Guild", volume.getTitle());
        verify(scannerHelperService).refreshBookReleaseYear(volume);

        // The first page (natural order) became the cover.
        Path cover = cachePath.resolve("book-covers").resolve(volumeId.toString()).resolve("cover.jpg");
        assertTrue(Files.exists(cover));
        ArgumentCaptor<ImageFoundData> image = ArgumentCaptor.forClass(ImageFoundData.class);
        verify(messageSender).sendImageFound(image.capture(), eq("node1-cache-directory"));
        assertEquals(ImageType.COVER, image.getValue().getImageType());
        assertEquals(volumeId, image.getValue().getBookEntityId());
        verify(serverEventService).createSearchIndexEvent(SearchEntityType.BOOK, volumeId);
    }

    @Test
    void cbzWithoutComicInfoOnlyStoresPagesAndCover() throws IOException {
        Path cbz = writeCbz(Map.of("page1.jpg", new byte[]{1}));
        when(imageRepository.findByBookEntityId(volumeId)).thenReturn(List.of());

        subject.handle(event(cbz));

        assertEquals(1, mediaFile.getPageCount());
        verify(metadataRepository, never()).save(any());
    }

    @Test
    void existingCoverIsNotOverwritten() throws IOException {
        Path cbz = writeCbz(Map.of("page1.jpg", new byte[]{1}));
        when(imageRepository.findByBookEntityId(volumeId))
                .thenReturn(List.of(ImageEntity.builder().path("/x.jpg").build()));

        subject.handle(event(cbz));

        verifyNoInteractions(messageSender);
    }

    @Test
    void reparsingUpsertsTheSameMetadataRow() throws IOException {
        String comicInfo = "<ComicInfo><Title>New Title</Title></ComicInfo>";
        Path cbz = writeCbz(Map.of(
                "page1.jpg", new byte[]{1},
                "ComicInfo.xml", comicInfo.getBytes(StandardCharsets.UTF_8)));
        MetadataEntity own = MetadataEntity.builder()
                .bookEntity(volume).sourceUri("file://" + cbz).title("Stale").build();
        when(metadataRepository.findByBookEntityId(volumeId)).thenReturn(List.of(own));
        when(imageRepository.findByBookEntityId(volumeId)).thenReturn(List.of());

        subject.handle(event(cbz));

        verify(metadataRepository).save(own);
        assertEquals("New Title", own.getTitle());
    }

    /** An explicit Manga tag lands on the series and is idempotent on rescan. */
    @Test
    void mangaTagSetsTheSeriesDefaultReadingDirection() throws IOException {
        String comicInfo = "<ComicInfo><Manga>YesAndRightToLeft</Manga></ComicInfo>";
        Path cbz = writeCbz(Map.of(
                "page1.jpg", new byte[]{1},
                "ComicInfo.xml", comicInfo.getBytes(StandardCharsets.UTF_8)));
        when(metadataRepository.findByBookEntityId(volumeId)).thenReturn(List.of());
        when(imageRepository.findByBookEntityId(volumeId)).thenReturn(List.of());

        subject.handle(event(cbz));
        assertEquals(ReadingDirection.RTL, series.getDefaultReadingDirection());
        verify(seriesRepository).save(series);

        // Rescan: the direction already matches, no second save.
        subject.handle(event(cbz));
        verify(seriesRepository, times(1)).save(series);
    }

    /** ComicInfo is authoritative: an explicit No overwrites a weaker Wikidata-detected RTL. */
    @Test
    void mangaNoOverwritesADetectedDirection() throws IOException {
        series.setDefaultReadingDirection(ReadingDirection.RTL);
        String comicInfo = "<ComicInfo><Manga>No</Manga></ComicInfo>";
        Path cbz = writeCbz(Map.of(
                "page1.jpg", new byte[]{1},
                "ComicInfo.xml", comicInfo.getBytes(StandardCharsets.UTF_8)));
        when(metadataRepository.findByBookEntityId(volumeId)).thenReturn(List.of());
        when(imageRepository.findByBookEntityId(volumeId)).thenReturn(List.of());

        subject.handle(event(cbz));

        assertEquals(ReadingDirection.LTR, series.getDefaultReadingDirection());
    }

    /** An absent tag leaves the direction unset so the metadata enrichment can still fill it. */
    @Test
    void absentMangaTagLeavesTheDirectionUntouched() throws IOException {
        String comicInfo = "<ComicInfo><Title>The Guild</Title></ComicInfo>";
        Path cbz = writeCbz(Map.of(
                "page1.jpg", new byte[]{1},
                "ComicInfo.xml", comicInfo.getBytes(StandardCharsets.UTF_8)));
        when(metadataRepository.findByBookEntityId(volumeId)).thenReturn(List.of());
        when(imageRepository.findByBookEntityId(volumeId)).thenReturn(List.of());

        subject.handle(event(cbz));

        assertNull(series.getDefaultReadingDirection());
        verifyNoInteractions(seriesRepository);
    }

    @Test
    void pdfStoresPageCountAndRenderedCover() throws IOException {
        Path pdf = libraryPath.resolve("Fairy Tail").resolve("fairytail_vol12.pdf");
        Files.createDirectories(pdf.getParent());
        try (org.apache.pdfbox.pdmodel.PDDocument document = new org.apache.pdfbox.pdmodel.PDDocument()) {
            document.addPage(new org.apache.pdfbox.pdmodel.PDPage());
            document.addPage(new org.apache.pdfbox.pdmodel.PDPage());
            document.save(pdf.toFile());
        }
        when(imageRepository.findByBookEntityId(volumeId)).thenReturn(List.of());

        subject.handle(event(pdf));

        assertEquals(2, mediaFile.getPageCount());
        Path cover = cachePath.resolve("book-covers").resolve(volumeId.toString()).resolve("cover.jpg");
        assertTrue(Files.exists(cover));
        verify(serverEventService).createSearchIndexEvent(SearchEntityType.BOOK, volumeId);
    }

    @Test
    void skipsWhenMediaFileOrVolumeIsMissing() {
        when(mediaFileRepository.findById(mediaFileId)).thenReturn(Optional.empty());

        subject.handle(event(Path.of("/x.cbz")));

        verifyNoInteractions(metadataRepository, messageSender, serverEventService);
    }
}
