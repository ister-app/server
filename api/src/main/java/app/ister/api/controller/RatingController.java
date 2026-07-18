package app.ister.api.controller;

import app.ister.core.entity.AlbumEntity;
import app.ister.core.entity.BookEntity;
import app.ister.core.entity.EpisodeEntity;
import app.ister.core.entity.MovieEntity;
import app.ister.core.entity.LibraryEntity;
import app.ister.core.entity.RatingEntity;
import app.ister.core.entity.ShowEntity;
import app.ister.core.entity.TrackEntity;
import app.ister.core.enums.RatingMediaType;
import app.ister.core.repository.AlbumRepository;
import app.ister.core.repository.BookRepository;
import app.ister.core.repository.EpisodeRepository;
import app.ister.core.repository.MovieRepository;
import app.ister.core.repository.PodcastRepository;
import app.ister.core.repository.RatingRepository;
import app.ister.core.repository.ShowRepository;
import app.ister.core.repository.TrackRepository;
import app.ister.core.service.LibraryAccessService;
import app.ister.core.service.RatingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.graphql.data.method.annotation.Argument;
import org.springframework.graphql.data.method.annotation.BatchMapping;
import org.springframework.graphql.data.method.annotation.MutationMapping;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

@Slf4j
@Controller
@RequiredArgsConstructor
public class RatingController {
    private final RatingService ratingService;
    private final RatingRepository ratingRepository;
    private final LibraryAccessService libraryAccessService;
    private final MovieRepository movieRepository;
    private final ShowRepository showRepository;
    private final EpisodeRepository episodeRepository;
    private final AlbumRepository albumRepository;
    private final TrackRepository trackRepository;
    private final BookRepository bookRepository;
    private final PodcastRepository podcastRepository;

    @PreAuthorize("hasRole('user')")
    @MutationMapping
    public Boolean setRating(@Argument RatingMediaType mediaType, @Argument UUID mediaId,
                             @Argument Integer rating, Authentication authentication) {
        Optional<LibraryEntity> library = switch (mediaType) {
            case MOVIE -> movieRepository.findById(mediaId).map(MovieEntity::getLibraryEntity);
            case SHOW -> showRepository.findById(mediaId).map(ShowEntity::getLibraryEntity);
            case EPISODE -> episodeRepository.findById(mediaId)
                    .map(episode -> episode.getShowEntity().getLibraryEntity());
            case ALBUM -> albumRepository.findById(mediaId).map(AlbumEntity::getLibraryEntity);
            case TRACK -> trackRepository.findById(mediaId)
                    .map(track -> track.getAlbumEntity().getLibraryEntity());
            case BOOK -> bookRepository.findById(mediaId).map(BookEntity::getLibraryEntity);
            case PODCAST -> podcastRepository.findById(mediaId)
                    .map(app.ister.core.entity.PodcastEntity::getLibraryEntity);
        };
        if (library.isEmpty() || !libraryAccessService.canAccess(library.get(), authentication)) {
            return false;
        }
        ratingService.setRating(authentication, mediaType, mediaId, rating);
        return true;
    }

    @BatchMapping(typeName = "Movie", field = "rating")
    public Map<MovieEntity, Integer> movieRating(List<MovieEntity> movies, Authentication authentication) {
        Map<UUID, Integer> byId = ratingRepository
                .findByUserEntityExternalIdAndMovieEntityIn(authentication.getName(), movies).stream()
                .collect(Collectors.toMap(r -> r.getMovieEntity().getId(), RatingEntity::getValue));
        return ratingsFor(movies, MovieEntity::getId, byId);
    }

    @BatchMapping(typeName = "Show", field = "rating")
    public Map<ShowEntity, Integer> showRating(List<ShowEntity> shows, Authentication authentication) {
        Map<UUID, Integer> byId = ratingRepository
                .findByUserEntityExternalIdAndShowEntityIn(authentication.getName(), shows).stream()
                .collect(Collectors.toMap(r -> r.getShowEntity().getId(), RatingEntity::getValue));
        return ratingsFor(shows, ShowEntity::getId, byId);
    }

    @BatchMapping(typeName = "Episode", field = "rating")
    public Map<EpisodeEntity, Integer> episodeRating(List<EpisodeEntity> episodes, Authentication authentication) {
        Map<UUID, Integer> byId = ratingRepository
                .findByUserEntityExternalIdAndEpisodeEntityIn(authentication.getName(), episodes).stream()
                .collect(Collectors.toMap(r -> r.getEpisodeEntity().getId(), RatingEntity::getValue));
        return ratingsFor(episodes, EpisodeEntity::getId, byId);
    }

    @BatchMapping(typeName = "Album", field = "rating")
    public Map<AlbumEntity, Integer> albumRating(List<AlbumEntity> albums, Authentication authentication) {
        Map<UUID, Integer> byId = ratingRepository
                .findByUserEntityExternalIdAndAlbumEntityIn(authentication.getName(), albums).stream()
                .collect(Collectors.toMap(r -> r.getAlbumEntity().getId(), RatingEntity::getValue));
        return ratingsFor(albums, AlbumEntity::getId, byId);
    }

    @BatchMapping(typeName = "Track", field = "rating")
    public Map<TrackEntity, Integer> trackRating(List<TrackEntity> tracks, Authentication authentication) {
        Map<UUID, Integer> byId = ratingRepository
                .findByUserEntityExternalIdAndTrackEntityIn(authentication.getName(), tracks).stream()
                .collect(Collectors.toMap(r -> r.getTrackEntity().getId(), RatingEntity::getValue));
        return ratingsFor(tracks, TrackEntity::getId, byId);
    }

    @BatchMapping(typeName = "Book", field = "rating")
    public Map<BookEntity, Integer> bookRating(List<BookEntity> books, Authentication authentication) {
        Map<UUID, Integer> byId = ratingRepository
                .findByUserEntityExternalIdAndBookEntityIn(authentication.getName(), books).stream()
                .collect(Collectors.toMap(r -> r.getBookEntity().getId(), RatingEntity::getValue));
        return ratingsFor(books, BookEntity::getId, byId);
    }

    @BatchMapping(typeName = "Podcast", field = "rating")
    public Map<app.ister.core.entity.PodcastEntity, Integer> podcastRating(
            List<app.ister.core.entity.PodcastEntity> podcasts, Authentication authentication) {
        Map<UUID, Integer> byId = ratingRepository
                .findByUserEntityExternalIdAndPodcastEntityIn(authentication.getName(), podcasts).stream()
                .collect(Collectors.toMap(r -> r.getPodcastEntity().getId(), RatingEntity::getValue));
        return ratingsFor(podcasts, app.ister.core.entity.PodcastEntity::getId, byId);
    }

    /**
     * Builds the {@code entity -> rating} map the framework expects. Entities without a rating are
     * mapped to null so the GraphQL {@code rating} field resolves to null rather than being absent.
     */
    private static <T> Map<T, Integer> ratingsFor(List<T> items, Function<T, UUID> idOf, Map<UUID, Integer> byId) {
        Map<T, Integer> result = new java.util.HashMap<>();
        for (T item : items) {
            result.put(item, byId.get(idOf.apply(item)));
        }
        return result;
    }
}
