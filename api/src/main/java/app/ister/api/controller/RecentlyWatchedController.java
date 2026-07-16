package app.ister.api.controller;

import app.ister.core.entity.BookEntity;
import app.ister.core.entity.ChapterEntity;
import app.ister.core.entity.ContinueWatchingEntity;
import app.ister.core.entity.EpisodeEntity;
import app.ister.core.entity.MovieEntity;
import app.ister.core.entity.PodcastEpisodeEntity;
import app.ister.core.entity.UserEntity;
import app.ister.core.service.ContinueWatchingService;
import app.ister.core.service.UserService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.graphql.data.method.annotation.QueryMapping;
import org.springframework.graphql.data.method.annotation.SchemaMapping;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;

import java.util.List;
import java.util.Optional;

/**
 * The "continue watching" list. It is not computed here: {@link ContinueWatchingService} keeps a
 * precomputed entry per show / movie / book / podcast up to date as the user plays, so this query
 * is one indexed read plus the batch loading of the media it points at. It used to walk the watch
 * history and load every episode of every show the user had ever touched, on every call.
 */
@Slf4j
@Controller
@RequiredArgsConstructor
public class RecentlyWatchedController {
    private final ContinueWatchingService continueWatchingService;
    private final UserService userService;

    @PreAuthorize("hasRole('user')")
    @QueryMapping
    public List<RecentlyWatched> recentlyWatched(Authentication authentication) {
        log.debug("Getting recently watched for user: {}", authentication.getName());
        UserEntity userEntity = userService.getOrCreateUser(authentication);

        return continueWatchingService.entriesFor(userEntity.getId()).stream()
                .flatMap(entry -> toRecentlyWatched(entry).stream())
                .toList();
    }

    /**
     * The entries come back ordered, and only the ones with something left to resume. An entry can
     * still point at nothing when the media it referenced was deleted since — the foreign keys null
     * the reference out — so those are skipped here; the nightly rebuild drops the rows.
     */
    private Optional<RecentlyWatched> toRecentlyWatched(ContinueWatchingEntity entry) {
        return Optional.ofNullable(switch (entry.getEntryType()) {
            case EPISODE -> entry.getEpisodeEntity() == null ? null
                    : RecentlyWatched.ofEpisode(entry.getEpisodeEntity(), entry.getLastWatched());
            case MOVIE -> entry.getMovieEntity() == null ? null
                    : RecentlyWatched.ofMovie(entry.getMovieEntity(), entry.getLastWatched());
            case CHAPTER -> entry.getChapterEntity() == null ? null
                    : RecentlyWatched.ofChapter(entry.getChapterEntity(), entry.getLastWatched());
            case BOOK -> (entry.getBookEntity() == null && entry.getChapterEntity() == null) ? null
                    : RecentlyWatched.ofBook(entry.getBookEntity(), entry.getChapterEntity(), entry.getLastWatched());
            case PODCAST_EPISODE -> entry.getPodcastEpisodeEntity() == null ? null
                    : RecentlyWatched.ofPodcastEpisode(entry.getPodcastEpisodeEntity(), entry.getLastWatched());
            case COMIC -> entry.getBookEntity() == null ? null
                    : RecentlyWatched.ofComic(entry.getBookEntity(), entry.getLastWatched());
            case TRACK -> null;
        });
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
}
