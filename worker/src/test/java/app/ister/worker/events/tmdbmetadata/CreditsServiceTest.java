package app.ister.worker.events.tmdbmetadata;

import app.ister.core.entity.CreditEntity;
import app.ister.core.entity.EpisodeEntity;
import app.ister.core.entity.MovieEntity;
import app.ister.core.entity.PersonEntity;
import app.ister.core.entity.ShowEntity;
import app.ister.core.enums.CreditType;
import app.ister.core.repository.CreditRepository;
import app.ister.tmdbapi.model.MovieCredits200Response;
import app.ister.tmdbapi.model.MovieCredits200ResponseCastInner;
import app.ister.tmdbapi.model.MovieCredits200ResponseCrewInner;
import app.ister.tmdbapi.model.TvEpisodeCredits200Response;
import app.ister.tmdbapi.model.TvEpisodeCredits200ResponseCastInner;
import app.ister.tmdbapi.model.TvEpisodeCredits200ResponseGuestStarsInner;
import app.ister.tmdbapi.model.TvSeriesAggregateCredits200Response;
import app.ister.tmdbapi.model.TvSeriesAggregateCredits200ResponseCastInner;
import app.ister.tmdbapi.model.TvSeriesAggregateCredits200ResponseCastInnerRolesInner;
import app.ister.worker.clients.TmdbClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CreditsServiceTest {

    @InjectMocks
    private CreditsService subject;

    @Mock
    private TmdbClient tmdbClient;
    @Mock
    private CreditRepository creditRepository;
    @Mock
    private PersonLookupService personLookupService;

    private final PersonEntity person = PersonEntity.builder().name("Actor").build();

    @Test
    void fetchForMovieSavesCastAndIgnoresCrew() {
        MovieEntity movie = MovieEntity.builder().name("Movie").build();
        MovieCredits200Response credits = new MovieCredits200Response()
                .cast(List.of(new MovieCredits200ResponseCastInner()
                        .id(5).name("Actor").character("Neo").order(0).creditId("c1").profilePath("/p.jpg")))
                .crew(List.of(new MovieCredits200ResponseCrewInner().id(6).name("Director")));
        when(tmdbClient._movieCredits(603, "en-US")).thenReturn(ResponseEntity.ok(credits));
        when(personLookupService.getOrCreateFromTmdb(5L, "Actor", "/p.jpg")).thenReturn(person);

        subject.fetchForMovie(movie, 603);

        verify(creditRepository).deleteByMovieEntityId(movie.getId());
        ArgumentCaptor<CreditEntity> captor = ArgumentCaptor.forClass(CreditEntity.class);
        verify(creditRepository).save(captor.capture());
        CreditEntity saved = captor.getValue();
        assertEquals(person, saved.getPersonEntity());
        assertEquals(movie.getId(), saved.getMovieEntityId());
        assertEquals("Neo", saved.getCharacterName());
        assertEquals(CreditType.CAST, saved.getCreditType());
        assertEquals(0, saved.getCastOrder());
        assertEquals("c1", saved.getTmdbCreditId());
        // Only the single cast member is saved; the crew entry is ignored.
        verify(personLookupService, times(1)).getOrCreateFromTmdb(org.mockito.ArgumentMatchers.anyLong(), any(), any());
    }

    @Test
    void fetchForShowSavesOneCreditPerRole() {
        ShowEntity show = ShowEntity.builder().name("Show").build();
        TvSeriesAggregateCredits200Response credits = new TvSeriesAggregateCredits200Response()
                .cast(List.of(new TvSeriesAggregateCredits200ResponseCastInner()
                        .id(5).name("Actor").order(2).profilePath(null)
                        .roles(List.of(
                                new TvSeriesAggregateCredits200ResponseCastInnerRolesInner().character("Role A").creditId("r1"),
                                new TvSeriesAggregateCredits200ResponseCastInnerRolesInner().character("Role B").creditId("r2")))));
        when(tmdbClient._tvSeriesAggregateCredits(1399, "en-US")).thenReturn(ResponseEntity.ok(credits));
        when(personLookupService.getOrCreateFromTmdb(5L, "Actor", null)).thenReturn(person);

        subject.fetchForShow(show, 1399);

        verify(creditRepository).deleteByShowEntityId(show.getId());
        ArgumentCaptor<CreditEntity> captor = ArgumentCaptor.forClass(CreditEntity.class);
        verify(creditRepository, times(2)).save(captor.capture());
        List<CreditEntity> saved = captor.getAllValues();
        assertEquals("Role A", saved.get(0).getCharacterName());
        assertEquals("Role B", saved.get(1).getCharacterName());
        assertEquals(show.getId(), saved.get(0).getShowEntityId());
        assertEquals(CreditType.CAST, saved.get(0).getCreditType());
        assertEquals(2, saved.get(0).getCastOrder());
    }

    @Test
    void fetchForEpisodeSavesCastAndGuestStars() {
        EpisodeEntity episode = EpisodeEntity.builder().number(3).build();
        TvEpisodeCredits200Response credits = new TvEpisodeCredits200Response()
                .cast(List.of(new TvEpisodeCredits200ResponseCastInner()
                        .id(5).name("Actor").character("Lead").order(0).creditId("c1")))
                .guestStars(List.of(new TvEpisodeCredits200ResponseGuestStarsInner()
                        .id(9).name("Guest").character("Villain").order(10).creditId("g1")));
        PersonEntity guest = PersonEntity.builder().name("Guest").build();
        when(tmdbClient._tvEpisodeCredits(1399, 2, 3, "en-US")).thenReturn(ResponseEntity.ok(credits));
        when(personLookupService.getOrCreateFromTmdb(5L, "Actor", null)).thenReturn(person);
        when(personLookupService.getOrCreateFromTmdb(9L, "Guest", null)).thenReturn(guest);

        subject.fetchForEpisode(episode, 1399, 2, 3);

        verify(creditRepository).deleteByEpisodeEntityId(episode.getId());
        ArgumentCaptor<CreditEntity> captor = ArgumentCaptor.forClass(CreditEntity.class);
        verify(creditRepository, times(2)).save(captor.capture());
        List<CreditEntity> saved = captor.getAllValues();
        assertEquals(CreditType.CAST, saved.get(0).getCreditType());
        assertEquals("Lead", saved.get(0).getCharacterName());
        assertEquals(CreditType.GUEST_STAR, saved.get(1).getCreditType());
        assertEquals("Villain", saved.get(1).getCharacterName());
        assertEquals(guest, saved.get(1).getPersonEntity());
        assertEquals(episode.getId(), saved.get(1).getEpisodeEntityId());
    }

    @Test
    void nullResponseBodyIsIgnored() {
        MovieEntity movie = MovieEntity.builder().name("Movie").build();
        ResponseEntity<MovieCredits200Response> emptyResponse = ResponseEntity.ok().build();
        when(tmdbClient._movieCredits(603, "en-US")).thenReturn(emptyResponse);

        subject.fetchForMovie(movie, 603);

        verify(creditRepository).deleteByMovieEntityId(movie.getId());
        verify(creditRepository, times(0)).save(any());
        verify(personLookupService, times(0)).getOrCreateFromTmdb(org.mockito.ArgumentMatchers.anyLong(), any(), any());
    }
}
