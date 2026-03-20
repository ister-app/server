package app.ister.api.controller;

import app.ister.core.entity.EpisodeEntity;
import app.ister.core.entity.MovieEntity;
import app.ister.core.entity.UserEntity;
import app.ister.core.entity.WatchStatusEntity;
import app.ister.core.enums.MediaType;
import app.ister.core.repository.EpisodeRepository;
import app.ister.core.repository.MovieRepository;
import app.ister.core.repository.WatchStatusRepository;
import app.ister.core.service.UserService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Sort;
import org.springframework.security.core.Authentication;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RecentlyWatchedControllerTest {

    @InjectMocks
    private RecentlyWatchedController subject;

    @Mock
    private EpisodeRepository episodeRepository;

    @Mock
    private MovieRepository movieRepository;

    @Mock
    private WatchStatusRepository watchStatusRepository;

    @Mock
    private UserService userService;

    @Mock
    private Authentication authentication;

    @Test
    void recentlyWatchedReturnsCurrentEpisodeWhenNotWatched() {
        UUID showId = UUID.randomUUID();
        UUID ep1Id = UUID.randomUUID();

        UserEntity user = UserEntity.builder().build();
        user.setId(UUID.randomUUID());

        WatchStatusEntity unwatched = WatchStatusEntity.builder().watched(false).build();
        unwatched.setDateUpdated(Instant.now());
        EpisodeEntity ep1 = EpisodeEntity.builder().number(1).watchStatusEntities(new ArrayList<>(List.of(unwatched))).build();
        ep1.setId(ep1Id);

        when(authentication.getName()).thenReturn("user1");
        when(userService.getOrCreateUser(authentication)).thenReturn(user);
        when(watchStatusRepository.findRecentEpisodesAndShowIdsByUserId(user.getId()))
                .thenReturn(List.<String[]>of(new String[]{ep1Id.toString(), showId.toString()}));
        when(watchStatusRepository.findRecentMovieIdsByUserId(user.getId())).thenReturn(List.of());
        when(episodeRepository.findByShowEntityId(eq(showId), any(Sort.class)))
                .thenReturn(List.of(ep1));

        List<RecentlyWatched> result = subject.recentlyWatched(authentication);

        assertEquals(1, result.size());
        assertEquals(MediaType.EPISODE, result.get(0).type());
        assertEquals(ep1, result.get(0).episode());
        assertNull(result.get(0).movie());
    }

    @Test
    void recentlyWatchedReturnsNextEpisodeWhenCurrentIsWatched() {
        UUID showId = UUID.randomUUID();
        UUID ep1Id = UUID.randomUUID();
        UUID ep2Id = UUID.randomUUID();

        UserEntity user = UserEntity.builder().build();
        user.setId(UUID.randomUUID());

        WatchStatusEntity watched = WatchStatusEntity.builder().watched(true).build();
        watched.setDateUpdated(Instant.now());

        WatchStatusEntity unwatched = WatchStatusEntity.builder().watched(false).build();
        unwatched.setDateUpdated(Instant.now());

        EpisodeEntity ep1 = EpisodeEntity.builder().number(1).watchStatusEntities(new ArrayList<>(List.of(watched))).build();
        ep1.setId(ep1Id);
        EpisodeEntity ep2 = EpisodeEntity.builder().number(2).watchStatusEntities(new ArrayList<>(List.of(unwatched))).build();
        ep2.setId(ep2Id);

        when(authentication.getName()).thenReturn("user1");
        when(userService.getOrCreateUser(authentication)).thenReturn(user);
        when(watchStatusRepository.findRecentEpisodesAndShowIdsByUserId(user.getId()))
                .thenReturn(List.<String[]>of(new String[]{ep1Id.toString(), showId.toString()}));
        when(watchStatusRepository.findRecentMovieIdsByUserId(user.getId())).thenReturn(List.of());
        when(episodeRepository.findByShowEntityId(eq(showId), any(Sort.class)))
                .thenReturn(List.of(ep1, ep2));

        List<RecentlyWatched> result = subject.recentlyWatched(authentication);

        assertEquals(1, result.size());
        assertEquals(MediaType.EPISODE, result.get(0).type());
        assertEquals(ep2, result.get(0).episode());
    }

    @Test
    void recentlyWatchedReturnsOldWatchedEpisode() {
        UUID showId = UUID.randomUUID();
        UUID ep1Id = UUID.randomUUID();
        UUID ep2Id = UUID.randomUUID();

        UserEntity user = UserEntity.builder().build();
        user.setId(UUID.randomUUID());

        WatchStatusEntity watched = WatchStatusEntity.builder().watched(true).build();
        watched.setDateUpdated(Instant.now());

        WatchStatusEntity oldWatched = WatchStatusEntity.builder().watched(true).build();
        oldWatched.setDateUpdated(Instant.now().minus(301, ChronoUnit.DAYS));

        EpisodeEntity ep1 = EpisodeEntity.builder().number(1).watchStatusEntities(new ArrayList<>(List.of(watched))).build();
        ep1.setId(ep1Id);
        EpisodeEntity ep2 = EpisodeEntity.builder().number(2).watchStatusEntities(new ArrayList<>(List.of(oldWatched))).build();
        ep2.setId(ep2Id);

        when(authentication.getName()).thenReturn("user1");
        when(userService.getOrCreateUser(authentication)).thenReturn(user);
        when(watchStatusRepository.findRecentEpisodesAndShowIdsByUserId(user.getId()))
                .thenReturn(List.<String[]>of(new String[]{ep1Id.toString(), showId.toString()}));
        when(watchStatusRepository.findRecentMovieIdsByUserId(user.getId())).thenReturn(List.of());
        when(episodeRepository.findByShowEntityId(eq(showId), any(Sort.class)))
                .thenReturn(List.of(ep1, ep2));

        List<RecentlyWatched> result = subject.recentlyWatched(authentication);

        assertEquals(1, result.size());
        assertEquals(ep2, result.get(0).episode());
    }

    @Test
    void recentlyWatchedReturnsEmptyWhenAllEpisodesWatched() {
        UUID showId = UUID.randomUUID();
        UUID ep1Id = UUID.randomUUID();

        UserEntity user = UserEntity.builder().build();
        user.setId(UUID.randomUUID());

        WatchStatusEntity watched = WatchStatusEntity.builder().watched(true).build();
        watched.setDateUpdated(Instant.now());

        EpisodeEntity ep1 = EpisodeEntity.builder().number(1).watchStatusEntities(new ArrayList<>(List.of(watched))).build();
        ep1.setId(ep1Id);

        when(authentication.getName()).thenReturn("user1");
        when(userService.getOrCreateUser(authentication)).thenReturn(user);
        when(watchStatusRepository.findRecentEpisodesAndShowIdsByUserId(user.getId()))
                .thenReturn(List.<String[]>of(new String[]{ep1Id.toString(), showId.toString()}));
        when(watchStatusRepository.findRecentMovieIdsByUserId(user.getId())).thenReturn(List.of());
        when(episodeRepository.findByShowEntityId(eq(showId), any(Sort.class)))
                .thenReturn(List.of(ep1));

        List<RecentlyWatched> result = subject.recentlyWatched(authentication);

        assertTrue(result.isEmpty());
    }

    @Test
    void recentlyWatchedReturnsUnwatchedMovie() {
        UUID movieId = UUID.randomUUID();

        UserEntity user = UserEntity.builder().build();
        user.setId(UUID.randomUUID());

        MovieEntity movie = MovieEntity.builder().name("Test Movie").releaseYear(2020)
                .watchStatusEntities(new ArrayList<>()).build();
        movie.setId(movieId);

        when(authentication.getName()).thenReturn("user1");
        when(userService.getOrCreateUser(authentication)).thenReturn(user);
        when(watchStatusRepository.findRecentEpisodesAndShowIdsByUserId(user.getId())).thenReturn(List.of());
        when(watchStatusRepository.findRecentMovieIdsByUserId(user.getId())).thenReturn(List.of(movieId.toString()));
        when(movieRepository.findById(movieId)).thenReturn(Optional.of(movie));

        List<RecentlyWatched> result = subject.recentlyWatched(authentication);

        assertEquals(1, result.size());
        assertEquals(MediaType.MOVIE, result.get(0).type());
        assertEquals(movie, result.get(0).movie());
        assertNull(result.get(0).episode());
    }

    @Test
    void recentlyWatchedExcludesFullyWatchedRecentMovie() {
        UUID movieId = UUID.randomUUID();

        UserEntity user = UserEntity.builder().build();
        user.setId(UUID.randomUUID());

        WatchStatusEntity watched = WatchStatusEntity.builder().watched(true).build();
        watched.setDateUpdated(Instant.now());

        MovieEntity movie = MovieEntity.builder().name("Test Movie").releaseYear(2020)
                .watchStatusEntities(new ArrayList<>(List.of(watched))).build();
        movie.setId(movieId);

        when(authentication.getName()).thenReturn("user1");
        when(userService.getOrCreateUser(authentication)).thenReturn(user);
        when(watchStatusRepository.findRecentEpisodesAndShowIdsByUserId(user.getId())).thenReturn(List.of());
        when(watchStatusRepository.findRecentMovieIdsByUserId(user.getId())).thenReturn(List.of(movieId.toString()));
        when(movieRepository.findById(movieId)).thenReturn(Optional.of(movie));

        List<RecentlyWatched> result = subject.recentlyWatched(authentication);

        assertTrue(result.isEmpty());
    }

    @Test
    void recentlyWatchedIncludesOldWatchedMovie() {
        UUID movieId = UUID.randomUUID();

        UserEntity user = UserEntity.builder().build();
        user.setId(UUID.randomUUID());

        WatchStatusEntity oldWatched = WatchStatusEntity.builder().watched(true).build();
        oldWatched.setDateUpdated(Instant.now().minus(301, ChronoUnit.DAYS));

        MovieEntity movie = MovieEntity.builder().name("Test Movie").releaseYear(2020)
                .watchStatusEntities(new ArrayList<>(List.of(oldWatched))).build();
        movie.setId(movieId);

        when(authentication.getName()).thenReturn("user1");
        when(userService.getOrCreateUser(authentication)).thenReturn(user);
        when(watchStatusRepository.findRecentEpisodesAndShowIdsByUserId(user.getId())).thenReturn(List.of());
        when(watchStatusRepository.findRecentMovieIdsByUserId(user.getId())).thenReturn(List.of(movieId.toString()));
        when(movieRepository.findById(movieId)).thenReturn(Optional.of(movie));

        List<RecentlyWatched> result = subject.recentlyWatched(authentication);

        assertEquals(1, result.size());
        assertEquals(movie, result.get(0).movie());
    }

    @Test
    void recentlyWatchedSortsMovieBeforeEpisodeWhenMovieIsMoreRecent() {
        UUID showId = UUID.randomUUID();
        UUID ep1Id = UUID.randomUUID();
        UUID movieId = UUID.randomUUID();

        UserEntity user = UserEntity.builder().build();
        user.setId(UUID.randomUUID());

        // Episode watched 2 hours ago
        WatchStatusEntity epWatched = WatchStatusEntity.builder().watched(false).build();
        epWatched.setDateUpdated(Instant.now().minus(2, ChronoUnit.HOURS));
        EpisodeEntity ep1 = EpisodeEntity.builder().number(1).watchStatusEntities(new ArrayList<>(List.of(epWatched))).build();
        ep1.setId(ep1Id);

        // Movie watched 1 hour ago (more recent)
        WatchStatusEntity movieWatched = WatchStatusEntity.builder().watched(false).build();
        movieWatched.setDateUpdated(Instant.now().minus(1, ChronoUnit.HOURS));
        MovieEntity movie = MovieEntity.builder().name("Test Movie").releaseYear(2020)
                .watchStatusEntities(new ArrayList<>(List.of(movieWatched))).build();
        movie.setId(movieId);

        when(authentication.getName()).thenReturn("user1");
        when(userService.getOrCreateUser(authentication)).thenReturn(user);
        when(watchStatusRepository.findRecentEpisodesAndShowIdsByUserId(user.getId()))
                .thenReturn(List.<String[]>of(new String[]{ep1Id.toString(), showId.toString()}));
        when(watchStatusRepository.findRecentMovieIdsByUserId(user.getId())).thenReturn(List.of(movieId.toString()));
        when(episodeRepository.findByShowEntityId(eq(showId), any(Sort.class))).thenReturn(List.of(ep1));
        when(movieRepository.findById(movieId)).thenReturn(Optional.of(movie));

        List<RecentlyWatched> result = subject.recentlyWatched(authentication);

        assertEquals(2, result.size());
        assertEquals(MediaType.MOVIE, result.get(0).type());
        assertEquals(MediaType.EPISODE, result.get(1).type());
    }

    @Test
    void recentlyWatchedSortsEpisodeBeforeMovieWhenEpisodeIsMoreRecent() {
        UUID showId = UUID.randomUUID();
        UUID ep1Id = UUID.randomUUID();
        UUID movieId = UUID.randomUUID();

        UserEntity user = UserEntity.builder().build();
        user.setId(UUID.randomUUID());

        // Episode watched 30 minutes ago (more recent)
        WatchStatusEntity epWatched = WatchStatusEntity.builder().watched(false).build();
        epWatched.setDateUpdated(Instant.now().minus(30, ChronoUnit.MINUTES));
        EpisodeEntity ep1 = EpisodeEntity.builder().number(1).watchStatusEntities(new ArrayList<>(List.of(epWatched))).build();
        ep1.setId(ep1Id);

        // Movie watched 2 hours ago
        WatchStatusEntity movieWatched = WatchStatusEntity.builder().watched(false).build();
        movieWatched.setDateUpdated(Instant.now().minus(2, ChronoUnit.HOURS));
        MovieEntity movie = MovieEntity.builder().name("Test Movie").releaseYear(2020)
                .watchStatusEntities(new ArrayList<>(List.of(movieWatched))).build();
        movie.setId(movieId);

        when(authentication.getName()).thenReturn("user1");
        when(userService.getOrCreateUser(authentication)).thenReturn(user);
        when(watchStatusRepository.findRecentEpisodesAndShowIdsByUserId(user.getId()))
                .thenReturn(List.<String[]>of(new String[]{ep1Id.toString(), showId.toString()}));
        when(watchStatusRepository.findRecentMovieIdsByUserId(user.getId())).thenReturn(List.of(movieId.toString()));
        when(episodeRepository.findByShowEntityId(eq(showId), any(Sort.class))).thenReturn(List.of(ep1));
        when(movieRepository.findById(movieId)).thenReturn(Optional.of(movie));

        List<RecentlyWatched> result = subject.recentlyWatched(authentication);

        assertEquals(2, result.size());
        assertEquals(MediaType.EPISODE, result.get(0).type());
        assertEquals(MediaType.MOVIE, result.get(1).type());
    }
}
