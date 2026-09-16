package app.ister.core.service;

import app.ister.core.entity.MovieEntity;
import app.ister.core.entity.RatingEntity;
import app.ister.core.entity.WatchStatusEntity;
import app.ister.core.enums.SearchEntityType;
import app.ister.core.repository.ContinueWatchingRepository;
import app.ister.core.repository.CreditRepository;
import app.ister.core.repository.ImageRepository;
import app.ister.core.repository.MediaFileRepository;
import app.ister.core.repository.MetadataRepository;
import app.ister.core.repository.MovieRepository;
import app.ister.core.repository.PlayQueueItemRepository;
import app.ister.core.repository.PlaylistItemRepository;
import app.ister.core.repository.RatingRepository;
import app.ister.core.repository.WatchStatusRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Folds one movie row into another. A movie's identity comes from its path ({@code Title (year)}),
 * so two copies of one film in differently titled directories ("De Smurfen" next to "The Smurfs")
 * become two rows until TMDB matches both to the same id. The {@code MOVIE_FOUND} handler then
 * merges the newcomer into the row that already carries that TMDB id: the files, play queue and
 * playlist entries move over, watch status and ratings move unless the user already has one on
 * the target, and the newcomer's own metadata, images and credits are dropped (the target has its
 * own). Afterwards {@link ScannerHelperService#getOrCreateMovie} resolves the merged-away name
 * through the moved files, so a rescan does not recreate it.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class MovieMergeService {
    private final MovieRepository movieRepository;
    private final MediaFileRepository mediaFileRepository;
    private final PlayQueueItemRepository playQueueItemRepository;
    private final PlaylistItemRepository playlistItemRepository;
    private final WatchStatusRepository watchStatusRepository;
    private final RatingRepository ratingRepository;
    private final ContinueWatchingRepository continueWatchingRepository;
    private final CreditRepository creditRepository;
    private final ImageRepository imageRepository;
    private final MetadataRepository metadataRepository;
    private final ContinueWatchingService continueWatchingService;
    private final ServerEventService serverEventService;

    @Transactional
    public void mergeInto(MovieEntity source, MovieEntity target) {
        log.info("Merging duplicate movie '{}' ({}) into '{}' ({}), TMDB {}",
                source.getName(), source.getReleaseYear(), target.getName(), target.getReleaseYear(), target.getTmdbId());
        int files = mediaFileRepository.moveToMovie(source, target);
        playQueueItemRepository.moveMovie(source.getId(), target.getId());
        playlistItemRepository.moveMovie(source.getId(), target.getId());
        // The source's continue-watching rows go; the moved watch statuses recompute the target's.
        continueWatchingRepository.deleteByMovieId(source.getId());
        for (WatchStatusEntity status : watchStatusRepository.findByMovieEntity(source)) {
            status.setMovieEntity(target);
            watchStatusRepository.save(status);
            continueWatchingService.onWatchStatusChanged(status);
        }
        for (RatingEntity rating : ratingRepository.findByMovieEntity(source)) {
            if (ratingRepository.findByUserEntityAndMovieEntity(rating.getUserEntity(), target).isPresent()) {
                ratingRepository.delete(rating);
            } else {
                rating.setMovieEntity(target);
                ratingRepository.save(rating);
            }
        }
        creditRepository.deleteRowsByMovieId(source.getId());
        imageRepository.deleteByMovieId(source.getId());
        metadataRepository.deleteByMovieId(source.getId());
        // The moved watch statuses and ratings are still pending in the persistence context; the
        // bulk delete below does not auto-flush them, and the row must not go while they point at it.
        watchStatusRepository.flush();
        movieRepository.deleteRowById(source.getId());
        serverEventService.createSearchDeleteEvent(SearchEntityType.MOVIE, source.getId());
        serverEventService.createSearchIndexEvent(SearchEntityType.MOVIE, target.getId());
        log.info("Merged {} file(s) of '{}' into '{}'", files, source.getName(), target.getName());
    }
}
