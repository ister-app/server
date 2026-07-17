package app.ister.core.service;

import app.ister.core.entity.*;
import app.ister.core.enums.SearchEntityType;
import app.ister.core.repository.*;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;

@Service
@Slf4j
@RequiredArgsConstructor
public class ScannerHelperService {
    private final MovieRepository movieRepository;
    private final ShowRepository showRepository;
    private final SeasonRepository seasonRepository;
    private final EpisodeRepository episodeRepository;
    private final PersonRepository personRepository;
    private final AlbumRepository albumRepository;
    private final TrackRepository trackRepository;
    private final BookRepository bookRepository;
    private final SeriesRepository seriesRepository;
    private final MetadataRepository metadataRepository;
    private final ChapterRepository chapterRepository;
    private final ServerEventService serverEventService;
    private final ContinueWatchingService continueWatchingService;
    private final BookSeriesService bookSeriesService;

    /**
     * Check if the database contains a show wit the given parameters.
     * - If it exists return it.
     * - Else create and return it.
     */
    public MovieEntity getOrCreateMovie(LibraryEntity libraryEntity, String movieName, int releaseYear) {
        return movieRepository.findByLibraryEntityAndNameAndReleaseYear(libraryEntity, movieName, releaseYear)
                .orElseGet(() -> {
                    MovieEntity movieEntity = MovieEntity.builder()
                            .libraryEntity(libraryEntity)
                            .name(movieName)
                            .releaseYear(releaseYear).build();
                    movieRepository.save(movieEntity);
                    serverEventService.createMovieFoundEvent(movieEntity.getId());
                    return movieEntity;
                });
    }


    /**
     * Check if the database contains a show wit the given parameters.
     * - If it exists return it.
     * - Else create and return it.
     */
    public ShowEntity getOrCreateShow(LibraryEntity libraryEntity, String showName, int releaseYear) {
        return showRepository.findByLibraryEntityAndNameAndReleaseYear(libraryEntity, showName, releaseYear)
                .orElseGet(() -> {
                    ShowEntity showEntity = ShowEntity.builder()
                            .libraryEntity(libraryEntity)
                            .name(showName)
                            .releaseYear(releaseYear).build();
                    showRepository.save(showEntity);
                    serverEventService.createShowFoundEvent(showEntity.getId());
                    return showEntity;
                });
    }

    /**
     * Check if the database contains a season wit the given parameters.
     * - If it exists return it.
     * - Else create and return it.
     */
    public SeasonEntity getOrCreateSeason(LibraryEntity libraryEntity, String showName, int releaseYear, int seasonNumber) {
        ShowEntity showEntity = getOrCreateShow(libraryEntity, showName, releaseYear);
        return seasonRepository.findByShowEntityAndNumber(showEntity, seasonNumber)
                .orElseGet(() -> {
                    SeasonEntity seasonEntity = SeasonEntity.builder()
                            .showEntity(showEntity)
                            .number(seasonNumber).build();
                    seasonRepository.save(seasonEntity);
                    return seasonEntity;
                });
    }

    /**
     * Check if the database contains a person with the given parameters.
     * - If it exists return it.
     * - Else, if a library-less person with the same name exists (created from TMDB cast
     *   credits), claim that person for this library. Deduplication is on exact name, so a
     *   band named exactly like an actor would be merged — accepted trade-off.
     * - Else create and return it.
     */
    public PersonEntity getOrCreatePerson(LibraryEntity libraryEntity, String artistName) {
        return getOrCreatePerson(libraryEntity, artistName, 0);
    }

