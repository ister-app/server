package app.ister.api.controller;

import app.ister.core.entity.BookEntity;
import app.ister.core.entity.ChapterEntity;
import app.ister.core.entity.EpisodeEntity;
import app.ister.core.entity.MovieEntity;
import app.ister.core.entity.PodcastEpisodeEntity;
import app.ister.core.entity.UserEntity;
import app.ister.core.entity.WatchStatusEntity;
import app.ister.core.enums.MediaType;
import app.ister.core.repository.BookRepository;
import app.ister.core.repository.ChapterRepository;
import app.ister.core.repository.EpisodeRepository;
import app.ister.core.repository.MovieRepository;
import app.ister.core.repository.PodcastEpisodeRepository;
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
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Slf4j
@Controller
@RequiredArgsConstructor
public class RecentlyWatchedController {
    private final EpisodeRepository episodeRepository;
    private final MovieRepository movieRepository;
    private final ChapterRepository chapterRepository;
    private final BookRepository bookRepository;
    private final PodcastEpisodeRepository podcastEpisodeRepository;
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
                .map(m -> RecentlyWatched.ofMovie(m, movieLastWatched(m)))
                .toList();

        List<RecentlyWatched> chapters = watchStatusRepository
                .findRecentChaptersAndBookIdsByUserId(userEntity.getId())
                .stream()
                .flatMap(strings -> getChapterItem(userEntity, UUID.fromString(strings[1]), UUID.fromString(strings[0])).stream())
                .toList();

        List<RecentlyWatched> books = watchStatusRepository
                .findRecentBookIdsByUserId(userEntity.getId())
                .stream()
                .map(id -> bookRepository.findById(UUID.fromString(id)))
                .flatMap(Optional::stream)
                .flatMap(book -> getBookReadingItem(userEntity, book).stream())
                .toList();

        List<RecentlyWatched> podcastEpisodes = watchStatusRepository
                .findRecentPodcastEpisodeIdsByUserId(userEntity.getId())
                .stream()
                .map(id -> podcastEpisodeRepository.findById(UUID.fromString(id)))
                .flatMap(Optional::stream)
                .flatMap(episode -> getPodcastEpisodeItem(userEntity, episode).stream())
                .toList();

        return Stream.of(episodes, movies, chapters, books, podcastEpisodes)
                .flatMap(List::stream)
                .sorted(Comparator.comparing(RecentlyWatched::lastWatched).reversed())
                .toList();
    }

    /** Continue-listening: a podcast episode the user started but has not finished. */
    private Optional<RecentlyWatched> getPodcastEpisodeItem(UserEntity userEntity, PodcastEpisodeEntity episode) {
        return watchStatusRepository
                .findByUserEntityExternalIdAndPodcastEpisodeEntityIn(userEntity.getExternalId(), List.of(episode),
                        Sort.by("dateUpdated").descending())
                .stream()
                .findFirst()
                .filter(status -> !status.isWatched() && status.getProgressInMilliseconds() > 0)
                .map(status -> RecentlyWatched.ofPodcastEpisode(episode, status.getDateUpdated()));
    }

    @SchemaMapping(typeName = "RecentlyWatched", field = "episode")
    public EpisodeEntity episode(RecentlyWatched item) {
        return item.episode();
    }

    @SchemaMapping(typeName = "RecentlyWatched", field = "movie")
    public MovieEntity movie(RecentlyWatched item) {
        return item.movie();
    }

    @SchemaMapping(typeName = "RecentlyWatched", field = "chapter")
    public ChapterEntity chapter(RecentlyWatched item) {
        return item.chapter();
    }

    @SchemaMapping(typeName = "RecentlyWatched", field = "book")
    public BookEntity book(RecentlyWatched item) {
        return item.book();
    }

    @SchemaMapping(typeName = "RecentlyWatched", field = "podcastEpisode")
    public PodcastEpisodeEntity podcastEpisode(RecentlyWatched item) {
        return item.podcastEpisode();
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
                            .map(e -> RecentlyWatched.ofEpisode(e, lastWatched));
                });
    }

    /** Continue-listening: the next unfinished chapter of an audiobook, mirroring the episode logic. */
    private Optional<RecentlyWatched> getChapterItem(UserEntity userEntity, UUID bookId, UUID chapterId) {
        List<ChapterEntity> bookChapters = chapterRepository.findByBookEntity_Id(bookId, Sort.by("number").ascending());
        Map<UUID, List<WatchStatusEntity>> statusesByChapterId = watchStatusRepository
                .findByUserEntityExternalIdAndChapterEntityIn(userEntity.getExternalId(), bookChapters, Sort.by("dateUpdated").descending())
                .stream()
                .collect(Collectors.groupingBy(w -> w.getChapterEntity().getId()));

        return bookChapters.stream()
                .filter(chapter -> chapter.getId().equals(chapterId))
                .findFirst()
                .flatMap(originalChapter -> {
                    List<WatchStatusEntity> statuses = statusesByChapterId.getOrDefault(originalChapter.getId(), List.of());
                    if (statuses.isEmpty()) {
                        return Optional.empty();
                    }
                    Instant lastWatched = statuses.getFirst().getDateUpdated();
                    return getFirstUnfinishedChapterFrom(originalChapter, bookChapters, statusesByChapterId)
                            .map(c -> RecentlyWatched.ofChapter(c, lastWatched));
                });
    }

    private Optional<ChapterEntity> getFirstUnfinishedChapterFrom(ChapterEntity chapter, List<ChapterEntity> bookChapters,
                                                                  Map<UUID, List<WatchStatusEntity>> statusesByChapterId) {
        List<WatchStatusEntity> statuses = statusesByChapterId.getOrDefault(chapter.getId(), List.of());
        if (statuses.isEmpty() || !statuses.getFirst().isWatched()) {
            return Optional.of(chapter);
        }
        int indexOfNextOne = bookChapters.indexOf(chapter) + 1;
        if (bookChapters.size() > indexOfNextOne) {
            return getFirstUnfinishedChapterFrom(bookChapters.get(indexOfNextOne), bookChapters, statusesByChapterId);
        }
        return Optional.empty();
    }

    /** Continue-reading: an epub the user started but has not finished. */
    private Optional<RecentlyWatched> getBookReadingItem(UserEntity userEntity, BookEntity book) {
        return watchStatusRepository.findByUserEntityAndBookEntity(userEntity, book)
                .filter(status -> !status.isWatched()
                        && status.getReadingProgress() != null && status.getReadingProgress() > 0)
                .map(status -> RecentlyWatched.ofBook(book, status.getDateUpdated()));
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
