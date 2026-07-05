package app.ister.api.controller;

import app.ister.api.error.SearchUnavailableException;
import app.ister.core.entity.MovieEntity;
import app.ister.core.entity.ShowEntity;
import app.ister.core.enums.SearchEntityType;
import app.ister.core.repository.AlbumRepository;
import app.ister.core.repository.EpisodeRepository;
import app.ister.core.repository.MovieRepository;
import app.ister.core.repository.PersonRepository;
import app.ister.core.repository.ShowRepository;
import app.ister.core.repository.TrackRepository;
import app.ister.core.service.ServerEventService;
import app.ister.search.SearchQueryService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SearchControllerTest {

    @Mock
    private SearchQueryService searchQueryService;
    @Mock
    private ServerEventService serverEventService;
    @Mock
    private MovieRepository movieRepository;
    @Mock
    private ShowRepository showRepository;
    @Mock
    private EpisodeRepository episodeRepository;
    @Mock
    private PersonRepository personRepository;
    @Mock
    private AlbumRepository albumRepository;
    @Mock
    private TrackRepository trackRepository;

    @InjectMocks
    private SearchController subject;

    @Test
    void searchThrowsWhenTypesenseIsNotConfigured() {
        when(searchQueryService.isEnabled()).thenReturn(false);

        assertThrows(SearchUnavailableException.class,
                () -> subject.search("matrix", Optional.empty(), Optional.empty()));
    }

    @Test
    void searchHydratesHitsKeepingTypesenseRanking() {
        MovieEntity movie = MovieEntity.builder().name("The Matrix").releaseYear(1999).build();
        movie.setId(UUID.randomUUID());
        ShowEntity show = ShowEntity.builder().name("Matrix Show").releaseYear(2003).build();
        show.setId(UUID.randomUUID());

        when(searchQueryService.isEnabled()).thenReturn(true);
        // Show ranks above movie in Typesense; hydration must keep that order.
        when(searchQueryService.search("matrix", 20, null)).thenReturn(List.of(
                new SearchQueryService.SearchHit(SearchEntityType.SHOW, show.getId()),
                new SearchQueryService.SearchHit(SearchEntityType.MOVIE, movie.getId())));
        when(showRepository.findAllById(List.of(show.getId()))).thenReturn(List.of(show));
        when(movieRepository.findAllById(List.of(movie.getId()))).thenReturn(List.of(movie));

        List<Object> result = subject.search("matrix", Optional.empty(), Optional.empty());

        assertEquals(List.of(show, movie), result);
    }

    @Test
    void searchDropsHitsThatNoLongerExistInTheDatabase() {
        UUID goneId = UUID.randomUUID();
        when(searchQueryService.isEnabled()).thenReturn(true);
        when(searchQueryService.search("matrix", 20, null)).thenReturn(List.of(
                new SearchQueryService.SearchHit(SearchEntityType.MOVIE, goneId)));
        when(movieRepository.findAllById(List.of(goneId))).thenReturn(List.of());

        List<Object> result = subject.search("matrix", Optional.empty(), Optional.empty());

        assertTrue(result.isEmpty());
    }

    @Test
    void reindexSearchSendsEvent() {
        when(searchQueryService.isEnabled()).thenReturn(true);

        assertTrue(subject.reindexSearch());

        verify(serverEventService).createSearchReindexEvent();
    }

    @Test
    void reindexSearchThrowsWhenTypesenseIsNotConfigured() {
        when(searchQueryService.isEnabled()).thenReturn(false);

        assertThrows(SearchUnavailableException.class, () -> subject.reindexSearch());
    }
}
