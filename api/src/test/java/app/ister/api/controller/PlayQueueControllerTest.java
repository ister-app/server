package app.ister.api.controller;

import app.ister.core.entity.EpisodeEntity;
import app.ister.core.entity.MovieEntity;
import app.ister.core.entity.PlayQueueEntity;
import app.ister.core.entity.PlayQueueItemEntity;
import app.ister.core.enums.MediaType;
import app.ister.core.repository.EpisodeRepository;
import app.ister.core.repository.MovieRepository;
import app.ister.core.service.PlayQueueService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.Authentication;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PlayQueueControllerTest {

    @InjectMocks
    private PlayQueueController subject;

    @Mock
    private PlayQueueService playQueueService;

    @Mock
    private EpisodeRepository episodeRepository;

    @Mock
    private MovieRepository movieRepository;

    @Mock
    private Authentication authentication;

    private PlayQueueItemEntity buildItem(UUID id) {
        PlayQueueItemEntity item = PlayQueueItemEntity.builder()
                .type(MediaType.EPISODE)
                .position(BigDecimal.valueOf(0))
                .build();
        item.setId(id);
        return item;
    }

    @Test
    void getPlayQueueDelegatesToService() {
        UUID id = UUID.randomUUID();
        PlayQueueEntity queue = PlayQueueEntity.builder().build();
        when(playQueueService.getPlayQueue(id)).thenReturn(Optional.of(queue));

        Optional<PlayQueueEntity> result = subject.getPlayQueue(id, authentication);

        assertTrue(result.isPresent());
        verify(playQueueService).getPlayQueue(id);
    }

    @Test
    void createPlayQueueForShowDelegatesToService() {
        UUID showId = UUID.randomUUID();
        UUID episodeId = UUID.randomUUID();
        PlayQueueEntity queue = PlayQueueEntity.builder().build();
        when(playQueueService.createPlayQueueForShow(showId, episodeId, authentication)).thenReturn(queue);

        PlayQueueEntity result = subject.createPlayQueueForShow(showId, episodeId, authentication);

        assertEquals(queue, result);
    }

    @Test
    void createPlayQueueForMovieDelegatesToService() {
        UUID movieId = UUID.randomUUID();
        PlayQueueEntity queue = PlayQueueEntity.builder().build();
        when(playQueueService.createPlayQueueForMovie(movieId, authentication)).thenReturn(queue);

        PlayQueueEntity result = subject.createPlayQueueForMovie(movieId, authentication);

        assertEquals(queue, result);
    }

    @Test
    void updatePlayQueueDelegatesToService() {
        UUID id = UUID.randomUUID();
        UUID itemId = UUID.randomUUID();
        PlayQueueEntity queue = PlayQueueEntity.builder().build();
        when(playQueueService.updatePlayQueue(id, 5000L, itemId, authentication)).thenReturn(Optional.of(queue));

        Optional<PlayQueueEntity> result = subject.updatePlayQueue(id, 5000L, itemId, authentication);

        assertTrue(result.isPresent());
    }

    @Test
    void currentItemIdReturnsCurrentItemFromPlayQueue() {
        UUID currentId = UUID.randomUUID();
        PlayQueueEntity queue = PlayQueueEntity.builder().currentItem(currentId).build();

        UUID result = subject.currentItemId(queue);

        assertEquals(currentId, result);
    }

    // --- playQueueItems windowing logic ---

    @Test
    void playQueueItemsReturnsFirst21WhenNoCurrentItem() {
        List<PlayQueueItemEntity> items = IntStream.range(0, 30)
                .mapToObj(i -> buildItem(UUID.randomUUID()))
                .toList();
        PlayQueueEntity queue = PlayQueueEntity.builder().items(new ArrayList<>(items)).build();

        List<PlayQueueItemEntity> result = subject.playQueueItems(queue);

        assertEquals(21, result.size());
        assertEquals(items.get(0), result.get(0));
    }

    @Test
    void playQueueItemsReturnsAllWhenLessThan21AndNoCurrentItem() {
        List<PlayQueueItemEntity> items = IntStream.range(0, 5)
                .mapToObj(i -> buildItem(UUID.randomUUID()))
                .toList();
        PlayQueueEntity queue = PlayQueueEntity.builder().items(new ArrayList<>(items)).build();

        List<PlayQueueItemEntity> result = subject.playQueueItems(queue);

        assertEquals(5, result.size());
    }

    @Test
    void playQueueItemsWindowsCenteredOnCurrentItem() {
        List<UUID> ids = IntStream.range(0, 25).mapToObj(i -> UUID.randomUUID()).toList();
        List<PlayQueueItemEntity> items = ids.stream().map(this::buildItem).toList();
        // current item is at index 12
        UUID currentId = ids.get(12);
        PlayQueueEntity queue = PlayQueueEntity.builder()
                .items(new ArrayList<>(items))
                .currentItem(currentId)
                .build();

        List<PlayQueueItemEntity> result = subject.playQueueItems(queue);

        // 10 before + current + 10 after = 21
        assertEquals(21, result.size());
        assertEquals(items.get(2), result.get(0));   // index 12 - 10 = 2
        assertEquals(items.get(12), result.get(10)); // current at index 10 in result
        assertEquals(items.get(22), result.get(20)); // index 12 + 10 = 22
    }

    @Test
    void playQueueItemsClipsToStartWhenCurrentIsNearBeginning() {
        List<UUID> ids = IntStream.range(0, 25).mapToObj(i -> UUID.randomUUID()).toList();
        List<PlayQueueItemEntity> items = ids.stream().map(this::buildItem).toList();
        // current item is at index 3
        UUID currentId = ids.get(3);
        PlayQueueEntity queue = PlayQueueEntity.builder()
                .items(new ArrayList<>(items))
                .currentItem(currentId)
                .build();

        List<PlayQueueItemEntity> result = subject.playQueueItems(queue);

        // from = max(0, 3-10) = 0, to = min(25, 3+11) = 14 → 14 items
        assertEquals(14, result.size());
        assertEquals(items.get(0), result.get(0));
    }

    @Test
    void playQueueItemsClipsToEndWhenCurrentIsNearEnd() {
        List<UUID> ids = IntStream.range(0, 25).mapToObj(i -> UUID.randomUUID()).toList();
        List<PlayQueueItemEntity> items = ids.stream().map(this::buildItem).toList();
        // current item is at index 22
        UUID currentId = ids.get(22);
        PlayQueueEntity queue = PlayQueueEntity.builder()
                .items(new ArrayList<>(items))
                .currentItem(currentId)
                .build();

        List<PlayQueueItemEntity> result = subject.playQueueItems(queue);

        // from = max(0, 22-10) = 12, to = min(25, 22+11) = 25 → 13 items
        assertEquals(13, result.size());
        assertEquals(items.get(12), result.get(0));
        assertEquals(items.get(24), result.getLast());
    }

    @Test
    void playQueueItemsReturnsFirst21WhenCurrentItemNotFound() {
        List<UUID> ids = IntStream.range(0, 30).mapToObj(i -> UUID.randomUUID()).toList();
        List<PlayQueueItemEntity> items = ids.stream().map(this::buildItem).toList();
        PlayQueueEntity queue = PlayQueueEntity.builder()
                .items(new ArrayList<>(items))
                .currentItem(UUID.randomUUID()) // not in list
                .build();

        List<PlayQueueItemEntity> result = subject.playQueueItems(queue);

        assertEquals(21, result.size());
    }

    @Test
    void currentItemEpisodeReturnsEpisodeWhenCurrentItemSet() {
        UUID episodeId = UUID.randomUUID();
        EpisodeEntity episode = EpisodeEntity.builder().number(1).build();
        PlayQueueEntity queue = PlayQueueEntity.builder().currentItem(episodeId).build();
        when(episodeRepository.findById(episodeId)).thenReturn(Optional.of(episode));

        Optional<EpisodeEntity> result = subject.currentItemEpisode(queue);

        assertTrue(result.isPresent());
        assertEquals(episode, result.get());
    }

    @Test
    void currentItemEpisodeReturnsEmptyWhenNoCurrentItem() {
        PlayQueueEntity queue = PlayQueueEntity.builder().build();

        Optional<EpisodeEntity> result = subject.currentItemEpisode(queue);

        assertTrue(result.isEmpty());
        verifyNoInteractions(episodeRepository);
    }

    @Test
    void currentItemMovieReturnsMovieWhenCurrentItemSet() {
        UUID movieId = UUID.randomUUID();
        MovieEntity movie = MovieEntity.builder().name("Test").releaseYear(2020).build();
        PlayQueueEntity queue = PlayQueueEntity.builder().currentItem(movieId).build();
        when(movieRepository.findById(movieId)).thenReturn(Optional.of(movie));

        Optional<MovieEntity> result = subject.currentItemMovie(queue);

        assertTrue(result.isPresent());
        assertEquals(movie, result.get());
    }

    @Test
    void currentItemMovieReturnsEmptyWhenNoCurrentItem() {
        PlayQueueEntity queue = PlayQueueEntity.builder().build();

        Optional<MovieEntity> result = subject.currentItemMovie(queue);

        assertTrue(result.isEmpty());
        verifyNoInteractions(movieRepository);
    }

    @Test
    void playQueueItemEpisodeReturnsEpisodeWhenEpisodeIdSet() {
        UUID episodeId = UUID.randomUUID();
        EpisodeEntity episode = EpisodeEntity.builder().number(1).build();
        PlayQueueItemEntity item = PlayQueueItemEntity.builder()
                .type(MediaType.EPISODE)
                .position(BigDecimal.ZERO)
                .build();
        item.setEpisodeEntityId(episodeId);
        when(episodeRepository.findById(episodeId)).thenReturn(Optional.of(episode));

        Optional<EpisodeEntity> result = subject.playQueueItemEpisode(item);

        assertTrue(result.isPresent());
    }

    @Test
    void playQueueItemEpisodeReturnsEmptyWhenNoEpisodeId() {
        PlayQueueItemEntity item = PlayQueueItemEntity.builder()
                .type(MediaType.MOVIE)
                .position(BigDecimal.ZERO)
                .build();

        Optional<EpisodeEntity> result = subject.playQueueItemEpisode(item);

        assertTrue(result.isEmpty());
        verifyNoInteractions(episodeRepository);
    }

    @Test
    void playQueueItemMovieReturnsMovieWhenMovieIdSet() {
        UUID movieId = UUID.randomUUID();
        MovieEntity movie = MovieEntity.builder().name("Test").releaseYear(2020).build();
        PlayQueueItemEntity item = PlayQueueItemEntity.builder()
                .type(MediaType.MOVIE)
                .position(BigDecimal.ZERO)
                .build();
        item.setMovieEntityId(movieId);
        when(movieRepository.findById(movieId)).thenReturn(Optional.of(movie));

        Optional<MovieEntity> result = subject.playQueueItemMovie(item);

        assertTrue(result.isPresent());
    }

    @Test
    void playQueueItemMovieReturnsEmptyWhenNoMovieId() {
        PlayQueueItemEntity item = PlayQueueItemEntity.builder()
                .type(MediaType.EPISODE)
                .position(BigDecimal.ZERO)
                .build();

        Optional<MovieEntity> result = subject.playQueueItemMovie(item);

        assertTrue(result.isEmpty());
        verifyNoInteractions(movieRepository);
    }
}
