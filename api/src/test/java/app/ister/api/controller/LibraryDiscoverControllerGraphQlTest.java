package app.ister.api.controller;

import app.ister.core.entity.AlbumEntity;
import app.ister.core.entity.LibraryEntity;
import app.ister.core.entity.MovieEntity;
import app.ister.core.entity.ShowEntity;
import app.ister.core.enums.LibraryType;
import app.ister.core.repository.AlbumRepository;
import app.ister.core.repository.BookRepository;
import app.ister.core.repository.LibraryRepository;
import app.ister.core.repository.MovieRepository;
import app.ister.core.repository.PodcastRepository;
import app.ister.core.repository.SeriesRepository;
import app.ister.core.repository.ShowRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.graphql.test.autoconfigure.GraphQlTest;
import org.springframework.graphql.test.tester.GraphQlTester;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * Schema-wiring test for the Discover view: libraryById plus the ranked top-list fields on
 * Library. Ranking correctness lives in the repository integration tests; this proves the schema
 * fields resolve, the repository order is preserved and access denial nulls the library out.
 */
@GraphQlTest(LibraryDiscoverController.class)
class LibraryDiscoverControllerGraphQlTest {

    @Autowired
    private GraphQlTester graphQlTester;

    @MockitoBean
    private app.ister.core.service.LibraryAccessService libraryAccessService;

    @MockitoBean
    private LibraryRepository libraryRepository;

    @MockitoBean
    private MovieRepository movieRepository;

    @MockitoBean
    private ShowRepository showRepository;

    @MockitoBean
    private AlbumRepository albumRepository;

    @MockitoBean
    private BookRepository bookRepository;

    @MockitoBean
    private SeriesRepository seriesRepository;

    @MockitoBean
    private PodcastRepository podcastRepository;

    private final LibraryEntity library = library();