    /**
     * Like {@link #getOrCreatePerson(LibraryEntity, String)} but seeds the birth year from a value
     * parsed out of the folder structure (a trailing "(YYYY)" on the artist directory). A value
     * {@code <= 0} means "unknown"; MusicBrainz then fills it in during PERSON_FOUND enrichment. The
     * birth year is what links a music artist to the same person as a TMDB actor.
     */
    public PersonEntity getOrCreatePerson(LibraryEntity libraryEntity, String artistName, int birthYear) {
        Integer year = birthYear > 0 ? birthYear : null;
        return personRepository.findByLibraryEntityAndName(libraryEntity, artistName)
                .map(existing -> fillBirthYearIfMissing(existing, year))
                .orElseGet(() -> {
                    PersonEntity personEntity = personRepository.findFirstByNameAndLibraryEntityIsNull(artistName)
                            .orElseGet(() -> PersonEntity.builder().name(artistName).build());
                    personEntity.setLibraryEntity(libraryEntity);
                    if (year != null && personEntity.getBirthYear() == null) {
                        personEntity.setBirthYear(year);
                    }
                    personRepository.save(personEntity);
                    serverEventService.createPersonFoundEvent(personEntity.getId());
                    return personEntity;
                });
    }

    private PersonEntity fillBirthYearIfMissing(PersonEntity person, Integer year) {
        if (year != null && person.getBirthYear() == null) {
            person.setBirthYear(year);
            personRepository.save(person);
        }
        return person;
    }

    /**
     * Check if the database contains an album with the given parameters.
     * - If it exists return it.
     * - Else create and return it.
     *
     * <p>Both the name and the release year come from the album directory, and the audio, image and
     * NFO scanners all derive them the same way — that is what lets a cover.jpg find the album its
     * sibling tracks created. A directory only carries a year when it ends in "(YYYY)"; when it does
     * not, {@code releaseYear} is 0 and the year cannot identify the album, so the name alone has to.
     * Two same-named albums by one artist in year-less directories therefore merge; only a year in
     * the directory name can tell them apart.
     */
    public AlbumEntity getOrCreateAlbum(LibraryEntity libraryEntity, PersonEntity personEntity, String albumName, int releaseYear) {
        Optional<AlbumEntity> existing = releaseYear > 0
                ? albumRepository.findByPersonEntityAndNameAndReleaseYear(personEntity, albumName, releaseYear)
                : albumRepository.findFirstByPersonEntityAndNameOrderByDateCreatedAsc(personEntity, albumName);
        return existing
                .orElseGet(() -> {
                    AlbumEntity albumEntity = AlbumEntity.builder()
                            .libraryEntity(libraryEntity)
                            .personEntity(personEntity)
                            .name(albumName)
                            .releaseYear(releaseYear).build();
                    albumRepository.save(albumEntity);
                    serverEventService.createAlbumFoundEvent(albumEntity.getId());
                    return albumEntity;
                });
    }

    /**
     * Check if the database contains a track with the given parameters.
     * - If it exists return it.
     * - Else create and return it.
     */
    public TrackEntity getOrCreateTrack(PersonEntity personEntity, AlbumEntity albumEntity, int trackNumber, int discNumber) {
        return trackRepository.findByAlbumEntityAndNumberAndDiscNumber(albumEntity, trackNumber, discNumber)
                .orElseGet(() -> {
                    TrackEntity trackEntity = TrackEntity.builder()
                            .personEntity(personEntity)
                            .albumEntity(albumEntity)
                            .number(trackNumber)
                            .discNumber(discNumber).build();
                    trackRepository.save(trackEntity);
                    serverEventService.createTrackFoundEvent(trackEntity.getId());
                    return trackEntity;
                });
    }

