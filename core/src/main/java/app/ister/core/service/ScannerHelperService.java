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
    private final ArtistRepository artistRepository;
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
     * Check if the database contains an artist with the given parameters.
     * - If it exists return it.
     * - Else create and return it.
     */
    public ArtistEntity getOrCreateArtist(LibraryEntity libraryEntity, String artistName) {
        return artistRepository.findByLibraryEntityAndName(libraryEntity, artistName)
                .orElseGet(() -> {
                    ArtistEntity artistEntity = ArtistEntity.builder()
                            .libraryEntity(libraryEntity)
                            .name(artistName).build();
                    artistRepository.save(artistEntity);
                    serverEventService.createArtistFoundEvent(artistEntity.getId());
                    return artistEntity;
                });
    }

    /**
     * Check if the database contains an album with the given parameters.
     * - If it exists return it.
     * - Else create and return it.
     */
    public AlbumEntity getOrCreateAlbum(LibraryEntity libraryEntity, ArtistEntity artistEntity, String albumName, int releaseYear) {
        return albumRepository.findByArtistEntityAndNameAndReleaseYear(artistEntity, albumName, releaseYear)
                .orElseGet(() -> {
                    AlbumEntity albumEntity = AlbumEntity.builder()
                            .libraryEntity(libraryEntity)
                            .artistEntity(artistEntity)
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
    public TrackEntity getOrCreateTrack(ArtistEntity artistEntity, AlbumEntity albumEntity, int trackNumber, int discNumber) {
        return trackRepository.findByAlbumEntityAndNumberAndDiscNumber(albumEntity, trackNumber, discNumber)
                .orElseGet(() -> {
                    TrackEntity trackEntity = TrackEntity.builder()
                            .artistEntity(artistEntity)
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
