package app.ister.api.controller;

import app.ister.core.entity.EpisodeEntity;
import app.ister.core.entity.MovieEntity;
import app.ister.core.entity.WatchStatusEntity;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(MockitoExtension.class)
class WatchStatusControllerTest {

    @InjectMocks
    private WatchStatusController subject;

    @Test
    void episodeReturnsEpisodeFromWatchStatus() {
        EpisodeEntity episode = EpisodeEntity.builder().number(1).build();
        WatchStatusEntity ws = WatchStatusEntity.builder().watched(false).episodeEntity(episode).build();

        EpisodeEntity result = subject.episode(ws);

        assertEquals(episode, result);
    }

    @Test
    void movieReturnsMovieFromWatchStatus() {
        MovieEntity movie = MovieEntity.builder().name("Test").releaseYear(2020).build();
        WatchStatusEntity ws = WatchStatusEntity.builder().watched(false).movieEntity(movie).build();

        MovieEntity result = subject.movie(ws);

        assertEquals(movie, result);
    }

    @Test
    void episodeReturnsNullWhenNoEpisode() {
        WatchStatusEntity ws = WatchStatusEntity.builder().watched(false).build();

        EpisodeEntity result = subject.episode(ws);

        assertNull(result);
    }

    @Test
    void movieReturnsNullWhenNoMovie() {
        WatchStatusEntity ws = WatchStatusEntity.builder().watched(false).build();

        MovieEntity result = subject.movie(ws);

        assertNull(result);
    }
}