    /**
     * Check if the database contains a book with the given parameters.
     * - If it exists (matched on the path-derived name and "(YYYY)" year) return it.
     * - If the path carries a year but only a year-less row exists, adopt that row: the user added
     *   "(YYYY)" to an existing book's folder, or the row predates the path-year split.
     * - Else create and return it.
     *
     * <p>The name comes from the epub filename or the book directory (with any "(karaoke)" suffix
     * already stripped by the path parser), so the epub, the audiobook folder and a karaoke epub of
     * the same book all converge on one row. Identity uses {@code pathYear}, never the display
     * {@code releaseYear}, so external enrichment can correct the displayed year without breaking
     * rescan matching. A {@code pathYear} of 0 means unknown and then the name alone identifies the
     * book.
     */
    public BookEntity getOrCreateBook(LibraryEntity libraryEntity, PersonEntity personEntity, String bookName, int pathYear) {
        Optional<BookEntity> existing = pathYear > 0
                ? bookRepository.findByPersonEntityAndNameAndPathYear(personEntity, bookName, pathYear)
                : bookRepository.findFirstByPersonEntityAndNameOrderByDateCreatedAsc(personEntity, bookName);
        if (existing.isEmpty() && pathYear > 0) {
            // Adoption: at most one year-less row exists (unique constraint), and a genuinely
            // different same-name book has its own pathYear > 0 row and is not touched.
            existing = bookRepository.findByPersonEntityAndNameAndPathYear(personEntity, bookName, 0)
                    .map(book -> {
                        book.setPathYear(pathYear);
                        bookRepository.save(book);
                        refreshBookReleaseYear(book);
                        return book;
                    });
        }
        return existing
                .orElseGet(() -> {
                    BookEntity bookEntity = BookEntity.builder()
                            .libraryEntity(libraryEntity)
                            .personEntity(personEntity)
                            .name(bookName)
                            .pathYear(pathYear)
                            .releaseYear(pathYear).build();
                    bookRepository.save(bookEntity);
                    serverEventService.createBookFoundEvent(bookEntity.getId());
                    // The second book sharing a "Series - " prefix retroactively pulls the first
                    // one into the series too.
                    bookSeriesService.applyPrefixHeuristic(personEntity);
                    return bookEntity;
                });
    }

    /**
     * Recompute the display/sort {@code releaseYear} column. Precedence: a "(YYYY)" path year is
     * deliberate user intent and always wins; otherwise the Wikidata year (the original edition —
     * Open Library often matches a translated work and reports the translation's year); otherwise
     * the Open Library year; otherwise the earliest year of the local (nfo/epub)
     * metadata rows. Sorting uses this column while {@code BookController.releaseYear} falls back
     * to the earliest metadata year only when the column is 0 — keeping the column current keeps
     * the two coherent. Called whenever a metadata row is written for the book.
     */
    public void refreshBookReleaseYear(BookEntity bookEntity) {
        int year = bookEntity.getPathYear() > 0 ? bookEntity.getPathYear() : bestMetadataYear(bookEntity);
        if (year > 0 && year != bookEntity.getReleaseYear()) {
            bookEntity.setReleaseYear(year);
            bookRepository.save(bookEntity);
        }
    }

    private int bestMetadataYear(BookEntity bookEntity) {
        var metadata = metadataRepository.findByBookEntityId(bookEntity.getId()).stream()
                .filter(m -> m.getReleased() != null && m.getReleased().getYear() > 0)
                .toList();
        return yearFromSource(metadata, "wikidata://")
                .orElseGet(() -> yearFromSource(metadata, "openlibrary://")
                        .orElseGet(() -> metadata.stream()
                                .mapToInt(m -> m.getReleased().getYear())
                                .min()
                                .orElse(0)));
    }

    private OptionalInt yearFromSource(List<MetadataEntity> metadata, String sourceUriPrefix) {
        return metadata.stream()
                .filter(m -> m.getSourceUri() != null && m.getSourceUri().startsWith(sourceUriPrefix))
                .mapToInt(m -> m.getReleased().getYear())
                .min();
    }

    /**
     * A comic series from a COMIC library's series directory. No author: identity is the library
     * plus the directory name and its "(YYYY)" year. On create the worker enriches it via
     * COMIC_SERIES_FOUND (Wikipedia description + thumbnail).
     */
    public SeriesEntity getOrCreateComicSeries(LibraryEntity libraryEntity, String seriesName, int startYear) {
        Optional<SeriesEntity> existing =
                seriesRepository.findByLibraryEntityAndNameAndStartYear(libraryEntity, seriesName, startYear);
        if (existing.isPresent()) {
            return existing.get();
        }
        // A native insert-ignore rather than save(): parallel consumers scanning one series
        // directory race on series_entity_comic_identity, and a constraint violation would poison
        // the surrounding transaction. The loser re-reads the winner's row; only the winner emits
        // COMIC_SERIES_FOUND.
        boolean inserted = seriesRepository.insertComicSeriesIfAbsent(
                UUID.randomUUID(), libraryEntity.getId(), seriesName, startYear) == 1;
        SeriesEntity seriesEntity = seriesRepository
                .findByLibraryEntityAndNameAndStartYear(libraryEntity, seriesName, startYear)
                .orElseThrow(() -> new IllegalStateException(
                        "Comic series vanished right after upsert: " + seriesName + " (" + startYear + ")"));
        if (inserted) {
            serverEventService.createComicSeriesFoundEvent(seriesEntity.getId());
        }
        return seriesEntity;
    }

