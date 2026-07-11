package app.ister.core.service;

import app.ister.core.entity.RatingEntity;
import app.ister.core.entity.UserEntity;
import app.ister.core.enums.RatingMediaType;
import app.ister.core.repository.AlbumRepository;
import app.ister.core.repository.EpisodeRepository;
import app.ister.core.repository.MovieRepository;
import app.ister.core.repository.RatingRepository;
import app.ister.core.repository.ShowRepository;
import app.ister.core.repository.TrackRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;

/**
 * Stores per-user ratings (1-10) for movies, shows, episodes, albums and tracks.
 * A null rating clears (deletes) the caller's existing rating for the item.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class RatingService {
    private final UserService userService;
    private final RatingRepository ratingRepository;
    private final MovieRepository movieRepository;
    private final ShowRepository showRepository;
    private final EpisodeRepository episodeRepository;
    private final AlbumRepository albumRepository;
    private final TrackRepository trackRepository;

    /**
     * Sets or clears the calling user's rating for a single media item.
     *
     * @param rating the rating 1-10, or {@code null} to remove the existing rating
     * @throws IllegalArgumentException if the rating is out of the 1-10 range
     * @throws NoSuchElementException   if the referenced media item does not exist
     */
    @Transactional
    public void setRating(Authentication authentication, RatingMediaType mediaType, UUID mediaId, Integer rating) {
        if (rating != null && (rating < 1 || rating > 10)) {
            throw new IllegalArgumentException("Rating must be between 1 and 10, or null to clear it.");
        }
        UserEntity userEntity = userService.getOrCreateUser(authentication);
        Optional<RatingEntity> existing = findExisting(userEntity, mediaType, mediaId);

        if (rating == null) {
            existing.ifPresent(ratingRepository::delete);
            return;
        }
        if (existing.isPresent()) {
            RatingEntity ratingEntity = existing.get();
            ratingEntity.setValue(rating);
            ratingRepository.save(ratingEntity);
        } else {
            ratingRepository.save(build(userEntity, mediaType, mediaId, rating));
        }
    }

    private Optional<RatingEntity> findExisting(UserEntity user, RatingMediaType mediaType, UUID mediaId) {
        return switch (mediaType) {
            case MOVIE -> ratingRepository.findByUserEntityAndMovieEntity(user, movie(mediaId));
            case SHOW -> ratingRepository.findByUserEntityAndShowEntity(user, show(mediaId));
            case EPISODE -> ratingRepository.findByUserEntityAndEpisodeEntity(user, episode(mediaId));
            case ALBUM -> ratingRepository.findByUserEntityAndAlbumEntity(user, album(mediaId));
            case TRACK -> ratingRepository.findByUserEntityAndTrackEntity(user, track(mediaId));
        };
    }

    private RatingEntity build(UserEntity user, RatingMediaType mediaType, UUID mediaId, int rating) {
        RatingEntity.RatingEntityBuilder<?, ?> builder = RatingEntity.builder().userEntity(user).value(rating);
        switch (mediaType) {
            case MOVIE -> builder.movieEntity(movie(mediaId));
            case SHOW -> builder.showEntity(show(mediaId));
            case EPISODE -> builder.episodeEntity(episode(mediaId));
            case ALBUM -> builder.albumEntity(album(mediaId));
            case TRACK -> builder.trackEntity(track(mediaId));
        }
        return builder.build();
    }

    private app.ister.core.entity.MovieEntity movie(UUID id) {
        return movieRepository.findById(id).orElseThrow(() -> notFound("Movie", id));
    }

    private app.ister.core.entity.ShowEntity show(UUID id) {
        return showRepository.findById(id).orElseThrow(() -> notFound("Show", id));
    }

    private app.ister.core.entity.EpisodeEntity episode(UUID id) {
        return episodeRepository.findById(id).orElseThrow(() -> notFound("Episode", id));
    }

    private app.ister.core.entity.AlbumEntity album(UUID id) {
        return albumRepository.findById(id).orElseThrow(() -> notFound("Album", id));
    }

    private app.ister.core.entity.TrackEntity track(UUID id) {
        return trackRepository.findById(id).orElseThrow(() -> notFound("Track", id));
    }

    private static NoSuchElementException notFound(String type, UUID id) {
        return new NoSuchElementException(type + " not found: " + id);
    }
}
