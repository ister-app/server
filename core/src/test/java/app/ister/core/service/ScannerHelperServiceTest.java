package app.ister.core.service;

import app.ister.core.entity.*;
import app.ister.core.repository.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ScannerHelperServiceTest {

    @InjectMocks
    private ScannerHelperService subject;

    @Mock
    private MovieRepository movieRepository;
    @Mock
    private ShowRepository showRepository;
    @Mock
    private SeasonRepository seasonRepository;
    @Mock
    private EpisodeRepository episodeRepository;
    @Mock
    private PersonRepository personRepository;
    @Mock
    private AlbumRepository albumRepository;
    @Mock
    private TrackRepository trackRepository;
    @Mock
    private BookRepository bookRepository;
    @Mock
    private ChapterRepository chapterRepository;
    @Mock
    private ServerEventService serverEventService;

    private final LibraryEntity library = LibraryEntity.builder().build();

    @Test
    void getOrCreateMovieReturnsExistingMovie() {
        MovieEntity existing = MovieEntity.builder().name("Movie").releaseYear(2024).build();
        when(movieRepository.findByLibraryEntityAndNameAndReleaseYear(library, "Movie", 2024))
                .thenReturn(Optional.of(existing));

        assertEquals(existing, subject.getOrCreateMovie(library, "Movie", 2024));
        verify(movieRepository, never()).save(any());
        verifyNoInteractions(serverEventService);
    }

    @Test
    void getOrCreateMovieCreatesNewMovie() {
        when(movieRepository.findByLibraryEntityAndNameAndReleaseYear(library, "Movie", 2024))
                .thenReturn(Optional.empty());

        MovieEntity result = subject.getOrCreateMovie(library, "Movie", 2024);

        assertEquals("Movie", result.getName());
        assertEquals(2024, result.getReleaseYear());
        verify(movieRepository).save(result);
        verify(serverEventService).createMovieFoundEvent(result.getId());
    }

    @Test
    void getOrCreateShowReturnsExistingShow() {
        ShowEntity existing = ShowEntity.builder().name("Show").releaseYear(2024).build();
        when(showRepository.findByLibraryEntityAndNameAndReleaseYear(library, "Show", 2024))
                .thenReturn(Optional.of(existing));

        assertEquals(existing, subject.getOrCreateShow(library, "Show", 2024));
        verify(showRepository, never()).save(any());
        verifyNoInteractions(serverEventService);
    }

    @Test
    void getOrCreateShowCreatesNewShow() {
        when(showRepository.findByLibraryEntityAndNameAndReleaseYear(library, "Show", 2024))
                .thenReturn(Optional.empty());

        ShowEntity result = subject.getOrCreateShow(library, "Show", 2024);

        assertEquals("Show", result.getName());
        assertEquals(2024, result.getReleaseYear());
        verify(showRepository).save(result);
        verify(serverEventService).createShowFoundEvent(result.getId());
    }

    @Test
    void getOrCreateSeasonReturnsExistingSeason() {
        ShowEntity show = ShowEntity.builder().build();
        SeasonEntity existing = SeasonEntity.builder().number(1).build();
        when(showRepository.findByLibraryEntityAndNameAndReleaseYear(library, "Show", 2024))
                .thenReturn(Optional.of(show));
        when(seasonRepository.findByShowEntityAndNumber(show, 1))
                .thenReturn(Optional.of(existing));

        assertEquals(existing, subject.getOrCreateSeason(library, "Show", 2024, 1));
        verify(seasonRepository, never()).save(any());
    }

    @Test
    void getOrCreateSeasonCreatesNewSeason() {
        ShowEntity show = ShowEntity.builder().build();
        when(showRepository.findByLibraryEntityAndNameAndReleaseYear(library, "Show", 2024))
                .thenReturn(Optional.of(show));
        when(seasonRepository.findByShowEntityAndNumber(show, 1))
                .thenReturn(Optional.empty());

        SeasonEntity result = subject.getOrCreateSeason(library, "Show", 2024, 1);

        assertEquals(1, result.getNumber());
        verify(seasonRepository).save(result);
    }

    @Test
    void getOrCreateEpisodeReturnsExistingEpisode() {
        ShowEntity show = ShowEntity.builder().build();
        SeasonEntity season = SeasonEntity.builder().number(1).build();
        EpisodeEntity existing = EpisodeEntity.builder().number(1).build();
        when(showRepository.findByLibraryEntityAndNameAndReleaseYear(library, "Show", 2024))
                .thenReturn(Optional.of(show));
        when(seasonRepository.findByShowEntityAndNumber(show, 1))
                .thenReturn(Optional.of(season));
        when(episodeRepository.findByShowEntityAndSeasonEntityAndNumber(show, season, 1))
                .thenReturn(Optional.of(existing));

        assertEquals(existing, subject.getOrCreateEpisode(library, "Show", 2024, 1, 1));
        verify(episodeRepository, never()).save(any());
        verifyNoInteractions(serverEventService);
    }

    @Test
    void getOrCreateEpisodeCreatesNewEpisode() {
        ShowEntity show = ShowEntity.builder().build();
        SeasonEntity season = SeasonEntity.builder().number(1).build();
        when(showRepository.findByLibraryEntityAndNameAndReleaseYear(library, "Show", 2024))
                .thenReturn(Optional.of(show));
        when(seasonRepository.findByShowEntityAndNumber(show, 1))
                .thenReturn(Optional.of(season));
        when(episodeRepository.findByShowEntityAndSeasonEntityAndNumber(show, season, 1))
                .thenReturn(Optional.empty());

        EpisodeEntity result = subject.getOrCreateEpisode(library, "Show", 2024, 1, 1);

        assertEquals(1, result.getNumber());
        verify(episodeRepository).save(result);
        verify(serverEventService).createEpisodeFoundEvent(result.getId());
    }

    @Test
    void getOrCreatePersonReturnsExistingArtist() {
        PersonEntity existing = PersonEntity.builder().name("The Beatles").build();
        when(personRepository.findByLibraryEntityAndName(library, "The Beatles"))
                .thenReturn(Optional.of(existing));

        assertEquals(existing, subject.getOrCreatePerson(library, "The Beatles"));
        verify(personRepository, never()).save(any());
        verify(serverEventService, never()).createPersonFoundEvent(any());
    }

    @Test
    void getOrCreatePersonCreatesNewArtist() {
        when(personRepository.findByLibraryEntityAndName(library, "The Beatles"))
                .thenReturn(Optional.empty());

        PersonEntity result = subject.getOrCreatePerson(library, "The Beatles");

        assertEquals("The Beatles", result.getName());
        verify(personRepository).save(result);
        verify(serverEventService).createPersonFoundEvent(result.getId());
    }

    @Test
    void getOrCreatePersonClaimsLibrarylessTmdbPerson() {
        PersonEntity actor = PersonEntity.builder().name("Lady Gaga").tmdbId(90633L).build();
        when(personRepository.findByLibraryEntityAndName(library, "Lady Gaga"))
                .thenReturn(Optional.empty());
        when(personRepository.findFirstByNameAndLibraryEntityIsNull("Lady Gaga"))
                .thenReturn(Optional.of(actor));

        PersonEntity result = subject.getOrCreatePerson(library, "Lady Gaga");

        assertEquals(actor, result);
        assertEquals(library, result.getLibraryEntity());
        verify(personRepository).save(actor);
        verify(serverEventService).createPersonFoundEvent(actor.getId());
    }

    @Test
    void getOrCreatePersonSeedsBirthYearFromFolderOnNewArtist() {
        when(personRepository.findByLibraryEntityAndName(library, "Ariana Grande"))
                .thenReturn(Optional.empty());
        when(personRepository.findFirstByNameAndLibraryEntityIsNull("Ariana Grande"))
                .thenReturn(Optional.empty());

        PersonEntity result = subject.getOrCreatePerson(library, "Ariana Grande", 1993);

        assertEquals(1993, result.getBirthYear());
        verify(personRepository).save(result);
        verify(serverEventService).createPersonFoundEvent(result.getId());
    }

    @Test
    void getOrCreatePersonFillsMissingBirthYearOnExistingArtist() {
        PersonEntity existing = PersonEntity.builder().name("Ariana Grande").build();
        when(personRepository.findByLibraryEntityAndName(library, "Ariana Grande"))
                .thenReturn(Optional.of(existing));

        PersonEntity result = subject.getOrCreatePerson(library, "Ariana Grande", 1993);

        assertEquals(1993, result.getBirthYear());
        verify(personRepository).save(existing);
    }

    @Test
    void getOrCreatePersonDoesNotOverrideExistingBirthYear() {
        PersonEntity existing = PersonEntity.builder().name("Ariana Grande").birthYear(1990).build();
        when(personRepository.findByLibraryEntityAndName(library, "Ariana Grande"))
                .thenReturn(Optional.of(existing));

        PersonEntity result = subject.getOrCreatePerson(library, "Ariana Grande", 1993);

        assertEquals(1990, result.getBirthYear());
        verify(personRepository, never()).save(any());
    }

    @Test
    void getOrCreateAlbumReturnsExistingAlbum() {
        PersonEntity artist = PersonEntity.builder().name("The Beatles").build();
        AlbumEntity existing = AlbumEntity.builder().name("Abbey Road").releaseYear(1969).build();
        when(albumRepository.findByPersonEntityAndNameAndReleaseYear(artist, "Abbey Road", 1969))
                .thenReturn(Optional.of(existing));

        assertEquals(existing, subject.getOrCreateAlbum(library, artist, "Abbey Road", 1969));
        verify(albumRepository, never()).save(any());
        verify(serverEventService, never()).createAlbumFoundEvent(any());
    }

    /**
     * The cover scanner reaches this method with the year the directory name carries, which is 0 for
     * almost every directory. Matching on that 0 against an album whose year came from the audio tags
     * is what used to spawn a second, empty album per cover.
     */
    @Test
    void getOrCreateAlbumMatchesOnNameAloneWhenDirectoryHasNoYear() {
        PersonEntity artist = PersonEntity.builder().name("Adele").build();
        AlbumEntity existing = AlbumEntity.builder().name("19").releaseYear(2008).build();
        when(albumRepository.findFirstByPersonEntityAndNameOrderByDateCreatedAsc(artist, "19"))
                .thenReturn(Optional.of(existing));

        assertEquals(existing, subject.getOrCreateAlbum(library, artist, "19", 0));
        verify(albumRepository, never()).findByPersonEntityAndNameAndReleaseYear(any(), any(), anyInt());
        verify(albumRepository, never()).save(any());
        verify(serverEventService, never()).createAlbumFoundEvent(any());
    }

    @Test
    void getOrCreateAlbumSeparatesSameNamedAlbumsWhenDirectoriesCarryAYear() {
        PersonEntity artist = PersonEntity.builder().name("The Great Park").build();
        AlbumEntity from2010 = AlbumEntity.builder().name("Simple Folk Recording").releaseYear(2010).build();
        when(albumRepository.findByPersonEntityAndNameAndReleaseYear(artist, "Simple Folk Recording", 2010))
                .thenReturn(Optional.of(from2010));
        when(albumRepository.findByPersonEntityAndNameAndReleaseYear(artist, "Simple Folk Recording", 2013))
                .thenReturn(Optional.empty());

        assertEquals(from2010, subject.getOrCreateAlbum(library, artist, "Simple Folk Recording", 2010));
        AlbumEntity from2013 = subject.getOrCreateAlbum(library, artist, "Simple Folk Recording", 2013);

        assertEquals(2013, from2013.getReleaseYear());
        verify(albumRepository).save(from2013);
        verify(serverEventService).createAlbumFoundEvent(from2013.getId());
    }

    @Test
    void getOrCreateAlbumCreatesNewAlbum() {
        PersonEntity artist = PersonEntity.builder().name("The Beatles").build();
        when(albumRepository.findByPersonEntityAndNameAndReleaseYear(artist, "Abbey Road", 1969))
                .thenReturn(Optional.empty());

        AlbumEntity result = subject.getOrCreateAlbum(library, artist, "Abbey Road", 1969);

        assertEquals("Abbey Road", result.getName());
        assertEquals(1969, result.getReleaseYear());
        verify(albumRepository).save(result);
        verify(serverEventService).createAlbumFoundEvent(result.getId());
    }

    @Test
    void getOrCreateTrackReturnsExistingTrack() {
        PersonEntity artist = PersonEntity.builder().build();
        AlbumEntity album = AlbumEntity.builder().build();
        TrackEntity existing = TrackEntity.builder().number(1).discNumber(1).build();
        when(trackRepository.findByAlbumEntityAndNumberAndDiscNumber(album, 1, 1))
                .thenReturn(Optional.of(existing));

        assertEquals(existing, subject.getOrCreateTrack(artist, album, 1, 1));
        verify(trackRepository, never()).save(any());
        verify(serverEventService, never()).createTrackFoundEvent(any());
    }

    @Test
    void getOrCreateTrackCreatesNewTrack() {
        PersonEntity artist = PersonEntity.builder().build();
        AlbumEntity album = AlbumEntity.builder().build();
        when(trackRepository.findByAlbumEntityAndNumberAndDiscNumber(album, 1, 1))
                .thenReturn(Optional.empty());

        TrackEntity result = subject.getOrCreateTrack(artist, album, 1, 1);

        assertEquals(1, result.getNumber());
        assertEquals(1, result.getDiscNumber());
        verify(trackRepository).save(result);
        verify(serverEventService).createTrackFoundEvent(result.getId());
    }

    @Test
    void getOrCreateBookReturnsExistingBook() {
        PersonEntity author = PersonEntity.builder().name("Tolkien").build();
        BookEntity existing = BookEntity.builder().name("The Hobbit").releaseYear(1937).build();
        when(bookRepository.findByPersonEntityAndNameAndReleaseYear(author, "The Hobbit", 1937))
                .thenReturn(Optional.of(existing));

        assertEquals(existing, subject.getOrCreateBook(library, author, "The Hobbit", 1937));
        verify(bookRepository, never()).save(any());
        verify(serverEventService, never()).createBookFoundEvent(any());
    }

    /**
     * The epub and the audiobook folder of one book converge on a single row: a year-less name
     * matches the oldest book with that name, exactly like albums.
     */
    @Test
    void getOrCreateBookMatchesOnNameAloneWhenTheDirectoryHasNoYear() {
        PersonEntity author = PersonEntity.builder().name("Tolkien").build();
        BookEntity existing = BookEntity.builder().name("The Hobbit").releaseYear(1937).build();
        when(bookRepository.findFirstByPersonEntityAndNameOrderByDateCreatedAsc(author, "The Hobbit"))
                .thenReturn(Optional.of(existing));

        assertEquals(existing, subject.getOrCreateBook(library, author, "The Hobbit", 0));
        verify(bookRepository, never()).findByPersonEntityAndNameAndReleaseYear(any(), any(), anyInt());
        verify(bookRepository, never()).save(any());
    }

    @Test
    void getOrCreateBookCreatesNewBook() {
        PersonEntity author = PersonEntity.builder().name("Tolkien").build();
        when(bookRepository.findByPersonEntityAndNameAndReleaseYear(author, "The Hobbit", 1937))
                .thenReturn(Optional.empty());

        BookEntity result = subject.getOrCreateBook(library, author, "The Hobbit", 1937);

        assertEquals("The Hobbit", result.getName());
        assertEquals(1937, result.getReleaseYear());
        verify(bookRepository).save(result);
        verify(serverEventService).createBookFoundEvent(result.getId());
    }

    @Test
    void getOrCreateChapterReturnsExistingChapter() {
        PersonEntity author = PersonEntity.builder().build();
        BookEntity book = BookEntity.builder().build();
        ChapterEntity existing = ChapterEntity.builder().number(1).build();
        when(chapterRepository.findByBookEntityAndNumber(book, 1)).thenReturn(Optional.of(existing));

        assertEquals(existing, subject.getOrCreateChapter(author, book, 1));
        verify(chapterRepository, never()).save(any());
        verify(serverEventService, never()).createChapterFoundEvent(any());
    }

    @Test
    void getOrCreateChapterCreatesNewChapter() {
        PersonEntity author = PersonEntity.builder().build();
        BookEntity book = BookEntity.builder().build();
        when(chapterRepository.findByBookEntityAndNumber(book, 1)).thenReturn(Optional.empty());

        ChapterEntity result = subject.getOrCreateChapter(author, book, 1);

        assertEquals(1, result.getNumber());
        verify(chapterRepository).save(result);
        verify(serverEventService).createChapterFoundEvent(result.getId());
    }
}