    /**
     * A comic volume: a BookEntity without author, scoped by its series. Deliberately separate
     * from {@link #getOrCreateBook}: that fires BOOK_FOUND (Open Library — wrong for comics) and
     * runs the book series-prefix heuristic. Multiple formats of one volume (pdf/cbz/epub with
     * the same basename) converge on one row via the name.
     */
    public BookEntity getOrCreateComicVolume(LibraryEntity libraryEntity, SeriesEntity seriesEntity,
                                             String volumeName, int pathYear, Double seriesIndex, String title) {
        Optional<BookEntity> existing =
                bookRepository.findBySeriesEntityAndNameAndPathYear(seriesEntity, volumeName, pathYear);
        if (existing.isPresent()) {
            return existing.get();
        }
        // Insert-ignore for the same reason as getOrCreateComicSeries: the pdf/cbz/epub formats of
        // one volume are scanned by parallel consumers racing on book_entity_comic_identity.
        boolean inserted = bookRepository.insertComicVolumeIfAbsent(
                UUID.randomUUID(), libraryEntity.getId(), seriesEntity.getId(),
                volumeName, title, seriesIndex, pathYear, pathYear) == 1;
        BookEntity bookEntity = bookRepository
                .findBySeriesEntityAndNameAndPathYear(seriesEntity, volumeName, pathYear)
                .orElseThrow(() -> new IllegalStateException(
                        "Comic volume vanished right after upsert: " + volumeName + " (" + pathYear + ")"));
        if (inserted) {
            serverEventService.createSearchIndexEvent(SearchEntityType.BOOK, bookEntity.getId());
            // A new volume puts a finished series back in continue-watching.
            continueWatchingService.recomputeForComicSeries(seriesEntity.getId());
        }
        return bookEntity;
    }

    /**
     * Check if the database contains a chapter with the given parameters.
     * - If it exists return it.
     * - Else create and return it.
     */
    public ChapterEntity getOrCreateChapter(PersonEntity personEntity, BookEntity bookEntity, int chapterNumber) {
        return chapterRepository.findByBookEntityAndNumber(bookEntity, chapterNumber)
                .orElseGet(() -> {
                    ChapterEntity chapterEntity = ChapterEntity.builder()
                            .personEntity(personEntity)
                            .bookEntity(bookEntity)
                            .number(chapterNumber).build();
                    chapterRepository.save(chapterEntity);
                    serverEventService.createChapterFoundEvent(chapterEntity.getId());
                    continueWatchingService.recomputeForBook(bookEntity.getId());
                    return chapterEntity;
                });
    }

    /**
     * Check if the database contains an episode wit the given parameters.
     * - If it exists return it.
     * - Else create and return it.
     */
    public EpisodeEntity getOrCreateEpisode(LibraryEntity libraryEntity, String showName, int releaseYear, int seasonNumber, int episodeNumber) {
        ShowEntity showEntity = getOrCreateShow(libraryEntity, showName, releaseYear);
        SeasonEntity seasonEntity = getOrCreateSeason(libraryEntity, showName, releaseYear, seasonNumber);
        return episodeRepository.findByShowEntityAndSeasonEntityAndNumber(showEntity, seasonEntity, episodeNumber)
                .orElseGet(() -> {
                    EpisodeEntity episodeEntity = EpisodeEntity.builder()
                            .showEntity(showEntity)
                            .seasonEntity(seasonEntity)
                            .number(episodeNumber).build();
                    episodeRepository.save(episodeEntity);
                    serverEventService.createEpisodeFoundEvent(episodeEntity.getId());
                    // A new episode of a show someone had watched to the end puts that show back in
                    // their continue-watching list, instead of only after the nightly rebuild.
                    continueWatchingService.recomputeForShow(showEntity.getId());
                    return episodeEntity;
                });
    }
}
