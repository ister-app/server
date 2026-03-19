package app.ister.core.service;

import app.ister.core.entity.*;
import app.ister.core.repository.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ScannerHelperServiceTest {

    @InjectMocks
    private ScannerHelperService subject;

    @Mock
    private MovieRepository movieRepository;
    @Mock
    private ShowRepository showRepository;
    @Mock
    private SeasonRepository seasonRepository;
    @Mock
    private EpisodeRepository episodeRepository;
    @Mock
    private ServerEventService serverEventService;

    private final LibraryEntity library = LibraryEntity.builder().build();

    @Test
    void getOrCreateMovieReturnsExistingMovie() {
        MovieEntity existing = MovieEntity.builder().name("Movie").releaseYear(2024).build();
        when(movieRepository.findByLibraryEntityAndNameAndReleaseYear(library, "Movie", 2024))
                .thenReturn(Optional.of(existing));

        assertEquals(existing, subject.getOrCreateMovie(library, "Movie", 2024));
        verify(movieRepository, never()).save(any());
        verifyNoInteractions(serverEventService);
    }

    @Test
    void getOrCreateMovieCreatesNewMovie() {
        when(movieRepository.findByLibraryEntityAndNameAndReleaseYear(library, "Movie", 2024))
                .thenReturn(Optional.empty());

        MovieEntity result = subject.getOrCreateMovie(library, "Movie", 2024);

        assertEquals("Movie", result.getName());
        assertEquals(2024, result.getReleaseYear());
        verify(movieRepository).save(result);
        verify(serverEventService).createMovieFoundEvent(result.getId());
    }

    @Test
    void getOrCreateShowReturnsExistingShow() {
        ShowEntity existing = ShowEntity.builder().name("Show").releaseYear(2024).build();
        when(showRepository.findByLibraryEntityAndNameAndReleaseYear(library, "Show", 2024))
                .thenReturn(Optional.of(existing));

        assertEquals(existing, subject.getOrCreateShow(library, "Show", 2024));
        verify(showRepository, never()).save(any());
        verifyNoInteractions(serverEventService);
    }

    @Test
    void getOrCreateShowCreatesNewShow() {
        when(showRepository.findByLibraryEntityAndNameAndReleaseYear(library, "Show", 2024))
                .thenReturn(Optional.empty());

        ShowEntity result = subject.getOrCreateShow(library, "Show", 2024);

        assertEquals("Show", result.getName());
        assertEquals(2024, result.getReleaseYear());
        verify(showRepository).save(result);
        verify(serverEventService).createShowFoundEvent(result.getId());
    }

    @Test
    void getOrCreateSeasonReturnsExistingSeason() {
        ShowEntity show = ShowEntity.builder().build();
        SeasonEntity existing = SeasonEntity.builder().number(1).build();
        when(showRepository.findByLibraryEntityAndNameAndReleaseYear(library, "Show", 2024))
                .thenReturn(Optional.of(show));
        when(seasonRepository.findByShowEntityAndNumber(show, 1))
                .thenReturn(Optional.of(existing));

        assertEquals(existing, subject.getOrCreateSeason(library, "Show", 2024, 1));
        verify(seasonRepository, never()).save(any());
    }

    @Test
    void getOrCreateSeasonCreatesNewSeason() {
        ShowEntity show = ShowEntity.builder().build();
        when(showRepository.findByLibraryEntityAndNameAndReleaseYear(library, "Show", 2024))
                .thenReturn(Optional.of(show));
        when(seasonRepository.findByShowEntityAndNumber(show, 1))
                .thenReturn(Optional.empty());

        SeasonEntity result = subject.getOrCreateSeason(library, "Show", 2024, 1);

        assertEquals(1, result.getNumber());
        verify(seasonRepository).save(result);
    }

    @Test
    void getOrCreateEpisodeReturnsExistingEpisode() {
        ShowEntity show = ShowEntity.builder().build();
        SeasonEntity season = SeasonEntity.builder().number(1).build();
        EpisodeEntity existing = EpisodeEntity.builder().number(1).build();
        when(showRepository.findByLibraryEntityAndNameAndReleaseYear(library, "Show", 2024))
                .thenReturn(Optional.of(show));
        when(seasonRepository.findByShowEntityAndNumber(show, 1))
                .thenReturn(Optional.of(season));
        when(episodeRepository.findByShowEntityAndSeasonEntityAndNumber(show, season, 1))
                .thenReturn(Optional.of(existing));

        assertEquals(existing, subject.getOrCreateEpisode(library, "Show", 2024, 1, 1));
        verify(episodeRepository, never()).save(any());
        verifyNoInteractions(serverEventService);
    }

    @Test
    void getOrCreateEpisodeCreatesNewEpisode() {
        ShowEntity show = ShowEntity.builder().build();
        SeasonEntity season = SeasonEntity.builder().number(1).build();
        when(showRepository.findByLibraryEntityAndNameAndReleaseYear(library, "Show", 2024))
                .thenReturn(Optional.of(show));
        when(seasonRepository.findByShowEntityAndNumber(show, 1))
                .thenReturn(Optional.of(season));
        when(episodeRepository.findByShowEntityAndSeasonEntityAndNumber(show, season, 1))
                .thenReturn(Optional.empty());

        EpisodeEntity result = subject.getOrCreateEpisode(library, "Show", 2024, 1, 1);

        assertEquals(1, result.getNumber());
        verify(episodeRepository).save(result);
        verify(serverEventService).createEpisodeFoundEvent(result.getId());
    }
}
