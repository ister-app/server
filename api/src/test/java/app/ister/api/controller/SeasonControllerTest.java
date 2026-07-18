package app.ister.api.controller;

import app.ister.core.entity.EpisodeEntity;
import app.ister.core.entity.SeasonEntity;
import app.ister.core.entity.ShowEntity;
import app.ister.core.repository.SeasonRepository;
import app.ister.core.service.LibraryAccessService;
import org.springframework.security.core.Authentication;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SeasonControllerTest {

    @InjectMocks
    private SeasonController subject;

    @Mock
    private SeasonRepository seasonRepository;

    @Mock
    private LibraryAccessService libraryAccessService;

    @Mock
    private Authentication authentication;

    @Test
    void seasonByIdReturnsFromRepository() {
        UUID id = UUID.randomUUID();
        ShowEntity showOfSeason = ShowEntity.builder().name("Test").releaseYear(2020)
                .libraryEntity(app.ister.core.entity.LibraryEntity.builder().name("Shows").build()).build();
        SeasonEntity season = SeasonEntity.builder().number(1).showEntity(showOfSeason).build();
        when(seasonRepository.findById(id)).thenReturn(Optional.of(season));
        when(libraryAccessService.canAccess(any(app.ister.core.entity.LibraryEntity.class), any())).thenReturn(true);

        Optional<SeasonEntity> result = subject.seasonById(id, authentication);

        assertTrue(result.isPresent());
        assertEquals(season, result.get());
    }

    @Test
    void seasonByIdReturnsEmptyWhenNotFound() {
        UUID id = UUID.randomUUID();
        when(seasonRepository.findById(id)).thenReturn(Optional.empty());

        Optional<SeasonEntity> result = subject.seasonById(id, authentication);

        assertTrue(result.isEmpty());
    }

    @Test
    void showReturnsSeasonsShow() {
        ShowEntity show = ShowEntity.builder().name("Test").releaseYear(2020).build();
        SeasonEntity season = SeasonEntity.builder().number(1).showEntity(show).build();

        ShowEntity result = subject.show(season);

        assertEquals(show, result);
    }

    @Test
    void seasonReturnsEpisodesFromSeason() {
        EpisodeEntity ep1 = EpisodeEntity.builder().number(1).build();
        EpisodeEntity ep2 = EpisodeEntity.builder().number(2).build();
        SeasonEntity season = SeasonEntity.builder().number(1).episodeEntities(List.of(ep1, ep2)).build();

        List<EpisodeEntity> result = subject.season(season);

        assertEquals(2, result.size());
    }
}