    @org.junit.jupiter.api.BeforeEach
    void authenticateAsUser() {
        org.mockito.Mockito.lenient().when(libraryAccessService.canAccess(
                org.mockito.ArgumentMatchers.<LibraryEntity>any(),
                org.mockito.ArgumentMatchers.any())).thenReturn(true);
        org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(
                new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(
                        "test-user", null,
                        java.util.List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_user"))));
    }

    @org.junit.jupiter.api.AfterEach
    void clearAuthentication() {
        org.springframework.security.core.context.SecurityContextHolder.clearContext();
    }

    @Test
    void topListsResolveInRepositoryOrderWithDefaultLimit() {
        MovieEntity first = movie("First");
        MovieEntity second = movie("Second");
        when(libraryRepository.findById(library.getId())).thenReturn(java.util.Optional.of(library));
        // Repository ranks first before second; findAllById returns them in the other order.
        when(movieRepository.findMostPlayedMovieIdsForLibrary(eq(library.getId()), any(), eq(15)))
                .thenReturn(List.of(first.getId(), second.getId()));
        when(movieRepository.findRecentlyPlayedMovieIdsForLibrary(eq(library.getId()), any(), eq(5)))
                .thenReturn(List.of(second.getId()));
        when(movieRepository.findHighestRatedMovieIdsForLibrary(eq(library.getId()), any(), eq(15)))
                .thenReturn(List.of());
        when(movieRepository.findAllById(anyCollection())).thenAnswer(invocation -> {
            java.util.Collection<UUID> ids = invocation.getArgument(0);
            return java.util.stream.Stream.of(second, first).filter(m -> ids.contains(m.getId())).toList();
        });

        assertDoesNotThrow(() -> graphQlTester.document("""
                        { libraryById(id: "%s") {
                            id name
                            mostPlayedMovies { id }
                            recentlyPlayedMovies(limit: 5) { id }
                            highestRatedMovies { id }
                        } }
                        """.formatted(library.getId()))
                .execute()
                .path("libraryById.name").entity(String.class).isEqualTo("Movies")
                .path("libraryById.mostPlayedMovies[0].id").entity(String.class).isEqualTo(first.getId().toString())
                .path("libraryById.mostPlayedMovies[1].id").entity(String.class).isEqualTo(second.getId().toString())
                .path("libraryById.recentlyPlayedMovies[0].id").entity(String.class).isEqualTo(second.getId().toString())
                .path("libraryById.highestRatedMovies").entityList(Object.class).hasSize(0));
    }

    @Test
    void everyTypedTopListFieldResolves() {
        ShowEntity show = new ShowEntity();
        show.setId(UUID.randomUUID());
        AlbumEntity album = new AlbumEntity();
        album.setId(UUID.randomUUID());
        when(libraryRepository.findById(library.getId())).thenReturn(java.util.Optional.of(library));
        when(showRepository.findRecentlyPlayedShowIdsForLibrary(eq(library.getId()), any(), eq(15)))
                .thenReturn(List.of(show.getId()));
        when(showRepository.findAllById(anyCollection())).thenReturn(List.of(show));
        when(albumRepository.findMostPlayedAlbumIdsForLibrary(eq(library.getId()), any(), eq(15)))
                .thenReturn(List.of(album.getId()));
        when(albumRepository.findAllById(anyCollection())).thenReturn(List.of(album));
        when(bookRepository.findRecentlyReadBookIdsForLibrary(eq(library.getId()), any(), eq(15))).thenReturn(List.of());
        when(bookRepository.findHighestRatedBookIdsForLibrary(eq(library.getId()), any(), eq(15))).thenReturn(List.of());
        when(bookRepository.findAllById(anyCollection())).thenReturn(List.of());
        when(seriesRepository.findRecentlyReadSeriesIdsForLibrary(eq(library.getId()), any(), eq(15))).thenReturn(List.of());
        when(seriesRepository.findAllById(anyCollection())).thenReturn(List.of());
        when(podcastRepository.findRecentlyPlayedPodcastIdsForLibrary(eq(library.getId()), any(), eq(15))).thenReturn(List.of());
        when(podcastRepository.findMostPlayedPodcastIdsForLibrary(eq(library.getId()), any(), eq(15))).thenReturn(List.of());
        when(podcastRepository.findHighestRatedPodcastIdsForLibrary(eq(library.getId()), any(), eq(15))).thenReturn(List.of());
        when(podcastRepository.findAllById(anyCollection())).thenReturn(List.of());

        assertDoesNotThrow(() -> graphQlTester.document("""
                        { libraryById(id: "%s") {
                            recentlyPlayedShows { id }
                            mostPlayedAlbums { id }
                            recentlyReadBooks { id }
                            highestRatedBooks { id }
                            recentlyReadSeries { id }
                            recentlyPlayedPodcasts { id }
                            mostPlayedPodcasts { id }
                            highestRatedPodcasts { id }
                        } }
                        """.formatted(library.getId()))
                .execute()
                .path("libraryById.recentlyPlayedShows[0].id").entity(String.class).isEqualTo(show.getId().toString())
                .path("libraryById.mostPlayedAlbums[0].id").entity(String.class).isEqualTo(album.getId().toString())
                .path("libraryById.recentlyReadBooks").entityList(Object.class).hasSize(0)
                .path("libraryById.recentlyReadSeries").entityList(Object.class).hasSize(0)
                .path("libraryById.recentlyPlayedPodcasts").entityList(Object.class).hasSize(0));
    }

    @Test
    void libraryByIdIsNullWhenAccessIsDenied() {
        when(libraryRepository.findById(library.getId())).thenReturn(java.util.Optional.of(library));
        when(libraryAccessService.canAccess(
                org.mockito.ArgumentMatchers.<LibraryEntity>any(),
                org.mockito.ArgumentMatchers.any())).thenReturn(false);

        assertDoesNotThrow(() -> graphQlTester.document("""
                        { libraryById(id: "%s") { id } }
                        """.formatted(library.getId()))
                .execute()
                .path("libraryById").valueIsNull());
    }

    @Test
    void libraryByIdIsNullWhenUnknown() {
        when(libraryRepository.findById(any(UUID.class))).thenReturn(java.util.Optional.empty());

        assertDoesNotThrow(() -> graphQlTester.document("""
                        { libraryById(id: "%s") { id } }
                        """.formatted(UUID.randomUUID()))
                .execute()
                .path("libraryById").valueIsNull());
    }

    private static LibraryEntity library() {
        LibraryEntity library = LibraryEntity.builder().name("Movies").libraryType(LibraryType.MOVIE).build();
        library.setId(UUID.randomUUID());
        return library;
    }

    private static MovieEntity movie(String name) {
        MovieEntity movie = MovieEntity.builder().name(name).releaseYear(2020).build();
        movie.setId(UUID.randomUUID());
        return movie;
    }
}
