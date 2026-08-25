package app.ister.worker.events.metadatabackfill;

import app.ister.core.entity.AlbumEntity;
import app.ister.core.entity.BookEntity;
import app.ister.core.entity.DirectoryEntity;
import app.ister.core.entity.LibraryEntity;
import app.ister.core.entity.MediaFileEntity;
import app.ister.core.entity.MetadataEntity;
import app.ister.core.entity.NodeEntity;
import app.ister.core.entity.OtherPathFileEntity;
import app.ister.core.entity.PersonEntity;
import app.ister.core.entity.SeriesEntity;
import app.ister.core.enums.DirectoryType;
import app.ister.core.enums.LibraryType;
import app.ister.core.repository.AlbumRepository;
import app.ister.core.repository.BookRepository;
import app.ister.core.repository.DirectoryRepository;
import app.ister.core.repository.EpisodeRepository;
import app.ister.core.repository.MediaFileRepository;
import app.ister.core.repository.MetadataRepository;
import app.ister.core.repository.MovieRepository;
import app.ister.core.repository.OtherPathFileRepository;
import app.ister.core.repository.PersonRepository;
import app.ister.core.repository.SeriesRepository;
import app.ister.core.repository.ShowRepository;
import app.ister.core.repository.TrackRepository;
import app.ister.core.service.BookSeriesService;
import app.ister.worker.events.common.FoundEventDispatcher;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MetadataBackfillServiceTest {

    @InjectMocks
    private MetadataBackfillService subject;

    @Mock
    private ShowRepository showRepository;
    @Mock
    private EpisodeRepository episodeRepository;
    @Mock
    private MovieRepository movieRepository;
    @Mock
    private PersonRepository personRepository;
    @Mock
    private AlbumRepository albumRepository;
    @Mock
    private TrackRepository trackRepository;
    @Mock
    private BookRepository bookRepository;
    @Mock
    private SeriesRepository seriesRepository;
    @Mock
    private BookSeriesService bookSeriesService;
    @Mock
    private MediaFileRepository mediaFileRepository;
    @Mock
    private MetadataRepository metadataRepository;
    @Mock
    private OtherPathFileRepository otherPathFileRepository;
    @Mock
    private DirectoryRepository directoryRepository;
    @Mock
    private FoundEventDispatcher dispatcher;

    private final LibraryEntity musicLibrary = LibraryEntity.builder()
            .id(UUID.randomUUID()).libraryType(LibraryType.MUSIC).name("Music").build();
    private final LibraryEntity bookLibrary = LibraryEntity.builder()
            .id(UUID.randomUUID()).libraryType(LibraryType.BOOK).name("Books").build();

    @Test
    void dispatchesEveryVideoItemNeedingMetadataExactlyOnce() {
        UUID libraryId = UUID.randomUUID();
        UUID showId = UUID.randomUUID();
        UUID episodeId = UUID.randomUUID();
        UUID movieId = UUID.randomUUID();
        when(showRepository.findIdsOfShowsNeedingMetadata(libraryId)).thenReturn(List.of(showId));
        when(episodeRepository.findIdsOfEpisodesWithoutMetadata(libraryId)).thenReturn(List.of(episodeId));
        when(movieRepository.findIdsOfMoviesNeedingMetadata(libraryId)).thenReturn(List.of(movieId));

        subject.dispatchMissingVideoMetadataEvents(libraryId);

        verify(dispatcher).showFound(showId);
        verify(dispatcher).episodeFound(episodeId);
        verify(dispatcher).movieFound(movieId);
    }

    @Test
    void dispatchesPersonsGloballyOnly() {
        PersonEntity artist = PersonEntity.builder().id(UUID.randomUUID()).libraryEntity(musicLibrary).name("Artist").build();
        PersonEntity author = PersonEntity.builder().id(UUID.randomUUID()).libraryEntity(bookLibrary).name("Author").build();
        when(personRepository.findByLibraryEntity_LibraryTypeAndMetadataEntitiesIsEmpty(LibraryType.MUSIC))
                .thenReturn(List.of(artist));
        when(personRepository.findByLibraryEntity_LibraryTypeAndMetadataEntitiesIsEmpty(LibraryType.BOOK))
                .thenReturn(List.of(author));

        subject.dispatchMissingPersonMetadataEvents(null);

        // Global on purpose: only the worker's enrichment handler listens there; the node-scoped
        // queue would reach the disk handler, which merely re-parses artist.nfo.
        verify(dispatcher).personFoundGlobal(artist.getId());
        verify(dispatcher).personFoundGlobal(author.getId());
        verify(dispatcher, never()).personFoundToNode(any(), any());
    }

    @Test
    void personDispatchHonoursTheLibraryScope() {
        PersonEntity artist = PersonEntity.builder().id(UUID.randomUUID()).libraryEntity(musicLibrary).name("Artist").build();
        PersonEntity author = PersonEntity.builder().id(UUID.randomUUID()).libraryEntity(bookLibrary).name("Author").build();
        when(personRepository.findByLibraryEntity_LibraryTypeAndMetadataEntitiesIsEmpty(LibraryType.MUSIC))
                .thenReturn(List.of(artist));
        when(personRepository.findByLibraryEntity_LibraryTypeAndMetadataEntitiesIsEmpty(LibraryType.BOOK))
                .thenReturn(List.of(author));

        subject.dispatchMissingPersonMetadataEvents(musicLibrary.getId());

        verify(dispatcher).personFoundGlobal(artist.getId());
        verify(dispatcher, never()).personFoundGlobal(author.getId());
    }

    @Test
    void routesMetadataMissingAlbumsToEveryNodeOfTheirLibraryAndImageMissingOnesGlobally() {
        NodeEntity node1 = NodeEntity.builder().name("node1").build();
        NodeEntity node2 = NodeEntity.builder().name("node2").build();
        DirectoryEntity dir1 = DirectoryEntity.builder().id(UUID.randomUUID()).name("d1")
                .directoryType(DirectoryType.LIBRARY).libraryEntity(musicLibrary).nodeEntity(node1).build();
        DirectoryEntity dir2 = DirectoryEntity.builder().id(UUID.randomUUID()).name("d2")
                .directoryType(DirectoryType.LIBRARY).libraryEntity(musicLibrary).nodeEntity(node2).build();
        AlbumEntity metadataMissing = AlbumEntity.builder().id(UUID.randomUUID()).libraryEntity(musicLibrary).name("A").build();
        AlbumEntity imageMissing = AlbumEntity.builder().id(UUID.randomUUID()).libraryEntity(musicLibrary).name("B").build();
        when(directoryRepository.findByDirectoryType(DirectoryType.LIBRARY)).thenReturn(List.of(dir1, dir2));
        when(albumRepository.findByLibraryEntity_LibraryTypeAndMetadataEntitiesIsEmpty(LibraryType.MUSIC))
                .thenReturn(List.of(metadataMissing));
        when(albumRepository.findByLibraryEntity_LibraryTypeAndImageEntitiesIsEmpty(LibraryType.MUSIC))
                .thenReturn(List.of(imageMissing));
        when(trackRepository.findByAlbumEntity_LibraryEntity_LibraryTypeAndMetadataEntitiesIsEmpty(LibraryType.MUSIC))
                .thenReturn(List.of());

        subject.dispatchMissingMusicMetadataEvents(null);

        verify(dispatcher).albumFoundToNode(metadataMissing.getId(), "node1");
        verify(dispatcher).albumFoundToNode(metadataMissing.getId(), "node2");
        verify(dispatcher).albumFoundGlobal(imageMissing.getId());
        verify(dispatcher, never()).albumFoundToNode(eq(imageMissing.getId()), any());
    }

    @Test
    void reparsesTheEpubsOfBooksWithoutOpenLibraryMetadataWithBatchedFileLookups() {
        DirectoryEntity dir = DirectoryEntity.builder()
                .id(UUID.randomUUID()).name("books-dir").directoryType(DirectoryType.LIBRARY).build();
        BookEntity book = BookEntity.builder().id(UUID.randomUUID()).libraryEntity(bookLibrary)
                .personEntity(PersonEntity.builder().id(UUID.randomUUID()).name("Author").build())
                .name("Book").build();
        MediaFileEntity epub = MediaFileEntity.builder().path("/books/Author/Book.epub").size(1L).build();
        epub.setId(UUID.randomUUID());
        epub.setDirectoryEntityId(dir.getId());
        epub.setBookEntity(book);
        when(directoryRepository.findAll()).thenReturn(List.of(dir));
        when(bookRepository.findBooksWithoutOpenLibraryMetadata(LibraryType.BOOK)).thenReturn(List.of(book));
        when(mediaFileRepository.findByBookEntityIdIn(anyCollection())).thenReturn(List.of(epub));

        subject.dispatchMissingBookMetadataEvents(null);

        // The epub re-parse chains BOOK_FOUND itself, after storing the ISBN — a direct BOOK_FOUND
        // would race the Open Library lookup against the ISBN write.
        verify(dispatcher).epubFileFound(dir, book.getId(), epub);
        verify(dispatcher, never()).bookFound(any());
    }

    @Test
    void sendsBookFoundOnceForAudiobookOnlyAndCoverlessBooks() {
        PersonEntity author = PersonEntity.builder().id(UUID.randomUUID()).name("Author").build();
        BookEntity audiobookOnly = BookEntity.builder().id(UUID.randomUUID()).libraryEntity(bookLibrary)
                .personEntity(author).name("Audiobook").build();
        BookEntity coverless = BookEntity.builder().id(UUID.randomUUID()).libraryEntity(bookLibrary)
                .personEntity(author).name("Coverless").build();
        when(directoryRepository.findAll()).thenReturn(List.of());
        when(bookRepository.findBooksWithoutOpenLibraryMetadata(LibraryType.BOOK))
                .thenReturn(List.of(audiobookOnly));
        when(mediaFileRepository.findByBookEntityIdIn(anyCollection())).thenReturn(List.of());
        // The coverless book appears in both queries; it must be dispatched only once.
        when(bookRepository.findByLibraryEntity_LibraryTypeAndImageEntitiesIsEmpty(LibraryType.BOOK))
                .thenReturn(List.of(audiobookOnly, coverless));

        subject.dispatchMissingBookMetadataEvents(null);

        verify(dispatcher, times(1)).bookFound(audiobookOnly.getId());
        verify(dispatcher, times(1)).bookFound(coverless.getId());
    }

    @Test
    void runsTheSeriesHeuristicOncePerDistinctAuthor() {
        UUID libraryId = bookLibrary.getId();
        PersonEntity author = PersonEntity.builder().id(UUID.randomUUID()).name("Author").build();
        when(bookRepository.findDistinctAuthors(LibraryType.BOOK, libraryId)).thenReturn(List.of(author));

        subject.applyBookSeriesHeuristics(libraryId);

        verify(bookSeriesService, times(1)).applyPrefixHeuristic(author);
        verify(bookSeriesService).cleanupOrphanSeries();
    }

    @Test
    void redispatchesTheNfoOfBooksMissingASeriesPosition() {
        DirectoryEntity dir = DirectoryEntity.builder()
                .id(UUID.randomUUID()).name("books-dir").directoryType(DirectoryType.LIBRARY).build();
        PersonEntity author = PersonEntity.builder().id(UUID.randomUUID()).name("Author").build();
        SeriesEntity series = SeriesEntity.builder().personEntity(author).name("Broederband").build();
        BookEntity missingIndex = BookEntity.builder().id(UUID.randomUUID()).libraryEntity(bookLibrary)
                .personEntity(author).name("Broederband - De indringers").seriesEntity(series).build();
        MetadataEntity nfoRow = MetadataEntity.builder()
                .sourceUri("file:///books/Author/Broederband - De indringers/album.nfo").build();
        MetadataEntity epubRow = MetadataEntity.builder()
                .sourceUri("file:///books/Author/Broederband - De indringers.epub").build();
        OtherPathFileEntity nfoFile = OtherPathFileEntity.builder()
                .directoryEntityId(dir.getId())
                .path("/books/Author/Broederband - De indringers/album.nfo")
                .build();
        when(directoryRepository.findAll()).thenReturn(List.of(dir));
        when(bookRepository.findBooksMissingSeriesInfo(LibraryType.BOOK, null)).thenReturn(List.of(missingIndex));
        when(metadataRepository.findByBookEntityId(missingIndex.getId())).thenReturn(List.of(epubRow, nfoRow));
        when(otherPathFileRepository.findByMetadataEntity(nfoRow)).thenReturn(Optional.of(nfoFile));

        subject.dispatchBookSeriesNfoEvents(null);

        verify(dispatcher).nfoFileFound(dir, nfoFile.getPath());
    }

    @Test
    void dispatchesComicSeriesWithoutMetadataAndReparsesCoverlessVolumes() {
        LibraryEntity comicLibrary = LibraryEntity.builder()
                .id(UUID.randomUUID()).libraryType(LibraryType.COMIC).name("Comics").build();
        DirectoryEntity dir = DirectoryEntity.builder()
                .id(UUID.randomUUID()).name("comics-dir").directoryType(DirectoryType.LIBRARY).build();
        SeriesEntity series = SeriesEntity.builder().id(UUID.randomUUID()).libraryEntity(comicLibrary).name("Series").build();
        BookEntity volume = BookEntity.builder().id(UUID.randomUUID()).libraryEntity(comicLibrary).name("Vol 1").build();
        MediaFileEntity cbz = MediaFileEntity.builder().path("/comics/Series/Vol 1.cbz").size(1L).build();
        cbz.setId(UUID.randomUUID());
        cbz.setDirectoryEntityId(dir.getId());
        cbz.setBookEntity(volume);
        when(seriesRepository.findByLibraryEntity_LibraryTypeAndMetadataEntitiesIsEmpty(LibraryType.COMIC))
                .thenReturn(List.of(series));
        when(directoryRepository.findAll()).thenReturn(List.of(dir));
        when(bookRepository.findByLibraryEntity_LibraryTypeAndImageEntitiesIsEmpty(LibraryType.COMIC))
                .thenReturn(List.of(volume));
        when(mediaFileRepository.findByBookEntityIdIn(anyCollection())).thenReturn(List.of(cbz));

        subject.dispatchMissingComicMetadataEvents(null);

        verify(dispatcher).comicSeriesFound(series.getId());
        verify(dispatcher).comicVolumeFileFound(dir, volume.getId(), cbz);
    }
}
