package app.ister.server.service;

import app.ister.server.entitiy.MediaFileEntity;
import app.ister.server.entitiy.PlayQueueEntity;
import app.ister.server.entitiy.PlayQueueItemEntity;
import app.ister.server.entitiy.WatchStatusEntity;
import app.ister.server.enums.MediaType;
import app.ister.server.repository.EpisodeRepository;
import app.ister.server.repository.MovieRepository;
import app.ister.server.repository.PlayQueueRepository;
import app.ister.server.repository.WatchStatusRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Sort;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Slf4j
@Service
public class PlayQueueService {
    private final PlayQueueRepository playQueueRepository;

    private final EpisodeRepository episodeRepository;

    private final MovieRepository movieRepository;

    private final UserService userService;

    private final WatchStatusRepository watchStatusRepository;

    private final WatchStatusService watchStatusService;

    private static final BigDecimal GAP = new BigDecimal("1000");

    public PlayQueueService(PlayQueueRepository playQueueRepository, EpisodeRepository episodeRepository, MovieRepository movieRepository, UserService userService, WatchStatusRepository watchStatusRepository, WatchStatusService watchStatusService) {
        this.playQueueRepository = playQueueRepository;
        this.episodeRepository = episodeRepository;
        this.movieRepository = movieRepository;
        this.userService = userService;
        this.watchStatusRepository = watchStatusRepository;
        this.watchStatusService = watchStatusService;
    }

    public Optional<PlayQueueEntity> getPlayQueue(UUID id) {
        return playQueueRepository.findById(id);
    }

    /**
     * Returns the next position value given the previous one (or null for the first item).
     */
    private BigDecimal nextPosition(BigDecimal previous) {
        return (previous == null) ? GAP : previous.add(GAP);
    }

    public PlayQueueEntity createPlayQueueForShow(UUID showId,
                                                  UUID episodeId,
                                                  Authentication authentication) {
        log.debug("Creating play queue for user: {}, show: {}", authentication.getName(), showId);

        List<UUID> episodeIds = episodeRepository
                .findIdsOnlyByShowEntityId(
                        showId,
                        Sort.by("SeasonEntityNumber").ascending()
                                .and(Sort.by("number").ascending()))
                .stream()
                .map(EpisodeRepository.IdOnly::getId)
                .toList();

        List<PlayQueueItemEntity> items = new ArrayList<>();
        BigDecimal pos = null;
        for (UUID epId : episodeIds) {
            pos = nextPosition(pos);
            PlayQueueItemEntity item = PlayQueueItemEntity.builder()
                    .position(pos)
                    .type(MediaType.EPISODE)
                    .episodeEntityId(epId)
                    .build();
            items.add(item);
        }

        PlayQueueEntity queue = PlayQueueEntity.builder()
                .userEntity(userService.getOrCreateUser(authentication))
                .items(items)
                .build();
        playQueueRepository.save(queue);

        UUID currentItemId = queue.getItems().stream()
                .filter(i -> i.getEpisodeEntityId().equals(episodeId))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Episode not in queue"))
                .getId();
        queue.setCurrentItem(currentItemId);
        playQueueRepository.save(queue);

        return queue;
    }


    public PlayQueueEntity createPlayQueueForMovie(UUID movieId,
                                                   Authentication authentication) {
        log.debug("Creating play queue for user: {}, movie: {}", authentication.getName(), movieId);

        PlayQueueItemEntity item = PlayQueueItemEntity.builder()
                .position(GAP)
                .type(MediaType.MOVIE)
                .movieEntityId(movieId)
                .build();

        PlayQueueEntity queue = PlayQueueEntity.builder()
                .userEntity(userService.getOrCreateUser(authentication))
                .items(List.of(item))
                .currentItem(item.getId())
                .build();
        playQueueRepository.save(queue);

        UUID currentItemId = queue.getItems().stream()
                .filter(i -> i.getMovieEntityId().equals(movieId))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Movie not in queue"))
                .getId();
        queue.setCurrentItem(currentItemId);
        playQueueRepository.save(queue);

        return queue;
    }

    /**
     * Find the PlayQueue and then update it.
     */
    public Optional<PlayQueueEntity> updatePlayQueue(UUID id, long progressInMilliseconds, UUID playQueueItemId, Authentication authentication) {
        log.debug("Updating play queue for user: {}", authentication.getName());
        // Update the current playing episode
        Optional<PlayQueueEntity> playQueueEntityOptional = playQueueRepository.findById(id);
        playQueueEntityOptional.ifPresent(playQueueEntity -> updatePlayQueueItemWithProgress(progressInMilliseconds, playQueueItemId, authentication, playQueueEntity));
        return playQueueEntityOptional;
    }

    private void updatePlayQueueItemWithProgress(long progressInMilliseconds, UUID playQueueItemId, Authentication authentication, PlayQueueEntity playQueueEntity) {
        playQueueEntity.getItems().stream().filter(item -> item.getId().equals(playQueueItemId)).findAny().ifPresent(playQueueItemEntity -> {
            playQueueEntity.setCurrentItem(playQueueItemEntity.getId());
            playQueueEntity.setProgressInMilliseconds(progressInMilliseconds);
            playQueueRepository.save(playQueueEntity);
            // Update the watch status of an episode if it's played for more then one minute
            if (progressInMilliseconds > 60000) {
                switch (playQueueItemEntity.getType()) {
                    case EPISODE ->
                            updateEpisodeWatchStatus(progressInMilliseconds, playQueueItemId, authentication, playQueueItemEntity);
                    case MOVIE ->
                            updateMovieWatchStatus(progressInMilliseconds, playQueueItemId, authentication, playQueueItemEntity);
                }

            }
        });
    }

    private void updateEpisodeWatchStatus(long progressInMilliseconds, UUID playQueueItemId, Authentication authentication, PlayQueueItemEntity playQueueItemEntity) {
        episodeRepository.findById(playQueueItemEntity.getEpisodeEntityId()).ifPresent(episodeEntity -> {
            WatchStatusEntity watchStatusEntity = watchStatusService.getOrCreate(authentication, playQueueItemId, episodeEntity, null);
            updateWatchStatus(progressInMilliseconds, watchStatusEntity, episodeEntity.getMediaFileEntities());
        });
    }

    private void updateMovieWatchStatus(long progressInMilliseconds, UUID playQueueItemId, Authentication authentication, PlayQueueItemEntity playQueueItemEntity) {
        movieRepository.findById(playQueueItemEntity.getMovieEntityId()).ifPresent(movieEntity -> {
            WatchStatusEntity watchStatusEntity = watchStatusService.getOrCreate(authentication, playQueueItemId, null, movieEntity);
            updateWatchStatus(progressInMilliseconds, watchStatusEntity, movieEntity.getMediaFileEntities());
        });
    }

    private void updateWatchStatus(long progressInMilliseconds, WatchStatusEntity watchStatusEntity, List<MediaFileEntity> mediaFileEntities) {
        watchStatusEntity.setProgressInMilliseconds(progressInMilliseconds);
        if (!mediaFileEntities.isEmpty()) {
            long durationOfMediaFile = mediaFileEntities.get(0).getDurationInMilliseconds();
            boolean durationIsLessThenOneMinute = durationOfMediaFile - progressInMilliseconds < 60000;
            watchStatusEntity.setWatched(durationIsLessThenOneMinute);
        }
        watchStatusRepository.save(watchStatusEntity);
    }

}
