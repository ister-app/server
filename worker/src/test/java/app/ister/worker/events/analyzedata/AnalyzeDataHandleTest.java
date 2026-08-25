package app.ister.worker.events.analyzedata;

import app.ister.core.entity.AlbumEntity;
import app.ister.core.entity.BookEntity;
import app.ister.core.entity.PersonEntity;
import app.ister.core.entity.DirectoryEntity;
import app.ister.core.entity.EpisodeEntity;
import app.ister.core.entity.LibraryEntity;
import app.ister.core.entity.MediaFileEntity;
import app.ister.core.entity.MovieEntity;
import app.ister.core.entity.NodeEntity;
import app.ister.core.entity.SeriesEntity;
import app.ister.core.entity.ShowEntity;
import app.ister.core.entity.TrackEntity;
import app.ister.core.enums.DirectoryType;
import app.ister.core.enums.EventType;
import app.ister.core.enums.LibraryType;
import app.ister.core.eventdata.AnalyzeData;
import app.ister.core.repository.AlbumRepository;
import app.ister.core.repository.BookRepository;
import app.ister.core.repository.PersonRepository;
import app.ister.core.repository.DirectoryRepository;
import app.ister.core.repository.EpisodeRepository;
import app.ister.core.repository.ImageRepository;
import app.ister.core.repository.LibraryRepository;
import app.ister.core.repository.MediaFileRepository;
import app.ister.core.repository.MediaFileStreamRepository;
import app.ister.core.repository.MetadataRepository;
import app.ister.core.repository.MovieRepository;
import app.ister.core.repository.SeriesRepository;
import app.ister.core.repository.ShowRepository;
import app.ister.core.repository.TrackRepository;
import app.ister.core.service.MessageSender;
import app.ister.worker.events.common.FoundEventDispatcher;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Sort;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AnalyzeDataHandleTest {

    @InjectMocks
    private AnalyzeDataHandle subject;

    @Mock
    private EpisodeRepository episodeRepository;
    @Mock
    private MovieRepository movieRepository;
    @Mock
    private ShowRepository showRepository;
    @Mock
    private LibraryRepository libraryRepository;
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
    private DirectoryRepository directoryRepository;
    @Mock
    private MediaFileRepository mediaFileRepository;
    @Mock
    private MetadataRepository metadataRepository;
    @Mock
    private MediaFileStreamRepository mediaFileStreamRepository;
    @Mock
    private ImageRepository imageRepository;
    @Mock
    private MessageSender messageSender;
    @Mock
    private FoundEventDispatcher dispatcher;

    @Test
    void handles() {
        assertEquals(EventType.ANALYZE_DATA, subject.handles());
    }

    @Test
    void listenerThrowsOnWrongEventType() {
        AnalyzeData data = AnalyzeData.builder()
                .eventType(EventType.FILE_SCAN_REQUESTED)
                .build();
        assertThrows(IllegalArgumentException.class, () -> subject.listener(data));
    }

    @Test
    void listenerCallsHandleWithCorrectEventType() {
        UUID episodeId = UUID.randomUUID();
        DirectoryEntity dir = DirectoryEntity.builder().id(UUID.randomUUID()).name("dir1").build();
        EpisodeEntity episode = EpisodeEntity.builder()
                .id(episodeId)
                .mediaFileEntities(List.of(MediaFileEntity.builder()
                        .directoryEntity(dir)
                        .build()))
                .build();
        AnalyzeData data = AnalyzeData.builder()
                .eventType(EventType.ANALYZE_DATA)
                .episodeId(episodeId)
                .build();
        when(episodeRepository.findById(episodeId)).thenReturn(Optional.of(episode));

        assertDoesNotThrow(() -> subject.listener(data));
    }

    @Test
    void handleLibraryIdShowTypeSendsShowFanOut() {
        UUID libraryId = UUID.randomUUID();
        UUID showId1 = UUID.randomUUID();
        UUID showId2 = UUID.randomUUID();
        LibraryEntity library = LibraryEntity.builder()
                .id(libraryId)
                .libraryType(LibraryType.SHOW)
                .build();
        AnalyzeData data = AnalyzeData.builder()
                .eventType(EventType.ANALYZE_DATA)
                .libraryId(libraryId)
                .build();

        when(libraryRepository.findById(libraryId)).thenReturn(Optional.of(library));
        when(showRepository.findIdsByLibraryId(libraryId)).thenReturn(List.of(showId1, showId2));

        subject.handle(data);

        ArgumentCaptor<AnalyzeData> captor = ArgumentCaptor.forClass(AnalyzeData.class);
        verify(messageSender, times(2)).sendAnalyzeData(captor.capture());
        List<UUID> sentShowIds = captor.getAllValues().stream().map(AnalyzeData::getShowId).toList();
        assertTrue(sentShowIds.contains(showId1));
        assertTrue(sentShowIds.contains(showId2));
    }

    @Test
    void handleLibraryIdMovieTypeSendsMovieFanOut() {
        UUID libraryId = UUID.randomUUID();
        UUID movieId1 = UUID.randomUUID();
        LibraryEntity library = LibraryEntity.builder()
                .id(libraryId)
                .libraryType(LibraryType.MOVIE)
                .build();
        AnalyzeData data = AnalyzeData.builder()
                .eventType(EventType.ANALYZE_DATA)
                .libraryId(libraryId)
                .build();

        when(libraryRepository.findById(libraryId)).thenReturn(Optional.of(library));
        when(movieRepository.findIdsByLibraryId(libraryId)).thenReturn(List.of(movieId1));

        subject.handle(data);

        ArgumentCaptor<AnalyzeData> captor = ArgumentCaptor.forClass(AnalyzeData.class);
        verify(messageSender).sendAnalyzeData(captor.capture());
        assertEquals(movieId1, captor.getValue().getMovieId());
        assertEquals(EventType.ANALYZE_DATA, captor.getValue().getEventType());
    }

    @Test
    void handleLibraryIdBookTypeSendsAuthorFanOut() {
        UUID libraryId = UUID.randomUUID();
        UUID authorId = UUID.randomUUID();
        LibraryEntity library = LibraryEntity.builder()
                .id(libraryId)
                .libraryType(LibraryType.BOOK)
                .build();
        AnalyzeData data = AnalyzeData.builder()
                .eventType(EventType.ANALYZE_DATA)
                .libraryId(libraryId)
                .build();

        when(libraryRepository.findById(libraryId)).thenReturn(Optional.of(library));
        when(personRepository.findByLibraryEntityId(libraryId))
                .thenReturn(List.of(PersonEntity.builder().id(authorId).build()));

        subject.handle(data);

        ArgumentCaptor<AnalyzeData> captor = ArgumentCaptor.forClass(AnalyzeData.class);
        verify(messageSender).sendAnalyzeData(captor.capture());
        assertEquals(authorId, captor.getValue().getPersonId());
    }

    @Test
    void handleLibraryIdComicTypeSendsSeriesFanOut() {
        UUID libraryId = UUID.randomUUID();
        UUID seriesId = UUID.randomUUID();
        LibraryEntity library = LibraryEntity.builder()
                .id(libraryId)
                .libraryType(LibraryType.COMIC)
                .build();
        AnalyzeData data = AnalyzeData.builder()
                .eventType(EventType.ANALYZE_DATA)
                .libraryId(libraryId)
                .build();

        when(libraryRepository.findById(libraryId)).thenReturn(Optional.of(library));
        when(seriesRepository.findAllByLibraryEntityId(libraryId))
                .thenReturn(List.of(SeriesEntity.builder().id(seriesId).build()));

        subject.handle(data);

        ArgumentCaptor<AnalyzeData> captor = ArgumentCaptor.forClass(AnalyzeData.class);
        verify(messageSender).sendAnalyzeData(captor.capture());
        assertEquals(seriesId, captor.getValue().getSeriesId());
    }

    @Test
    void handleLibraryIdUnsupportedTypeSkipsWithoutFanOut() {
        UUID libraryId = UUID.randomUUID();
        LibraryEntity library = LibraryEntity.builder()
                .id(libraryId)
                .name("Podcasts")
                .libraryType(LibraryType.PODCAST)
                .build();
        AnalyzeData data = AnalyzeData.builder()
                .eventType(EventType.ANALYZE_DATA)
                .libraryId(libraryId)
                .build();

        when(libraryRepository.findById(libraryId)).thenReturn(Optional.of(library));

        subject.handle(data);

        verifyNoInteractions(messageSender, dispatcher);
    }

    @Test
    void handleShowIdSendsShowFoundAndEpisodeFanOut() {
        UUID showId = UUID.randomUUID();
        UUID episodeId1 = UUID.randomUUID();
        UUID episodeId2 = UUID.randomUUID();
        EpisodeEntity ep1 = EpisodeEntity.builder().id(episodeId1).build();
        EpisodeEntity ep2 = EpisodeEntity.builder().id(episodeId2).build();
        AnalyzeData data = AnalyzeData.builder()
                .eventType(EventType.ANALYZE_DATA)
                .showId(showId)
                .build();

        ShowEntity show = ShowEntity.builder().id(showId).metadataEntities(List.of()).imageEntities(List.of()).build();
        when(showRepository.findById(showId)).thenReturn(Optional.of(show));
        when(episodeRepository.findByShowEntityId(eq(showId), any(Sort.class))).thenReturn(List.of(ep1, ep2));

        subject.handle(data);

        verify(dispatcher).showFound(showId);
        ArgumentCaptor<AnalyzeData> captor = ArgumentCaptor.forClass(AnalyzeData.class);
        verify(messageSender, times(2)).sendAnalyzeData(captor.capture());
        List<UUID> sentEpisodeIds = captor.getAllValues().stream().map(AnalyzeData::getEpisodeId).toList();
        assertTrue(sentEpisodeIds.contains(episodeId1));
        assertTrue(sentEpisodeIds.contains(episodeId2));
    }

    @Test
    void handleEpisodeIdSendsEpisodeFoundAndDirectoryFanOut() {
        UUID episodeId = UUID.randomUUID();
        DirectoryEntity dir = DirectoryEntity.builder().id(UUID.randomUUID()).name("dir1").build();
        MediaFileEntity mf = MediaFileEntity.builder().build();
        mf.setDirectoryEntity(dir);
        EpisodeEntity episode = EpisodeEntity.builder().id(episodeId).build();
        AnalyzeData data = AnalyzeData.builder()
                .eventType(EventType.ANALYZE_DATA)
                .episodeId(episodeId)
                .build();

        when(episodeRepository.findById(episodeId)).thenReturn(Optional.of(episode));
        when(mediaFileRepository.findByEpisodeEntityId(episodeId)).thenReturn(List.of(mf));
        when(directoryRepository.findAll()).thenReturn(List.of(dir));

        subject.handle(data);

        verify(dispatcher).episodeFound(episodeId);
        ArgumentCaptor<AnalyzeData> captor = ArgumentCaptor.forClass(AnalyzeData.class);
        verify(messageSender).sendAnalyzeData(captor.capture(), eq("dir1"));
        assertEquals(episodeId, captor.getValue().getEpisodeId());
        assertEquals(dir.getId(), captor.getValue().getDirectoryId());
    }

    @Test
    void handleMovieIdSendsMovieFoundAndDirectoryFanOut() {
        UUID movieId = UUID.randomUUID();
        DirectoryEntity dir = DirectoryEntity.builder().id(UUID.randomUUID()).name("movies").build();
        MediaFileEntity mf = MediaFileEntity.builder().build();
        mf.setDirectoryEntity(dir);
        MovieEntity movie = MovieEntity.builder().id(movieId).build();
        AnalyzeData data = AnalyzeData.builder()
                .eventType(EventType.ANALYZE_DATA)
                .movieId(movieId)
                .build();

        when(movieRepository.findById(movieId)).thenReturn(Optional.of(movie));
        when(mediaFileRepository.findByMovieEntityId(movieId)).thenReturn(List.of(mf));
        when(directoryRepository.findAll()).thenReturn(List.of(dir));

        subject.handle(data);

        verify(dispatcher).movieFound(movieId);
        ArgumentCaptor<AnalyzeData> captor = ArgumentCaptor.forClass(AnalyzeData.class);
        verify(messageSender).sendAnalyzeData(captor.capture(), eq("movies"));
        assertEquals(movieId, captor.getValue().getMovieId());
        assertEquals(dir.getId(), captor.getValue().getDirectoryId());
    }

    @Test
    void handleLibraryIdMusicTypeSendsArtistFanOut() {
        UUID libraryId = UUID.randomUUID();
        UUID personId1 = UUID.randomUUID();
        UUID personId2 = UUID.randomUUID();
        LibraryEntity library = LibraryEntity.builder()
                .id(libraryId)
                .libraryType(LibraryType.MUSIC)
                .build();
        PersonEntity artist1 = PersonEntity.builder().id(personId1).build();
        PersonEntity artist2 = PersonEntity.builder().id(personId2).build();
        AnalyzeData data = AnalyzeData.builder()
                .eventType(EventType.ANALYZE_DATA)
                .libraryId(libraryId)
                .build();

        when(libraryRepository.findById(libraryId)).thenReturn(Optional.of(library));
        when(personRepository.findByLibraryEntityId(libraryId)).thenReturn(List.of(artist1, artist2));

        subject.handle(data);

        ArgumentCaptor<AnalyzeData> captor = ArgumentCaptor.forClass(AnalyzeData.class);
        verify(messageSender, times(2)).sendAnalyzeData(captor.capture());
        List<UUID> sentPersonIds = captor.getAllValues().stream().map(AnalyzeData::getPersonId).toList();
        assertTrue(sentPersonIds.contains(personId1));
        assertTrue(sentPersonIds.contains(personId2));
    }

    @Test
    void handlePersonIdSendsPersonFoundGloballyAndPerNodeAndFansOutToAlbumsAndBooks() {
        UUID personId = UUID.randomUUID();
        UUID albumId1 = UUID.randomUUID();
        UUID bookId1 = UUID.randomUUID();
        LibraryEntity library = LibraryEntity.builder().build();
        NodeEntity node = NodeEntity.builder().name("disk1").build();
        DirectoryEntity dir = DirectoryEntity.builder().nodeEntity(node).build();
        AlbumEntity album1 = AlbumEntity.builder().id(albumId1).build();
        PersonEntity artist = PersonEntity.builder()
                .id(personId)
                .libraryEntity(library)
                .metadataEntities(List.of())
                .imageEntities(List.of())
                .albumEntities(List.of(album1))
                .build();
        AnalyzeData data = AnalyzeData.builder()
                .eventType(EventType.ANALYZE_DATA)
                .personId(personId)
                .build();

        when(personRepository.findById(personId)).thenReturn(Optional.of(artist));
        when(albumRepository.findByPersonEntityId(personId)).thenReturn(List.of(album1));
        when(bookRepository.findByPersonEntityId(personId))
                .thenReturn(List.of(BookEntity.builder().id(bookId1).build()));
        when(directoryRepository.findByLibraryEntityAndDirectoryType(library, DirectoryType.LIBRARY))
                .thenReturn(List.of(dir));

        subject.handle(data);

        // Node-scoped alone only reaches the disk handler (artist.nfo); the global send is what
        // makes the MusicBrainz/Open Library/Wikipedia enrichment actually run on a force refresh.
        verify(dispatcher).personFoundToNode(personId, "disk1");
        verify(dispatcher).personFoundGlobal(personId);
        ArgumentCaptor<AnalyzeData> captor = ArgumentCaptor.forClass(AnalyzeData.class);
        verify(messageSender, times(2)).sendAnalyzeData(captor.capture());
        assertEquals(albumId1, captor.getAllValues().get(0).getAlbumId());
        assertEquals(bookId1, captor.getAllValues().get(1).getBookId());
    }

    @Test
    void handleAlbumIdSendsAlbumFoundAndTrackFanOut() {
        UUID albumId = UUID.randomUUID();
        UUID trackId1 = UUID.randomUUID();
        LibraryEntity library = LibraryEntity.builder().build();
        NodeEntity node = NodeEntity.builder().name("disk1").build();
        DirectoryEntity dir = DirectoryEntity.builder().nodeEntity(node).build();
        TrackEntity track1 = TrackEntity.builder().id(trackId1).build();
        AlbumEntity album = AlbumEntity.builder()
                .id(albumId)
                .libraryEntity(library)
                .metadataEntities(List.of())
                .imageEntities(List.of())
                .trackEntities(List.of(track1))
                .build();
        AnalyzeData data = AnalyzeData.builder()
                .eventType(EventType.ANALYZE_DATA)
                .albumId(albumId)
                .build();

        when(albumRepository.findById(albumId)).thenReturn(Optional.of(album));
        when(trackRepository.findByAlbumEntity_Id(eq(albumId), any(Sort.class))).thenReturn(List.of(track1));
        when(directoryRepository.findByLibraryEntityAndDirectoryType(library, DirectoryType.LIBRARY))
                .thenReturn(List.of(dir));

        subject.handle(data);

        verify(dispatcher).albumFoundToNode(albumId, "disk1");
        verify(dispatcher).albumFoundGlobal(albumId);
        ArgumentCaptor<AnalyzeData> captor = ArgumentCaptor.forClass(AnalyzeData.class);
        verify(messageSender).sendAnalyzeData(captor.capture());
        assertEquals(trackId1, captor.getValue().getTrackId());
    }

    @Test
    void handleAlbumIdDefersSendsUntilAfterCommit() {
        UUID albumId = UUID.randomUUID();
        LibraryEntity library = LibraryEntity.builder().build();
        NodeEntity node = NodeEntity.builder().name("disk1").build();
        DirectoryEntity dir = DirectoryEntity.builder().nodeEntity(node).build();
        AlbumEntity album = AlbumEntity.builder()
                .id(albumId)
                .libraryEntity(library)
                .metadataEntities(List.of())
                .imageEntities(List.of())
                .trackEntities(List.of())
                .build();
        AnalyzeData data = AnalyzeData.builder()
                .eventType(EventType.ANALYZE_DATA)
                .albumId(albumId)
                .build();

        when(albumRepository.findById(albumId)).thenReturn(Optional.of(album));
        when(trackRepository.findByAlbumEntity_Id(eq(albumId), any(Sort.class))).thenReturn(List.of());
        when(directoryRepository.findByLibraryEntityAndDirectoryType(library, DirectoryType.LIBRARY))
                .thenReturn(List.of(dir));

        TransactionSynchronizationManager.initSynchronization();
        try {
            subject.handle(data);

            // The album-found consumers check for existing metadata/image rows: publishing before
            // the delete commits makes them skip the refetch and the album ends up cover-less.
            verify(dispatcher, never()).albumFoundGlobal(any());
            verify(dispatcher, never()).albumFoundToNode(any(), any());

            TransactionSynchronizationManager.getSynchronizations().forEach(TransactionSynchronization::afterCommit);
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }

        verify(dispatcher).albumFoundGlobal(albumId);
        verify(dispatcher).albumFoundToNode(albumId, "disk1");
    }

    @Test
    void handleTrackIdSendsAudioFileFoundForEachMediaFile() {
        UUID trackId = UUID.randomUUID();
        DirectoryEntity dir = DirectoryEntity.builder().id(UUID.randomUUID()).name("music-dir").build();
        MediaFileEntity mf = MediaFileEntity.builder()
                .path("/music/track.mp3")
                .build();
        mf.setDirectoryEntity(dir);
        TrackEntity track = TrackEntity.builder().id(trackId).build();
        AnalyzeData data = AnalyzeData.builder()
                .eventType(EventType.ANALYZE_DATA)
                .trackId(trackId)
                .build();

        when(trackRepository.findById(trackId)).thenReturn(Optional.of(track));
        when(mediaFileRepository.findByTrackEntityId(trackId)).thenReturn(List.of(mf));
        when(directoryRepository.findAll()).thenReturn(List.of(dir));

        subject.handle(data);

        verify(messageSender).sendAudioFileFound(any(), eq("music-dir"));
    }

    @Test
    void handleTrackIdSkipsMediaFileWithNullDirectory() {
        UUID trackId = UUID.randomUUID();
        MediaFileEntity mfNoDir = MediaFileEntity.builder()
                .path("/music/track.mp3")
                .build(); // no directoryEntity set
        TrackEntity track = TrackEntity.builder().id(trackId).build();
        AnalyzeData data = AnalyzeData.builder()
                .eventType(EventType.ANALYZE_DATA)
                .trackId(trackId)
                .build();

        when(trackRepository.findById(trackId)).thenReturn(Optional.of(track));
        when(mediaFileRepository.findByTrackEntityId(trackId)).thenReturn(List.of(mfNoDir));

        subject.handle(data);

        verify(messageSender, times(0)).sendAudioFileFound(any(), any());
    }

    @Test
    void handleBookIdReparsesEpubsAfterWipingMetadata() {
        UUID bookId = UUID.randomUUID();
        DirectoryEntity dir = DirectoryEntity.builder().id(UUID.randomUUID()).name("books-dir").build();
        BookEntity book = BookEntity.builder().id(bookId).name("Book").build();
        MediaFileEntity epub = MediaFileEntity.builder().path("/books/Author/Book.epub").size(1L).build();
        epub.setId(UUID.randomUUID());
        epub.setDirectoryEntity(dir);
        AnalyzeData data = AnalyzeData.builder()
                .eventType(EventType.ANALYZE_DATA)
                .bookId(bookId)
                .build();

        when(bookRepository.findById(bookId)).thenReturn(Optional.of(book));
        when(mediaFileRepository.findByBookEntityId(bookId)).thenReturn(List.of(epub));
        when(directoryRepository.findAll()).thenReturn(List.of(dir));

        subject.handle(data);

        verify(metadataRepository).deleteAll(any());
        verify(imageRepository).deleteAll(any());
        // The epub re-parse chains BOOK_FOUND itself after storing the ISBN.
        verify(dispatcher).epubFileFound(dir, bookId, epub);
        verify(dispatcher, never()).bookFound(any());
    }

    @Test
    void handleBookIdWithoutEpubsSendsBookFoundDirectly() {
        UUID bookId = UUID.randomUUID();
        BookEntity audiobookOnly = BookEntity.builder().id(bookId).name("Audiobook").build();
        AnalyzeData data = AnalyzeData.builder()
                .eventType(EventType.ANALYZE_DATA)
                .bookId(bookId)
                .build();

        when(bookRepository.findById(bookId)).thenReturn(Optional.of(audiobookOnly));
        when(mediaFileRepository.findByBookEntityId(bookId)).thenReturn(List.of());

        subject.handle(data);

        verify(dispatcher).bookFound(bookId);
    }

    @Test
    void handleSeriesIdSendsComicSeriesFoundAndReparsesEveryVolume() {
        UUID seriesId = UUID.randomUUID();
        UUID volumeId = UUID.randomUUID();
        DirectoryEntity dir = DirectoryEntity.builder().id(UUID.randomUUID()).name("comics-dir").build();
        SeriesEntity series = SeriesEntity.builder().id(seriesId).name("Series").build();
        BookEntity volume = BookEntity.builder().id(volumeId).name("Vol 1").build();
        MediaFileEntity cbz = MediaFileEntity.builder().path("/comics/Series/Vol 1.cbz").size(1L).build();
        cbz.setId(UUID.randomUUID());
        cbz.setDirectoryEntity(dir);
        AnalyzeData data = AnalyzeData.builder()
                .eventType(EventType.ANALYZE_DATA)
                .seriesId(seriesId)
                .build();

        when(seriesRepository.findById(seriesId)).thenReturn(Optional.of(series));
        when(bookRepository.findBySeriesEntityId(seriesId)).thenReturn(List.of(volume));
        when(mediaFileRepository.findByBookEntityId(volumeId)).thenReturn(List.of(cbz));
        when(directoryRepository.findAll()).thenReturn(List.of(dir));

        subject.handle(data);

        verify(dispatcher).comicSeriesFound(seriesId);
        verify(dispatcher).comicVolumeFileFound(dir, volumeId, cbz);
    }
}
