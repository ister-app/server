package app.ister.api.controller;

import app.ister.core.entity.AlbumEntity;
import app.ister.core.entity.BaseEntity;
import app.ister.core.entity.BookEntity;
import app.ister.core.entity.LibraryEntity;
import app.ister.core.entity.MovieEntity;
import app.ister.core.entity.PodcastEntity;
import app.ister.core.entity.SeriesEntity;
import app.ister.core.entity.ShowEntity;
import app.ister.core.repository.AlbumRepository;
import app.ister.core.repository.BookRepository;
import app.ister.core.repository.LibraryRepository;
import app.ister.core.repository.MovieRepository;
import app.ister.core.repository.PodcastRepository;
import app.ister.core.repository.SeriesRepository;
import app.ister.core.repository.ShowRepository;
import app.ister.core.enums.RankKind;
import app.ister.core.service.LibraryAccessService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.graphql.data.method.annotation.Argument;
import org.springframework.graphql.data.method.annotation.QueryMapping;
import org.springframework.graphql.data.method.annotation.SchemaMapping;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.LongSupplier;
import java.util.stream.Collectors;

/**
 * The library Discover view: {@code libraryById} plus the per-user ranked top-lists on
 * {@code Library}. Every list is scoped to the one library the caller already resolved through
 * {@code libraryById} (or {@code libraries}, both access-checked), so unlike the Person top-lists
 * no separate in-libraries query variants are needed here.
 */
@Controller
@RequiredArgsConstructor
public class LibraryDiscoverController {
    private final LibraryRepository libraryRepository;
    private final LibraryAccessService libraryAccessService;
    private final MovieRepository movieRepository;
    private final ShowRepository showRepository;
    private final AlbumRepository albumRepository;
    private final BookRepository bookRepository;
    private final SeriesRepository seriesRepository;
    private final PodcastRepository podcastRepository;

    @PreAuthorize("hasRole('user')")
    @QueryMapping
    public Optional<LibraryEntity> libraryById(@Argument UUID id, Authentication authentication) {
        return libraryRepository.findById(id)
                .filter(library -> libraryAccessService.canAccess(library, authentication));
    }

    @SchemaMapping(typeName = "Library", field = "recentlyPlayedMovies")
    public List<MovieEntity> recentlyPlayedMovies(LibraryEntity library, @Argument Optional<Integer> limit, Authentication authentication) {
        return inOrder(movieRepository, movieRepository.findRecentlyPlayedMovieIdsForLibrary(
                library.getId(), authentication.getName(), clampLimit(limit), 0));
    }

    @SchemaMapping(typeName = "Library", field = "mostPlayedMovies")
    public List<MovieEntity> mostPlayedMovies(LibraryEntity library, @Argument Optional<Integer> limit, Authentication authentication) {
        return inOrder(movieRepository, movieRepository.findMostPlayedMovieIdsForLibrary(
                library.getId(), authentication.getName(), clampLimit(limit), 0));
    }

    @SchemaMapping(typeName = "Library", field = "highestRatedMovies")
    public List<MovieEntity> highestRatedMovies(LibraryEntity library, @Argument Optional<Integer> limit, Authentication authentication) {
        return inOrder(movieRepository, movieRepository.findHighestRatedMovieIdsForLibrary(
                library.getId(), authentication.getName(), clampLimit(limit), 0));
    }

    @SchemaMapping(typeName = "Library", field = "recentlyPlayedShows")
    public List<ShowEntity> recentlyPlayedShows(LibraryEntity library, @Argument Optional<Integer> limit, Authentication authentication) {
        return inOrder(showRepository, showRepository.findRecentlyPlayedShowIdsForLibrary(
                library.getId(), authentication.getName(), clampLimit(limit), 0));
    }

    @SchemaMapping(typeName = "Library", field = "mostPlayedShows")
    public List<ShowEntity> mostPlayedShows(LibraryEntity library, @Argument Optional<Integer> limit, Authentication authentication) {
        return inOrder(showRepository, showRepository.findMostPlayedShowIdsForLibrary(
                library.getId(), authentication.getName(), clampLimit(limit), 0));
    }

