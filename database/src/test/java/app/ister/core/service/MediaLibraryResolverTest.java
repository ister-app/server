package app.ister.core.service;

import app.ister.core.entity.AlbumEntity;
import app.ister.core.entity.BookEntity;
import app.ister.core.entity.ChapterEntity;
import app.ister.core.entity.EpisodeEntity;
import app.ister.core.entity.GameEntity;
import app.ister.core.entity.ImageEntity;
import app.ister.core.entity.LibraryEntity;
import app.ister.core.entity.MediaFileEntity;
import app.ister.core.entity.MovieEntity;
import app.ister.core.entity.PlayQueueItemEntity;
import app.ister.core.entity.PlaylistEntity;
import app.ister.core.entity.PodcastEntity;
import app.ister.core.entity.PodcastEpisodeEntity;
import app.ister.core.entity.SeasonEntity;
import app.ister.core.entity.SeriesEntity;
import app.ister.core.entity.ShowEntity;
import app.ister.core.entity.TrackEntity;
import app.ister.core.enums.MediaType;
import app.ister.core.enums.PlayQueueSourceType;
import app.ister.core.repository.AlbumRepository;
import app.ister.core.repository.BookRepository;
import app.ister.core.repository.ChapterRepository;
import app.ister.core.repository.EpisodeRepository;
import app.ister.core.repository.GameRepository;
import app.ister.core.repository.ImageRepository;
import app.ister.core.repository.LibraryRepository;
import app.ister.core.repository.MediaFileRepository;
import app.ister.core.repository.MovieRepository;
import app.ister.core.repository.PlaylistRepository;
import app.ister.core.repository.PodcastEpisodeRepository;
import app.ister.core.repository.PodcastRepository;
import app.ister.core.repository.ShowRepository;
import app.ister.core.repository.TrackRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MediaLibraryResolverTest {

    @InjectMocks
    private MediaLibraryResolver subject;

    @Mock
    private MovieRepository movieRepository;
    @Mock
    private ShowRepository showRepository;
    @Mock
    private AlbumRepository albumRepository;
    @Mock
    private BookRepository bookRepository;
    @Mock
    private PodcastRepository podcastRepository;
    @Mock
    private LibraryRepository libraryRepository;
    @Mock
    private MediaFileRepository mediaFileRepository;
    @Mock
    private ImageRepository imageRepository;
    @Mock
    private EpisodeRepository episodeRepository;
    @Mock
    private TrackRepository trackRepository;
    @Mock
    private ChapterRepository chapterRepository;
    @Mock
    private PodcastEpisodeRepository podcastEpisodeRepository;
    @Mock
    private PlaylistRepository playlistRepository;
    @Mock
    private GameRepository gameRepository;

    private LibraryEntity library;
    private UUID id;

    @BeforeEach
    void setUp() {
        library = LibraryEntity.builder().name("Library").build();
        library.setId(UUID.randomUUID());
        id = UUID.randomUUID();
    }

    private MovieEntity movie() {
        return MovieEntity.builder().libraryEntity(library).build();
    }

    private ShowEntity show() {
        return ShowEntity.builder().libraryEntity(library).build();
    }

    private EpisodeEntity episode() {
        return EpisodeEntity.builder().showEntity(show()).build();
    }

    private AlbumEntity album() {
        return AlbumEntity.builder().libraryEntity(library).build();
    }

    private BookEntity book() {
        return BookEntity.builder().libraryEntity(library).build();
    }

    private PodcastEntity podcast() {
        return PodcastEntity.builder().libraryEntity(library).build();
    }

    private PodcastEpisodeEntity podcastEpisode() {
        return PodcastEpisodeEntity.builder().podcastEntity(podcast()).build();
    }

    private GameEntity game() {
        return GameEntity.builder().libraryEntity(library).build();
    }

    // ---- ofMediaFileId / ofGameId / ofImageId -------------------------------------------------

    @Test
    void ofMediaFileIdResolvesThroughTheMediaFile() {
        when(mediaFileRepository.findById(id)).thenReturn(Optional.of(MediaFileEntity.builder().movieEntity(movie()).build()));

        assertSame(library, subject.ofMediaFileId(id).orElseThrow());
    }

    @Test
    void ofMediaFileIdIsEmptyForUnknownId() {
        when(mediaFileRepository.findById(id)).thenReturn(Optional.empty());

        assertTrue(subject.ofMediaFileId(id).isEmpty());
    }

    @Test
    void ofGameIdResolvesTheGameLibrary() {
        when(gameRepository.findById(id)).thenReturn(Optional.of(game()));

        assertSame(library, subject.ofGameId(id).orElseThrow());
    }

    @Test
    void ofGameIdIsEmptyForUnknownId() {
        when(gameRepository.findById(id)).thenReturn(Optional.empty());

        assertTrue(subject.ofGameId(id).isEmpty());
    }

    @Test
    void ofImageIdResolvesThroughTheImage() {
        when(imageRepository.findById(id)).thenReturn(Optional.of(ImageEntity.builder().showEntity(show()).build()));

        assertSame(library, subject.ofImageId(id).orElseThrow());
    }

    @Test
    void ofImageIdIsEmptyForUnknownId() {
        when(imageRepository.findById(id)).thenReturn(Optional.empty());

        assertTrue(subject.ofImageId(id).isEmpty());
    }

    // ---- ofSource -------------------------------------------------------------------------------

    @Test
    void ofSourceMovie() {
        when(movieRepository.findById(id)).thenReturn(Optional.of(movie()));

        assertSame(library, subject.ofSource(PlayQueueSourceType.MOVIE, id).orElseThrow());
    }

    @Test
    void ofSourceShow() {
        when(showRepository.findById(id)).thenReturn(Optional.of(show()));

        assertSame(library, subject.ofSource(PlayQueueSourceType.SHOW, id).orElseThrow());
    }

    @Test
    void ofSourceAlbum() {
        when(albumRepository.findById(id)).thenReturn(Optional.of(album()));

        assertSame(library, subject.ofSource(PlayQueueSourceType.ALBUM, id).orElseThrow());
    }

    @Test
    void ofSourceBook() {
        when(bookRepository.findById(id)).thenReturn(Optional.of(book()));

        assertSame(library, subject.ofSource(PlayQueueSourceType.BOOK, id).orElseThrow());
    }

    @Test
    void ofSourcePodcast() {
        when(podcastRepository.findById(id)).thenReturn(Optional.of(podcast()));

        assertSame(library, subject.ofSource(PlayQueueSourceType.PODCAST, id).orElseThrow());
    }

    @Test
    void ofSourceLibraryIsTheLibraryItself() {
        when(libraryRepository.findById(id)).thenReturn(Optional.of(library));

        assertSame(library, subject.ofSource(PlayQueueSourceType.LIBRARY, id).orElseThrow());
    }

    @Test
    void ofSourcePlaylist() {
        when(playlistRepository.findById(id)).thenReturn(Optional.of(PlaylistEntity.builder().libraryEntity(library).build()));

        assertSame(library, subject.ofSource(PlayQueueSourceType.PLAYLIST, id).orElseThrow());
    }

    @Test
    void ofSourceDeletedPlaylistIsEmpty() {
        when(playlistRepository.findById(id)).thenReturn(Optional.empty());

        assertTrue(subject.ofSource(PlayQueueSourceType.PLAYLIST, id).isEmpty());
    }

    @Test
    void ofSourceUnknownMovieIsEmpty() {
        when(movieRepository.findById(id)).thenReturn(Optional.empty());

        assertTrue(subject.ofSource(PlayQueueSourceType.MOVIE, id).isEmpty());
    }

    @ParameterizedTest
    @EnumSource(value = PlayQueueSourceType.class, names = {"ARTIST", "FILTER"})
    void ofSourceArtistAndFilterSpanLibrariesAndResolveEmptyWithoutLookups(PlayQueueSourceType type) {
        assertEquals(Optional.empty(), subject.ofSource(type, id));
        verifyNoInteractions(movieRepository, showRepository, albumRepository, bookRepository, podcastRepository,
                libraryRepository, playlistRepository);
    }

    // ---- ofPlayQueueItem -----------------------------------------------------------------------

    private PlayQueueItemEntity item(MediaType type) {
        return PlayQueueItemEntity.builder().type(type).build();
    }

    @Test
    void ofPlayQueueItemMovie() {
        PlayQueueItemEntity item = item(MediaType.MOVIE);
        item.setMovieEntityId(id);
        when(movieRepository.findById(id)).thenReturn(Optional.of(movie()));

        assertSame(library, subject.ofPlayQueueItem(item).orElseThrow());
    }

    @Test
    void ofPlayQueueItemMovieWithoutIdIsEmpty() {
        assertTrue(subject.ofPlayQueueItem(item(MediaType.MOVIE)).isEmpty());
        verifyNoInteractions(movieRepository);
    }

    @Test
    void ofPlayQueueItemDeletedMovieIsEmpty() {
        PlayQueueItemEntity item = item(MediaType.MOVIE);
        item.setMovieEntityId(id);
        when(movieRepository.findById(id)).thenReturn(Optional.empty());

        assertTrue(subject.ofPlayQueueItem(item).isEmpty());
    }

    @Test
    void ofPlayQueueItemEpisodeWalksToTheShow() {
        PlayQueueItemEntity item = item(MediaType.EPISODE);
        item.setEpisodeEntityId(id);
        when(episodeRepository.findById(id)).thenReturn(Optional.of(episode()));

        assertSame(library, subject.ofPlayQueueItem(item).orElseThrow());
    }

    @Test
    void ofPlayQueueItemEpisodeWithoutIdIsEmpty() {
        assertTrue(subject.ofPlayQueueItem(item(MediaType.EPISODE)).isEmpty());
        verifyNoInteractions(episodeRepository);
    }

    @Test
    void ofPlayQueueItemTrackWalksToTheAlbum() {
        PlayQueueItemEntity item = item(MediaType.TRACK);
        item.setTrackEntityId(id);
        when(trackRepository.findById(id)).thenReturn(Optional.of(TrackEntity.builder().albumEntity(album()).build()));

        assertSame(library, subject.ofPlayQueueItem(item).orElseThrow());
    }

    @Test
    void ofPlayQueueItemTrackWithoutIdIsEmpty() {
        assertTrue(subject.ofPlayQueueItem(item(MediaType.TRACK)).isEmpty());
        verifyNoInteractions(trackRepository);
    }

    @Test
    void ofPlayQueueItemChapterWalksToTheBook() {
        PlayQueueItemEntity item = item(MediaType.CHAPTER);
        item.setChapterEntityId(id);
        when(chapterRepository.findById(id)).thenReturn(Optional.of(ChapterEntity.builder().bookEntity(book()).build()));

        assertSame(library, subject.ofPlayQueueItem(item).orElseThrow());
    }

    @Test
    void ofPlayQueueItemChapterWithoutIdIsEmpty() {
        assertTrue(subject.ofPlayQueueItem(item(MediaType.CHAPTER)).isEmpty());
        verifyNoInteractions(chapterRepository);
    }

    @Test
    void ofPlayQueueItemPodcastEpisodeWalksToThePodcast() {
        PlayQueueItemEntity item = item(MediaType.PODCAST_EPISODE);
        item.setPodcastEpisodeEntityId(id);
        when(podcastEpisodeRepository.findById(id)).thenReturn(Optional.of(podcastEpisode()));

        assertSame(library, subject.ofPlayQueueItem(item).orElseThrow());
    }

    @Test
    void ofPlayQueueItemPodcastEpisodeWithoutIdIsEmpty() {
        assertTrue(subject.ofPlayQueueItem(item(MediaType.PODCAST_EPISODE)).isEmpty());
        verifyNoInteractions(podcastEpisodeRepository);
    }

    @ParameterizedTest
    @EnumSource(value = MediaType.class, names = {"BOOK", "COMIC", "GAME"})
    void ofPlayQueueItemNonPlayableTypesResolveEmpty(MediaType type) {
        assertEquals(Optional.empty(), subject.ofPlayQueueItem(item(type)));
    }

    // ---- ofMediaFile -----------------------------------------------------------------------------

    @Test
    void ofMediaFileNullIsEmpty() {
        assertTrue(subject.ofMediaFile(null).isEmpty());
    }

    @Test
    void ofMediaFileWithoutAnyParentIsEmpty() {
        assertTrue(subject.ofMediaFile(MediaFileEntity.builder().build()).isEmpty());
    }

    @Test
    void ofMediaFileMovie() {
        assertSame(library, subject.ofMediaFile(MediaFileEntity.builder().movieEntity(movie()).build()).orElseThrow());
    }

    @Test
    void ofMediaFileMovieWithoutLibraryIsEmpty() {
        MediaFileEntity mediaFile = MediaFileEntity.builder().movieEntity(MovieEntity.builder().build()).build();

        assertTrue(subject.ofMediaFile(mediaFile).isEmpty());
    }

    @Test
    void ofMediaFileEpisode() {
        assertSame(library, subject.ofMediaFile(MediaFileEntity.builder().episodeEntity(episode()).build()).orElseThrow());
    }

    @Test
    void ofMediaFileTrack() {
        TrackEntity track = TrackEntity.builder().albumEntity(album()).build();

        assertSame(library, subject.ofMediaFile(MediaFileEntity.builder().trackEntity(track).build()).orElseThrow());
    }

    @Test
    void ofMediaFileBook() {
        assertSame(library, subject.ofMediaFile(MediaFileEntity.builder().bookEntity(book()).build()).orElseThrow());
    }

    @Test
    void ofMediaFileChapter() {
        ChapterEntity chapter = ChapterEntity.builder().bookEntity(book()).build();

        assertSame(library, subject.ofMediaFile(MediaFileEntity.builder().chapterEntity(chapter).build()).orElseThrow());
    }

    @Test
    void ofMediaFilePodcastEpisode() {
        MediaFileEntity mediaFile = MediaFileEntity.builder().podcastEpisodeEntity(podcastEpisode()).build();

        assertSame(library, subject.ofMediaFile(mediaFile).orElseThrow());
    }

    @Test
    void ofMediaFileGame() {
        assertSame(library, subject.ofMediaFile(MediaFileEntity.builder().gameEntity(game()).build()).orElseThrow());
    }

    @Test
    void ofMediaFilePrefersTheMovieOverLaterParents() {
        LibraryEntity other = LibraryEntity.builder().name("Other").build();
        other.setId(UUID.randomUUID());
        MediaFileEntity mediaFile = MediaFileEntity.builder()
                .movieEntity(movie())
                .bookEntity(BookEntity.builder().libraryEntity(other).build())
                .build();

        assertSame(library, subject.ofMediaFile(mediaFile).orElseThrow());
    }

    // ---- ofImage ---------------------------------------------------------------------------------

    @Test
    void ofImageNullIsEmpty() {
        assertTrue(subject.ofImage(null).isEmpty());
    }

    @Test
    void ofImageOfAPersonHasNoLibrary() {
        ImageEntity image = ImageEntity.builder().build();
        image.setPersonEntity(app.ister.core.entity.PersonEntity.builder().name("Actor").build());

        assertTrue(subject.ofImage(image).isEmpty());
    }

    @Test
    void ofImageMovie() {
        assertSame(library, subject.ofImage(ImageEntity.builder().movieEntity(movie()).build()).orElseThrow());
    }

    @Test
    void ofImageMovieWithoutLibraryIsEmpty() {
        ImageEntity image = ImageEntity.builder().movieEntity(MovieEntity.builder().build()).build();

        assertTrue(subject.ofImage(image).isEmpty());
    }

    @Test
    void ofImageShow() {
        assertSame(library, subject.ofImage(ImageEntity.builder().showEntity(show()).build()).orElseThrow());
    }

    @Test
    void ofImageSeasonWalksToTheShow() {
        SeasonEntity season = SeasonEntity.builder().showEntity(show()).build();

        assertSame(library, subject.ofImage(ImageEntity.builder().seasonEntity(season).build()).orElseThrow());
    }

    @Test
    void ofImageEpisodeWalksToTheShow() {
        assertSame(library, subject.ofImage(ImageEntity.builder().episodeEntity(episode()).build()).orElseThrow());
    }

    @Test
    void ofImageAlbum() {
        assertSame(library, subject.ofImage(ImageEntity.builder().albumEntity(album()).build()).orElseThrow());
    }

    @Test
    void ofImageBook() {
        assertSame(library, subject.ofImage(ImageEntity.builder().bookEntity(book()).build()).orElseThrow());
    }

    @Test
    void ofImageSeries() {
        SeriesEntity series = SeriesEntity.builder().libraryEntity(library).build();

        assertSame(library, subject.ofImage(ImageEntity.builder().seriesEntity(series).build()).orElseThrow());
    }

    @Test
    void ofImagePodcast() {
        assertSame(library, subject.ofImage(ImageEntity.builder().podcastEntity(podcast()).build()).orElseThrow());
    }

    @Test
    void ofImagePodcastEpisodeWalksToThePodcast() {
        ImageEntity image = ImageEntity.builder().podcastEpisodeEntity(podcastEpisode()).build();

        assertSame(library, subject.ofImage(image).orElseThrow());
    }

    @Test
    void ofImageGame() {
        assertSame(library, subject.ofImage(ImageEntity.builder().gameEntity(game()).build()).orElseThrow());
    }
}
