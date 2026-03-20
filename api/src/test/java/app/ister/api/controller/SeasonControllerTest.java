package app.ister.api.controller;

import app.ister.core.entity.EpisodeEntity;
import app.ister.core.entity.SeasonEntity;
import app.ister.core.entity.ShowEntity;
import app.ister.core.repository.SeasonRepository;
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

    @Test
    void seasonByIdReturnsFromRepository() {
        UUID id = UUID.randomUUID();
        SeasonEntity season = SeasonEntity.builder().number(1).build();
        when(seasonRepository.findById(id)).thenReturn(Optional.of(season));

        Optional<SeasonEntity> result = subject.seasonById(id);

        assertTrue(result.isPresent());
        assertEquals(season, result.get());
    }

    @Test
    void seasonByIdReturnsEmptyWhenNotFound() {
        UUID id = UUID.randomUUID();
        when(seasonRepository.findById(id)).thenReturn(Optional.empty());

        Optional<SeasonEntity> result = subject.seasonById(id);

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
