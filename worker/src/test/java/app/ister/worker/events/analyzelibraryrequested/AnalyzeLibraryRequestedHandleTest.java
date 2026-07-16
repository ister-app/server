package app.ister.worker.events.analyzelibraryrequested;

import app.ister.core.entity.BookEntity;
import app.ister.core.entity.DirectoryEntity;
import app.ister.core.entity.MediaFileEntity;
import app.ister.core.entity.NodeEntity;
import app.ister.core.entity.PersonEntity;
import app.ister.core.enums.DirectoryType;
import app.ister.core.enums.EventType;
import app.ister.core.enums.LibraryType;
import app.ister.core.eventdata.AnalyzeLibraryRequestedData;
import app.ister.core.eventdata.BookFoundData;
import app.ister.core.eventdata.EpubFileFoundData;
import app.ister.core.eventdata.PersonFoundData;
import app.ister.core.eventdata.UpdateImagesRequestedData;
import app.ister.core.repository.AlbumRepository;
import app.ister.core.repository.BookRepository;
import app.ister.core.repository.MediaFileRepository;
import app.ister.core.repository.PersonRepository;
import app.ister.core.repository.DirectoryRepository;
import app.ister.core.repository.EpisodeRepository;
import app.ister.core.repository.MovieRepository;
import app.ister.core.repository.ShowRepository;
import app.ister.core.repository.TrackRepository;
import app.ister.core.service.BookSeriesService;
import app.ister.core.service.MessageSender;
import app.ister.core.service.NodeService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AnalyzeLibraryRequestedHandleTest {

    @InjectMocks
    private AnalyzeLibraryRequestedHandle subject;

    @Mock
    private ShowRepository showRepository;

    @Mock
    private EpisodeRepository episodeRepository;

    @Mock
    private MovieRepository movieRepository;

    @Mock
    private MessageSender messageSender;

    @Mock
    private NodeService nodeService;

    @Mock
    private DirectoryRepository directoryRepository;

    @Mock
    private PersonRepository personRepository;

    @Mock
    private AlbumRepository albumRepository;

    @Mock
    private TrackRepository trackRepository;

    @Mock
    private BookRepository bookRepository;

    @Mock
    private MediaFileRepository mediaFileRepository;

    @Mock
    private BookSeriesService bookSeriesService;

    @Test
    void handles() {
        assertEquals(EventType.ANALYZE_LIBRARY_REQUEST, subject.handles());
    }

    @Test
    void listenerThrowsOnWrongEventType() {
        AnalyzeLibraryRequestedData data = AnalyzeLibraryRequestedData.builder()
                .eventType(EventType.FILE_SCAN_REQUESTED)
                .build();
        assertThrows(IllegalArgumentException.class, () -> subject.listener(data));
    }

    @Test
    void handleSendsUpdateImagesPerDirectoryAndDispatchesMetadataEvents() {
        NodeEntity nodeEntity = NodeEntity.builder().name("TestServer").build();
        DirectoryEntity library = DirectoryEntity.builder()
                .id(UUID.randomUUID()).name("disk1").directoryType(DirectoryType.LIBRARY).build();
        DirectoryEntity cache = DirectoryEntity.builder()
                .id(UUID.randomUUID()).name("TestServer-cache-directory").directoryType(DirectoryType.CACHE).build();
        UUID showId = UUID.randomUUID();
        UUID episodeId = UUID.randomUUID();
        UUID movieId = UUID.randomUUID();
        AnalyzeLibraryRequestedData data = AnalyzeLibraryRequestedData.builder()
                .eventType(EventType.ANALYZE_LIBRARY_REQUEST)
                .build();

        when(nodeService.getOrCreateNodeEntityForThisNode()).thenReturn(nodeEntity);
        when(directoryRepository.findByNodeEntity(nodeEntity)).thenReturn(List.of(library, cache));
        when(showRepository.findIdsOfShowsWithoutMetadataForNode("TestServer")).thenReturn(List.of(showId));
        when(episodeRepository.findIdsOfEpisodesWithoutMetadataForNode("TestServer")).thenReturn(List.of(episodeId));
        when(movieRepository.findIdsOfMoviesWithoutMetadataForNode("TestServer")).thenReturn(List.of(movieId));

        subject.handle(data);

        verify(messageSender).sendUpdateImagesRequested(any(), eq("disk1"));
        // The cache directory holds the downloaded artwork -- by far the most images -- so it must
        // get a sweep of its own, not just the library directories.
        verify(messageSender).sendUpdateImagesRequested(any(), eq("TestServer-cache-directory"));
        verify(messageSender).sendShowFound(any());
        verify(messageSender).sendEpisodeFound(any());
        verify(messageSender).sendMovieFound(any());
    }

    @Test
    void handleDispatchesPersonFoundForMusicArtistsAndBookAuthorsWithoutMetadata() {
        NodeEntity nodeEntity = NodeEntity.builder().name("TestServer").build();
        PersonEntity artist = PersonEntity.builder().id(UUID.randomUUID()).name("Artist").build();
        PersonEntity author = PersonEntity.builder().id(UUID.randomUUID()).name("Author").build();

        when(nodeService.getOrCreateNodeEntityForThisNode()).thenReturn(nodeEntity);
        when(personRepository.findByLibraryEntity_LibraryTypeAndMetadataEntitiesIsEmpty(LibraryType.MUSIC))
                .thenReturn(List.of(artist));
        when(personRepository.findByLibraryEntity_LibraryTypeAndMetadataEntitiesIsEmpty(LibraryType.BOOK))
                .thenReturn(List.of(author));

        subject.handle(AnalyzeLibraryRequestedData.builder().eventType(EventType.ANALYZE_LIBRARY_REQUEST).build());

        // Unrouted, i.e. onto the global queue: only the worker's HandlePersonFound (MusicBrainz /
        // Open Library / Wikipedia) listens there. A node-scoped send would reach the disk handler,
        // which merely re-parses artist.nfo, and the enrichment this dispatch exists for never runs.
        ArgumentCaptor<PersonFoundData> captor = ArgumentCaptor.forClass(PersonFoundData.class);
        verify(messageSender, times(2)).sendPersonFound(captor.capture());
        verify(messageSender, never()).sendPersonFound(any(), anyString());
        assertEquals(List.of(artist.getId(), author.getId()),
                captor.getAllValues().stream().map(PersonFoundData::getPersonId).toList());
    }

    @Test
    void handleReparsesTheEpubsOfBooksWithoutOpenLibraryMetadata() {
        NodeEntity nodeEntity = NodeEntity.builder().name("TestServer").build();
        DirectoryEntity dir = DirectoryEntity.builder()
                .id(UUID.randomUUID()).name("books-dir").directoryType(DirectoryType.LIBRARY).build();
        BookEntity book = BookEntity.builder().id(UUID.randomUUID())
                .personEntity(PersonEntity.builder().id(UUID.randomUUID()).name("Author").build())
                .name("Book").build();
        MediaFileEntity epub = MediaFileEntity.builder().path("/books/Author/Book.epub").size(1L).build();
        epub.setId(UUID.randomUUID());
        epub.setDirectoryEntityId(dir.getId());

        when(nodeService.getOrCreateNodeEntityForThisNode()).thenReturn(nodeEntity);
        when(bookRepository.findBooksWithoutOpenLibraryMetadata(LibraryType.BOOK)).thenReturn(List.of(book));
        when(mediaFileRepository.findByBookEntityId(book.getId())).thenReturn(List.of(epub));
        when(directoryRepository.findById(dir.getId())).thenReturn(java.util.Optional.of(dir));

        subject.handle(AnalyzeLibraryRequestedData.builder().eventType(EventType.ANALYZE_LIBRARY_REQUEST).build());

        // The epub re-parse chains BOOK_FOUND itself, after storing the ISBN — a direct BOOK_FOUND
        // would race the Open Library lookup against the ISBN write.
        ArgumentCaptor<EpubFileFoundData> captor = ArgumentCaptor.forClass(EpubFileFoundData.class);
        verify(messageSender).sendEpubFileFound(captor.capture(), eq("books-dir"));
        assertEquals(book.getId(), captor.getValue().getBookEntityUUID());
        assertEquals(epub.getId(), captor.getValue().getMediaFileEntityUUID());
        verify(messageSender, never()).sendBookFound(any());
        verify(bookSeriesService).cleanupOrphanSeries();
    }

    @Test
    void handleSendsBookFoundForAudiobookOnlyAndCoverlessBooks() {
        NodeEntity nodeEntity = NodeEntity.builder().name("TestServer").build();
        PersonEntity author = PersonEntity.builder().id(UUID.randomUUID()).name("Author").build();
        BookEntity audiobookOnly = BookEntity.builder().id(UUID.randomUUID())
                .personEntity(author).name("Audiobook").build();
        BookEntity coverless = BookEntity.builder().id(UUID.randomUUID())
                .personEntity(author).name("Coverless").build();

        when(nodeService.getOrCreateNodeEntityForThisNode()).thenReturn(nodeEntity);
        when(bookRepository.findBooksWithoutOpenLibraryMetadata(LibraryType.BOOK))
                .thenReturn(List.of(audiobookOnly));
        when(mediaFileRepository.findByBookEntityId(audiobookOnly.getId())).thenReturn(List.of());
        // The coverless book appears in both queries; it must be dispatched only once.
        when(bookRepository.findByLibraryEntity_LibraryTypeAndImageEntitiesIsEmpty(LibraryType.BOOK))
                .thenReturn(List.of(audiobookOnly, coverless));
        when(bookRepository.findByLibraryEntity_LibraryType(LibraryType.BOOK))
                .thenReturn(List.of(audiobookOnly, coverless));

        subject.handle(AnalyzeLibraryRequestedData.builder().eventType(EventType.ANALYZE_LIBRARY_REQUEST).build());

        ArgumentCaptor<BookFoundData> captor = ArgumentCaptor.forClass(BookFoundData.class);
        verify(messageSender, times(2)).sendBookFound(captor.capture());
        assertEquals(List.of(audiobookOnly.getId(), coverless.getId()),
                captor.getAllValues().stream().map(BookFoundData::getBookId).toList());
        // One distinct author over both books: the series heuristic runs once for them.
        verify(bookSeriesService, times(1)).applyPrefixHeuristic(author);
    }

    @Test
    void handleStartsEachSweepAtTheBeginningOfItsDirectory() {
        NodeEntity nodeEntity = NodeEntity.builder().name("TestServer").build();
        DirectoryEntity directory = DirectoryEntity.builder()
                .id(UUID.randomUUID()).name("disk1").directoryType(DirectoryType.LIBRARY).build();

        when(nodeService.getOrCreateNodeEntityForThisNode()).thenReturn(nodeEntity);
        when(directoryRepository.findByNodeEntity(nodeEntity)).thenReturn(List.of(directory));

        subject.handle(AnalyzeLibraryRequestedData.builder().eventType(EventType.ANALYZE_LIBRARY_REQUEST).build());

        ArgumentCaptor<UpdateImagesRequestedData> sweep = ArgumentCaptor.forClass(UpdateImagesRequestedData.class);
        verify(messageSender).sendUpdateImagesRequested(sweep.capture(), eq("disk1"));
        assertEquals(directory.getId(), sweep.getValue().getDirectoryEntityId());
        assertEquals("disk1", sweep.getValue().getDirectoryName());
        assertNull(sweep.getValue().getAfterId(), "a fresh sweep must start without a cursor");
    }
}
