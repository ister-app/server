package app.ister.core.service;

import app.ister.core.entity.*;
import app.ister.core.enums.MediaType;
import app.ister.core.repository.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Sort;
import org.springframework.security.core.Authentication;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PlayQueueServiceTest {

    @InjectMocks
    private PlayQueueService subject;

    @Mock
    private PlayQueueRepository playQueueRepository;

    @Mock
    private EpisodeRepository episodeRepository;

    @Mock
    private MovieRepository movieRepository;

    @Mock
    private UserService userService;

    @Mock
    private WatchStatusRepository watchStatusRepository;

    @Mock
    private WatchStatusService watchStatusService;

    @Mock
    private Authentication authentication;

    @Test
    void getPlayQueueReturnsFromRepository() {
        UUID id = UUID.randomUUID();
        PlayQueueEntity queue = PlayQueueEntity.builder().build();
        when(playQueueRepository.findById(id)).thenReturn(Optional.of(queue));

        Optional<PlayQueueEntity> result = subject.getPlayQueue(id);

        assertTrue(result.isPresent());
        assertEquals(queue, result.get());
    }

    @Test
    void createPlayQueueForShowCreatesQueueWithEpisodes() {
        UUID showId = UUID.randomUUID();
        UUID ep1Id = UUID.randomUUID();
        UUID ep2Id = UUID.randomUUID();
        UserEntity user = UserEntity.builder().build();

        EpisodeRepository.IdOnly id1 = () -> ep1Id;
        EpisodeRepository.IdOnly id2 = () -> ep2Id;

        when(authentication.getName()).thenReturn("user1");
        when(userService.getOrCreateUser(authentication)).thenReturn(user);
        when(episodeRepository.findIdsOnlyByShowEntityId(eq(showId), any(Sort.class)))
                .thenReturn(List.of(id1, id2));
        when(playQueueRepository.save(any(PlayQueueEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        PlayQueueEntity result = subject.createPlayQueueForShow(showId, ep1Id, authentication);

        assertNotNull(result);
        assertEquals(2, result.getItems().size());
        assertEquals(ep1Id, result.getItems().get(0).getEpisodeEntityId());
        verify(playQueueRepository, times(2)).save(any(PlayQueueEntity.class));
    }

    @Test
    void createPlayQueueForMovieCreatesQueueWithMovie() {
        UUID movieId = UUID.randomUUID();
        UserEntity user = UserEntity.builder().build();

        when(authentication.getName()).thenReturn("user1");
        when(userService.getOrCreateUser(authentication)).thenReturn(user);
        when(playQueueRepository.save(any(PlayQueueEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        PlayQueueEntity result = subject.createPlayQueueForMovie(movieId, authentication);

        assertNotNull(result);
        assertEquals(1, result.getItems().size());
        assertEquals(movieId, result.getItems().get(0).getMovieEntityId());
        verify(playQueueRepository, times(2)).save(any(PlayQueueEntity.class));
    }

    @Test
    void updatePlayQueueReturnsFalseWhenQueueNotFound() {
        UUID id = UUID.randomUUID();
        when(playQueueRepository.findById(id)).thenReturn(Optional.empty());

        Optional<PlayQueueEntity> result = subject.updatePlayQueue(id, 5000L, UUID.randomUUID(), authentication);

        assertTrue(result.isEmpty());
    }

    @Test
    void updatePlayQueueUpdatesProgressAndSaves() {
        UUID queueId = UUID.randomUUID();
        UUID itemId = UUID.randomUUID();
        UUID epId = UUID.randomUUID();

        PlayQueueItemEntity item = PlayQueueItemEntity.builder()
                .type(MediaType.EPISODE)
                .episodeEntityId(epId)
                .build();
        // Give the item an ID by using reflection trick via builder
        item.setId(itemId);

        PlayQueueEntity queue = PlayQueueEntity.builder()
                .items(new ArrayList<>(List.of(item)))
                .build();

        when(playQueueRepository.findById(queueId)).thenReturn(Optional.of(queue));
        // progress < 60000 so no watch status update
        when(authentication.getName()).thenReturn("user1");

        Optional<PlayQueueEntity> result = subject.updatePlayQueue(queueId, 5000L, itemId, authentication);

        assertTrue(result.isPresent());
        assertEquals(5000L, result.get().getProgressInMilliseconds());
        verify(playQueueRepository).save(queue);
        verifyNoInteractions(episodeRepository, watchStatusService);
    }

    @Test
    void updatePlayQueueUpdatesEpisodeWatchStatusWhenProgressOver60s() {
        UUID queueId = UUID.randomUUID();
        UUID itemId = UUID.randomUUID();
        UUID epId = UUID.randomUUID();

        PlayQueueItemEntity item = PlayQueueItemEntity.builder()
                .type(MediaType.EPISODE)
                .episodeEntityId(epId)
                .build();
        item.setId(itemId);

        PlayQueueEntity queue = PlayQueueEntity.builder()
                .items(new ArrayList<>(List.of(item)))
                .build();

        MediaFileEntity mediaFile = MediaFileEntity.builder().durationInMilliseconds(3600000L).build();
        EpisodeEntity episode = EpisodeEntity.builder()
                .id(epId)
                .mediaFileEntities(List.of(mediaFile))
                .build();
        WatchStatusEntity watchStatus = WatchStatusEntity.builder().watched(false).build();

        when(playQueueRepository.findById(queueId)).thenReturn(Optional.of(queue));
        when(episodeRepository.findById(epId)).thenReturn(Optional.of(episode));
        when(watchStatusService.getOrCreate(authentication, itemId, episode, null)).thenReturn(watchStatus);

        subject.updatePlayQueue(queueId, 90000L, itemId, authentication);

        verify(watchStatusRepository).save(watchStatus);
        assertEquals(90000L, watchStatus.getProgressInMilliseconds());
    }

    @Test
    void updatePlayQueueUpdatesMovieWatchStatusWhenProgressOver60s() {
        UUID queueId = UUID.randomUUID();
        UUID itemId = UUID.randomUUID();
        UUID movieId = UUID.randomUUID();

        PlayQueueItemEntity item = PlayQueueItemEntity.builder()
                .type(MediaType.MOVIE)
                .movieEntityId(movieId)
                .build();
        item.setId(itemId);

        PlayQueueEntity queue = PlayQueueEntity.builder()
                .items(new ArrayList<>(List.of(item)))
                .build();

        MediaFileEntity mediaFile = MediaFileEntity.builder().durationInMilliseconds(7200000L).build();
        MovieEntity movie = MovieEntity.builder()
                .id(movieId)
                .mediaFileEntities(List.of(mediaFile))
                .build();
        WatchStatusEntity watchStatus = WatchStatusEntity.builder().watched(false).build();

        when(playQueueRepository.findById(queueId)).thenReturn(Optional.of(queue));
        when(movieRepository.findById(movieId)).thenReturn(Optional.of(movie));
        when(watchStatusService.getOrCreate(authentication, itemId, null, movie)).thenReturn(watchStatus);

        subject.updatePlayQueue(queueId, 90000L, itemId, authentication);

        verify(watchStatusRepository).save(watchStatus);
    }
}