    @SchemaMapping(typeName = "Library", field = "highestRatedShows")
    public List<ShowEntity> highestRatedShows(LibraryEntity library, @Argument Optional<Integer> limit, Authentication authentication) {
        return inOrder(showRepository, showRepository.findHighestRatedShowIdsForLibrary(
                library.getId(), authentication.getName(), clampLimit(limit), 0));
    }

    @SchemaMapping(typeName = "Library", field = "recentlyPlayedAlbums")
    public List<AlbumEntity> recentlyPlayedAlbums(LibraryEntity library, @Argument Optional<Integer> limit, Authentication authentication) {
        return inOrder(albumRepository, albumRepository.findRecentlyPlayedAlbumIdsForLibrary(
                library.getId(), authentication.getName(), clampLimit(limit), 0));
    }

    @SchemaMapping(typeName = "Library", field = "mostPlayedAlbums")
    public List<AlbumEntity> mostPlayedAlbums(LibraryEntity library, @Argument Optional<Integer> limit, Authentication authentication) {
        return inOrder(albumRepository, albumRepository.findMostPlayedAlbumIdsForLibrary(
                library.getId(), authentication.getName(), clampLimit(limit), 0));
    }

    @SchemaMapping(typeName = "Library", field = "highestRatedAlbums")
    public List<AlbumEntity> highestRatedAlbums(LibraryEntity library, @Argument Optional<Integer> limit, Authentication authentication) {
        return inOrder(albumRepository, albumRepository.findHighestRatedAlbumIdsForLibrary(
                library.getId(), authentication.getName(), clampLimit(limit), 0));
    }

    @SchemaMapping(typeName = "Library", field = "recentlyReadBooks")
    public List<BookEntity> recentlyReadBooks(LibraryEntity library, @Argument Optional<Integer> limit, Authentication authentication) {
        return inOrder(bookRepository, bookRepository.findRecentlyReadBookIdsForLibrary(
                library.getId(), authentication.getName(), clampLimit(limit), 0));
    }

    @SchemaMapping(typeName = "Library", field = "highestRatedBooks")
    public List<BookEntity> highestRatedBooks(LibraryEntity library, @Argument Optional<Integer> limit, Authentication authentication) {
        return inOrder(bookRepository, bookRepository.findHighestRatedBookIdsForLibrary(
                library.getId(), authentication.getName(), clampLimit(limit), 0));
    }

    @SchemaMapping(typeName = "Library", field = "recentlyReadSeries")
    public List<SeriesEntity> recentlyReadSeries(LibraryEntity library, @Argument Optional<Integer> limit, Authentication authentication) {
        return inOrder(seriesRepository, seriesRepository.findRecentlyReadSeriesIdsForLibrary(
                library.getId(), authentication.getName(), clampLimit(limit), 0));
    }

    @SchemaMapping(typeName = "Library", field = "recentlyPlayedPodcasts")
    public List<PodcastEntity> recentlyPlayedPodcasts(LibraryEntity library, @Argument Optional<Integer> limit, Authentication authentication) {
        return inOrder(podcastRepository, podcastRepository.findRecentlyPlayedPodcastIdsForLibrary(
                library.getId(), authentication.getName(), clampLimit(limit), 0));
    }

    @SchemaMapping(typeName = "Library", field = "mostPlayedPodcasts")
    public List<PodcastEntity> mostPlayedPodcasts(LibraryEntity library, @Argument Optional<Integer> limit, Authentication authentication) {
        return inOrder(podcastRepository, podcastRepository.findMostPlayedPodcastIdsForLibrary(
                library.getId(), authentication.getName(), clampLimit(limit), 0));
    }

    @SchemaMapping(typeName = "Library", field = "highestRatedPodcasts")
    public List<PodcastEntity> highestRatedPodcasts(LibraryEntity library, @Argument Optional<Integer> limit, Authentication authentication) {
        return inOrder(podcastRepository, podcastRepository.findHighestRatedPodcastIdsForLibrary(
                library.getId(), authentication.getName(), clampLimit(limit), 0));
    }

