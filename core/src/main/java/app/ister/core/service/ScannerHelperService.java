package app.ister.core.service;

import app.ister.core.entity.*;
import app.ister.core.repository.*;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

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
    private final ServerEventService serverEventService;

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
     */
    public AlbumEntity getOrCreateAlbum(LibraryEntity libraryEntity, PersonEntity personEntity, String albumName, int releaseYear) {
        return albumRepository.findByPersonEntityAndNameAndReleaseYear(personEntity, albumName, releaseYear)
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
                    return episodeEntity;
                });
    }
}
