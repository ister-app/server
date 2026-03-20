package app.ister.api.controller;

import app.ister.core.entity.EpisodeEntity;
import app.ister.core.entity.MovieEntity;
import app.ister.core.entity.UserEntity;
import app.ister.core.enums.MediaType;
import app.ister.core.repository.EpisodeRepository;
import app.ister.core.repository.MovieRepository;
import app.ister.core.repository.WatchStatusRepository;
import app.ister.core.service.UserService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Sort;
import org.springframework.graphql.data.method.annotation.QueryMapping;
import org.springframework.graphql.data.method.annotation.SchemaMapping;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;

import java.util.Comparator;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

@Slf4j
@Controller
@RequiredArgsConstructor
public class RecentlyWatchedController {
    private final EpisodeRepository episodeRepository;
    private final MovieRepository movieRepository;
    private final WatchStatusRepository watchStatusRepository;
    private final UserService userService;

    @PreAuthorize("hasRole('user')")
    @QueryMapping
    public List<RecentlyWatched> recentlyWatched(Authentication authentication) {
        log.debug("Getting recently watched for user: {}", authentication.getName());
        UserEntity userEntity = userService.getOrCreateUser(authentication);

        List<RecentlyWatched> episodes = watchStatusRepository
                .findRecentEpisodesAndShowIdsByUserId(userEntity.getId())
                .stream()
                .flatMap(strings -> getEpisodeItem(UUID.fromString(strings[1]), UUID.fromString(strings[0])).stream())
                .toList();

        List<RecentlyWatched> movies = watchStatusRepository
                .findRecentMovieIdsByUserId(userEntity.getId())
                .stream()
                .map(id -> movieRepository.findById(UUID.fromString(id)))
                .flatMap(Optional::stream)
                .filter(this::isMovieUnwatchedOrRecent)
                .map(m -> new RecentlyWatched(MediaType.MOVIE, null, m, movieLastWatched(m)))
                .toList();

        return Stream.concat(episodes.stream(), movies.stream())
                .sorted(Comparator.comparing(RecentlyWatched::lastWatched).reversed())
                .toList();
    }

    @SchemaMapping(typeName = "RecentlyWatched", field = "episode")
    public EpisodeEntity episode(RecentlyWatched item) {
        return item.episode();
    }

    @SchemaMapping(typeName = "RecentlyWatched", field = "movie")
    public MovieEntity movie(RecentlyWatched item) {
        return item.movie();
    }

    private Optional<RecentlyWatched> getEpisodeItem(UUID showId, UUID episodeId) {
        List<EpisodeEntity> seasonEpisodes = episodeRepository.findByShowEntityId(showId,
                Sort.by("seasonEntity.number").ascending().and(Sort.by("number").ascending()));

        return seasonEpisodes.stream()
                .filter(episode -> episode.getId().equals(episodeId))
                .findFirst()
                .flatMap(originalEpisode -> {
                    Instant lastWatched = originalEpisode.getWatchStatusEntities().getFirst().getDateUpdated();
                    return getFirstUnwatchedEpisodeFrom(originalEpisode, seasonEpisodes)
                            .map(e -> new RecentlyWatched(MediaType.EPISODE, e, null, lastWatched));
                });
    }

    private Optional<EpisodeEntity> getFirstUnwatchedEpisodeFrom(EpisodeEntity episodeEntity, List<EpisodeEntity> seasonEpisodes) {
        if (!episodeEntity.getWatchStatusEntities().getFirst().isWatched()) {
            return Optional.of(episodeEntity);
        }
        return findNextUnwatchedEpisode(episodeEntity, seasonEpisodes);
    }

    private Optional<EpisodeEntity> findNextUnwatchedEpisode(EpisodeEntity episodeEntity, List<EpisodeEntity> seasonEpisodes) {
        int indexOfNextOne = seasonEpisodes.indexOf(episodeEntity) + 1;

        if (seasonEpisodes.size() > indexOfNextOne) {
            EpisodeEntity nextEpisodeEntity = seasonEpisodes.get(indexOfNextOne);
            if (isUnwatchedOrOld(nextEpisodeEntity)) {
                return Optional.of(nextEpisodeEntity);
            }
            return findNextUnwatchedEpisode(nextEpisodeEntity, seasonEpisodes);
        }
        return Optional.empty();
    }

    private boolean isUnwatchedOrOld(EpisodeEntity episodeEntity) {
        return episodeEntity.getWatchStatusEntities().isEmpty()
                || !episodeEntity.getWatchStatusEntities().getFirst().isWatched()
                || episodeEntity.getWatchStatusEntities().getFirst().getDateUpdated().isBefore(Instant.now().minus(Duration.ofDays(300)));
    }

    private boolean isMovieUnwatchedOrRecent(MovieEntity movie) {
        var statuses = movie.getWatchStatusEntities();
        return statuses.isEmpty()
                || !statuses.getFirst().isWatched()
                || statuses.getFirst().getDateUpdated().isBefore(Instant.now().minus(Duration.ofDays(300)));
    }

    private Instant movieLastWatched(MovieEntity movie) {
        var statuses = movie.getWatchStatusEntities();
        return statuses.isEmpty() ? Instant.EPOCH : statuses.getFirst().getDateUpdated();
    }
}
