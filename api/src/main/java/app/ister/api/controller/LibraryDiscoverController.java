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
import app.ister.core.service.LibraryAccessService;
import lombok.RequiredArgsConstructor;
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
                library.getId(), authentication.getName(), clampLimit(limit)));
    }

    @SchemaMapping(typeName = "Library", field = "mostPlayedMovies")
    public List<MovieEntity> mostPlayedMovies(LibraryEntity library, @Argument Optional<Integer> limit, Authentication authentication) {
        return inOrder(movieRepository, movieRepository.findMostPlayedMovieIdsForLibrary(
                library.getId(), authentication.getName(), clampLimit(limit)));
    }

    @SchemaMapping(typeName = "Library", field = "highestRatedMovies")
    public List<MovieEntity> highestRatedMovies(LibraryEntity library, @Argument Optional<Integer> limit, Authentication authentication) {
        return inOrder(movieRepository, movieRepository.findHighestRatedMovieIdsForLibrary(
                library.getId(), authentication.getName(), clampLimit(limit)));
    }

    @SchemaMapping(typeName = "Library", field = "recentlyPlayedShows")
    public List<ShowEntity> recentlyPlayedShows(LibraryEntity library, @Argument Optional<Integer> limit, Authentication authentication) {
        return inOrder(showRepository, showRepository.findRecentlyPlayedShowIdsForLibrary(
                library.getId(), authentication.getName(), clampLimit(limit)));
    }

    @SchemaMapping(typeName = "Library", field = "mostPlayedShows")
    public List<ShowEntity> mostPlayedShows(LibraryEntity library, @Argument Optional<Integer> limit, Authentication authentication) {
        return inOrder(showRepository, showRepository.findMostPlayedShowIdsForLibrary(
                library.getId(), authentication.getName(), clampLimit(limit)));
    }

    @SchemaMapping(typeName = "Library", field = "highestRatedShows")
    public List<ShowEntity> highestRatedShows(LibraryEntity library, @Argument Optional<Integer> limit, Authentication authentication) {
        return inOrder(showRepository, showRepository.findHighestRatedShowIdsForLibrary(
                library.getId(), authentication.getName(), clampLimit(limit)));
    }

    @SchemaMapping(typeName = "Library", field = "recentlyPlayedAlbums")
    public List<AlbumEntity> recentlyPlayedAlbums(LibraryEntity library, @Argument Optional<Integer> limit, Authentication authentication) {
        return inOrder(albumRepository, albumRepository.findRecentlyPlayedAlbumIdsForLibrary(
                library.getId(), authentication.getName(), clampLimit(limit)));
    }

    @SchemaMapping(typeName = "Library", field = "mostPlayedAlbums")
    public List<AlbumEntity> mostPlayedAlbums(LibraryEntity library, @Argument Optional<Integer> limit, Authentication authentication) {
        return inOrder(albumRepository, albumRepository.findMostPlayedAlbumIdsForLibrary(
                library.getId(), authentication.getName(), clampLimit(limit)));
    }

    @SchemaMapping(typeName = "Library", field = "highestRatedAlbums")
    public List<AlbumEntity> highestRatedAlbums(LibraryEntity library, @Argument Optional<Integer> limit, Authentication authentication) {
        return inOrder(albumRepository, albumRepository.findHighestRatedAlbumIdsForLibrary(
                library.getId(), authentication.getName(), clampLimit(limit)));
    }

    @SchemaMapping(typeName = "Library", field = "recentlyReadBooks")
    public List<BookEntity> recentlyReadBooks(LibraryEntity library, @Argument Optional<Integer> limit, Authentication authentication) {
        return inOrder(bookRepository, bookRepository.findRecentlyReadBookIdsForLibrary(
                library.getId(), authentication.getName(), clampLimit(limit)));
    }

    @SchemaMapping(typeName = "Library", field = "highestRatedBooks")
    public List<BookEntity> highestRatedBooks(LibraryEntity library, @Argument Optional<Integer> limit, Authentication authentication) {
        return inOrder(bookRepository, bookRepository.findHighestRatedBookIdsForLibrary(
                library.getId(), authentication.getName(), clampLimit(limit)));
    }

    @SchemaMapping(typeName = "Library", field = "recentlyReadSeries")
    public List<SeriesEntity> recentlyReadSeries(LibraryEntity library, @Argument Optional<Integer> limit, Authentication authentication) {
        return inOrder(seriesRepository, seriesRepository.findRecentlyReadSeriesIdsForLibrary(
                library.getId(), authentication.getName(), clampLimit(limit)));
    }

    @SchemaMapping(typeName = "Library", field = "recentlyPlayedPodcasts")
    public List<PodcastEntity> recentlyPlayedPodcasts(LibraryEntity library, @Argument Optional<Integer> limit, Authentication authentication) {
        return inOrder(podcastRepository, podcastRepository.findRecentlyPlayedPodcastIdsForLibrary(
                library.getId(), authentication.getName(), clampLimit(limit)));
    }

    @SchemaMapping(typeName = "Library", field = "mostPlayedPodcasts")
    public List<PodcastEntity> mostPlayedPodcasts(LibraryEntity library, @Argument Optional<Integer> limit, Authentication authentication) {
        return inOrder(podcastRepository, podcastRepository.findMostPlayedPodcastIdsForLibrary(
                library.getId(), authentication.getName(), clampLimit(limit)));
    }

    @SchemaMapping(typeName = "Library", field = "highestRatedPodcasts")
    public List<PodcastEntity> highestRatedPodcasts(LibraryEntity library, @Argument Optional<Integer> limit, Authentication authentication) {
        return inOrder(podcastRepository, podcastRepository.findHighestRatedPodcastIdsForLibrary(
                library.getId(), authentication.getName(), clampLimit(limit)));
    }

    private static int clampLimit(Optional<Integer> limit) {
        return Math.clamp(limit.orElse(15), 1, 50);
    }

    /** Loads the entities of the ranked id list, preserving the list's order. */
    private static <T extends BaseEntity> List<T> inOrder(JpaRepository<T, UUID> repository, List<UUID> ids) {
        Map<UUID, T> byId = repository.findAllById(ids).stream()
                .collect(Collectors.toMap(BaseEntity::getId, Function.identity()));
        return ids.stream().map(byId::get).filter(Objects::nonNull).toList();
    }
}