    @SchemaMapping(typeName = "Library", field = "rankedMovies")
    public Page<MovieEntity> rankedMovies(LibraryEntity library, @Argument RankKind kind,
                                          @Argument Optional<Integer> page, @Argument Optional<Integer> size, Authentication authentication) {
        UUID libraryId = library.getId();
        String user = authentication.getName();
        return switch (kind) {
            case RECENTLY_PLAYED -> rankedPage(page, size, movieRepository,
                    (limit, offset) -> movieRepository.findRecentlyPlayedMovieIdsForLibrary(libraryId, user, limit, offset),
                    () -> movieRepository.countPlayedMoviesForLibrary(libraryId, user));
            case MOST_PLAYED -> rankedPage(page, size, movieRepository,
                    (limit, offset) -> movieRepository.findMostPlayedMovieIdsForLibrary(libraryId, user, limit, offset),
                    () -> movieRepository.countPlayedMoviesForLibrary(libraryId, user));
            case HIGHEST_RATED -> rankedPage(page, size, movieRepository,
                    (limit, offset) -> movieRepository.findHighestRatedMovieIdsForLibrary(libraryId, user, limit, offset),
                    () -> movieRepository.countRatedMoviesForLibrary(libraryId, user));
        };
    }

    @SchemaMapping(typeName = "Library", field = "rankedShows")
    public Page<ShowEntity> rankedShows(LibraryEntity library, @Argument RankKind kind,
                                        @Argument Optional<Integer> page, @Argument Optional<Integer> size, Authentication authentication) {
        UUID libraryId = library.getId();
        String user = authentication.getName();
        return switch (kind) {
            case RECENTLY_PLAYED -> rankedPage(page, size, showRepository,
                    (limit, offset) -> showRepository.findRecentlyPlayedShowIdsForLibrary(libraryId, user, limit, offset),
                    () -> showRepository.countPlayedShowsForLibrary(libraryId, user));
            case MOST_PLAYED -> rankedPage(page, size, showRepository,
                    (limit, offset) -> showRepository.findMostPlayedShowIdsForLibrary(libraryId, user, limit, offset),
                    () -> showRepository.countPlayedShowsForLibrary(libraryId, user));
            case HIGHEST_RATED -> rankedPage(page, size, showRepository,
                    (limit, offset) -> showRepository.findHighestRatedShowIdsForLibrary(libraryId, user, limit, offset),
                    () -> showRepository.countRatedShowsForLibrary(libraryId, user));
        };
    }

    @SchemaMapping(typeName = "Library", field = "rankedAlbums")
    public Page<AlbumEntity> rankedAlbums(LibraryEntity library, @Argument RankKind kind,
                                          @Argument Optional<Integer> page, @Argument Optional<Integer> size, Authentication authentication) {
        UUID libraryId = library.getId();
        String user = authentication.getName();
        return switch (kind) {
            case RECENTLY_PLAYED -> rankedPage(page, size, albumRepository,
                    (limit, offset) -> albumRepository.findRecentlyPlayedAlbumIdsForLibrary(libraryId, user, limit, offset),
                    () -> albumRepository.countPlayedAlbumsForLibrary(libraryId, user));
            case MOST_PLAYED -> rankedPage(page, size, albumRepository,
                    (limit, offset) -> albumRepository.findMostPlayedAlbumIdsForLibrary(libraryId, user, limit, offset),
                    () -> albumRepository.countPlayedAlbumsForLibrary(libraryId, user));
            case HIGHEST_RATED -> rankedPage(page, size, albumRepository,
                    (limit, offset) -> albumRepository.findHighestRatedAlbumIdsForLibrary(libraryId, user, limit, offset),
                    () -> albumRepository.countRatedAlbumsForLibrary(libraryId, user));
        };
    }

    @SchemaMapping(typeName = "Library", field = "rankedBooks")
    public Page<BookEntity> rankedBooks(LibraryEntity library, @Argument RankKind kind,
                                        @Argument Optional<Integer> page, @Argument Optional<Integer> size, Authentication authentication) {
        UUID libraryId = library.getId();
        String user = authentication.getName();
        return switch (kind) {
            case RECENTLY_PLAYED -> rankedPage(page, size, bookRepository,
                    (limit, offset) -> bookRepository.findRecentlyReadBookIdsForLibrary(libraryId, user, limit, offset),
                    () -> bookRepository.countReadBooksForLibrary(libraryId, user));
            case HIGHEST_RATED -> rankedPage(page, size, bookRepository,
                    (limit, offset) -> bookRepository.findHighestRatedBookIdsForLibrary(libraryId, user, limit, offset),
                    () -> bookRepository.countRatedBooksForLibrary(libraryId, user));
            case MOST_PLAYED -> Page.empty(rankedPageable(page, size));
        };
    }

    @SchemaMapping(typeName = "Library", field = "rankedSeries")
    public Page<SeriesEntity> rankedSeries(LibraryEntity library, @Argument RankKind kind,
                                           @Argument Optional<Integer> page, @Argument Optional<Integer> size, Authentication authentication) {
        UUID libraryId = library.getId();
        String user = authentication.getName();
        return switch (kind) {
            case RECENTLY_PLAYED -> rankedPage(page, size, seriesRepository,
                    (limit, offset) -> seriesRepository.findRecentlyReadSeriesIdsForLibrary(libraryId, user, limit, offset),
                    () -> seriesRepository.countReadSeriesForLibrary(libraryId, user));
            case MOST_PLAYED, HIGHEST_RATED -> Page.empty(rankedPageable(page, size));
        };
    }

    @SchemaMapping(typeName = "Library", field = "rankedPodcasts")
    public Page<PodcastEntity> rankedPodcasts(LibraryEntity library, @Argument RankKind kind,
                                              @Argument Optional<Integer> page, @Argument Optional<Integer> size, Authentication authentication) {
        UUID libraryId = library.getId();
        String user = authentication.getName();
        return switch (kind) {
            case RECENTLY_PLAYED -> rankedPage(page, size, podcastRepository,
                    (limit, offset) -> podcastRepository.findRecentlyPlayedPodcastIdsForLibrary(libraryId, user, limit, offset),
                    () -> podcastRepository.countPlayedPodcastsForLibrary(libraryId, user));
            case MOST_PLAYED -> rankedPage(page, size, podcastRepository,
                    (limit, offset) -> podcastRepository.findMostPlayedPodcastIdsForLibrary(libraryId, user, limit, offset),
                    () -> podcastRepository.countPlayedPodcastsForLibrary(libraryId, user));
            case HIGHEST_RATED -> rankedPage(page, size, podcastRepository,
                    (limit, offset) -> podcastRepository.findHighestRatedPodcastIdsForLibrary(libraryId, user, limit, offset),
                    () -> podcastRepository.countRatedPodcastsForLibrary(libraryId, user));
        };
    }

    private static int clampLimit(Optional<Integer> limit) {
        return Math.clamp(limit.orElse(15), 1, 50);
    }

    /** The ranked queries carry their ORDER BY themselves, so the pageable is offset/size only. */
    private static Pageable rankedPageable(Optional<Integer> page, Optional<Integer> size) {
        return PageRequest.of(Math.max(page.orElse(0), 0), Math.clamp(size.orElse(15), 1, 50));
    }

    @FunctionalInterface
    private interface RankedIdsQuery {
        List<UUID> fetch(int limit, int offset);
    }

    private static <T extends BaseEntity> Page<T> rankedPage(
            Optional<Integer> page, Optional<Integer> size, JpaRepository<T, UUID> repository,
            RankedIdsQuery ids, LongSupplier total) {
        Pageable pageable = rankedPageable(page, size);
        List<T> content = inOrder(repository, ids.fetch(pageable.getPageSize(), (int) pageable.getOffset()));
        return new PageImpl<>(content, pageable, total.getAsLong());
    }

    /** Loads the entities of the ranked id list, preserving the list's order. */
    private static <T extends BaseEntity> List<T> inOrder(JpaRepository<T, UUID> repository, List<UUID> ids) {
        Map<UUID, T> byId = repository.findAllById(ids).stream()
                .collect(Collectors.toMap(BaseEntity::getId, Function.identity()));
        return ids.stream().map(byId::get).filter(Objects::nonNull).toList();
    }
}
