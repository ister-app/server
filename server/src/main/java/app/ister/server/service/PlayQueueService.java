package app.ister.server.service;

import app.ister.server.entitiy.MediaFileEntity;
import app.ister.server.entitiy.PlayQueueEntity;
import app.ister.server.entitiy.PlayQueueItemEntity;
import app.ister.server.entitiy.UserEntity;
import app.ister.server.entitiy.WatchStatusEntity;
import app.ister.server.enums.MediaType;
import app.ister.server.repository.EpisodeRepository;
import app.ister.server.repository.MovieRepository;
import app.ister.server.repository.PlayQueueRepository;
import app.ister.server.repository.WatchStatusRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Sort;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Slf4j
@Service
public class PlayQueueService {
    @Autowired
    private PlayQueueRepository playQueueRepository;

    @Autowired
    private EpisodeRepository episodeRepository;

    @Autowired
    private MovieRepository movieRepository;

    @Autowired
    private UserService userService;

    @Autowired
    private WatchStatusRepository watchStatusRepository;

    @Autowired
    private WatchStatusService watchStatusService;

    public PlayQueueEntity createPlayQueueForShow(UUID showId, UUID episodeId, Authentication authentication) {
        log.debug("Creating play queue for user: {}, show: {}", authentication.getName(), showId);
        List<PlayQueueItemEntity> items = episodeRepository.findIdsOnlyByShowEntityId(
                        UUID.fromString(showId.toString()),
                        Sort.by("SeasonEntityNumber").ascending().and(
                                Sort.by("number").ascending())).stream()
                .map(idOnly -> PlayQueueItemEntity.builder()
                        .id(UUID.randomUUID())
                        .itemId(idOnly.getId())
                        .type(MediaType.EPISODE).build())
                .collect(Collectors.toList());
        UserEntity userEntity = userService.getOrCreateUser(authentication);
        PlayQueueItemEntity playQueueItemEntity = items.stream().filter(item -> item.getItemId().equals(episodeId)).findAny().get();
        var playQueueEntity = PlayQueueEntity.builder()
                .userEntity(userEntity)
                .items(items)
                .currentItem(playQueueItemEntity.getId()).build();
        playQueueRepository.save(playQueueEntity);
        return playQueueEntity;
    }

    public PlayQueueEntity createPlayQueueForMovie(UUID movieId, Authentication authentication) {
        log.debug("Creating play queue for user: {}, movie: {}", authentication.getName(), movieId);
        PlayQueueItemEntity playQueueItemEntity = PlayQueueItemEntity.builder().id(UUID.randomUUID()).type(MediaType.MOVIE).itemId(movieId).build();
        List<PlayQueueItemEntity> playQueueItemEntities = List.of(playQueueItemEntity);
        var playQueueEntity = PlayQueueEntity.builder()
                .userEntity(userService.getOrCreateUser(authentication))
                .currentItem(playQueueItemEntity.getId())
                .items(playQueueItemEntities).build();
        playQueueRepository.save(playQueueEntity);
        return playQueueEntity;
    }

    public Boolean updatePlayQueue(UUID id, long progressInMilliseconds, UUID playQueueItemId, Authentication authentication) {
        log.debug("Updating play queue for user: {}", authentication.getName());
        // Update the current playing episode
        playQueueRepository.findById(id).ifPresent(playQueueEntity -> updatePlayQueueItemWithProgress(progressInMilliseconds, playQueueItemId, authentication, playQueueEntity));
        return true;
    }

    private void updatePlayQueueItemWithProgress(long progressInMilliseconds, UUID playQueueItemId, Authentication authentication, PlayQueueEntity playQueueEntity) {
        playQueueEntity.getItems().stream().filter(item -> item.getId().equals(playQueueItemId)).findAny().ifPresent(playQueueItemEntity -> {
            playQueueEntity.setCurrentItem(playQueueItemEntity.getId());
            playQueueEntity.setProgressInMilliseconds(progressInMilliseconds);
            playQueueRepository.save(playQueueEntity);
            // Update the watch status of an episode if it's played for more then one minute
            if (progressInMilliseconds > 60000) {
                switch (playQueueItemEntity.getType()) {
                    case EPISODE -> updateEpisodeWatchStatus(progressInMilliseconds, playQueueItemId, authentication, playQueueItemEntity);
                    case MOVIE -> updateMovieWatchStatus(progressInMilliseconds, playQueueItemId, authentication, playQueueItemEntity);
                }

            }
        });
    }

    private void updateEpisodeWatchStatus(long progressInMilliseconds, UUID playQueueItemId, Authentication authentication, PlayQueueItemEntity playQueueItemEntity) {
        episodeRepository.findById(playQueueItemEntity.getItemId()).ifPresent(episodeEntity -> {
            WatchStatusEntity watchStatusEntity = watchStatusService.getOrCreate(authentication, playQueueItemId, episodeEntity, null);
            updateWatchStatus(progressInMilliseconds, watchStatusEntity, episodeEntity.getMediaFileEntities());
        });
    }

    private void updateMovieWatchStatus(long progressInMilliseconds, UUID playQueueItemId, Authentication authentication, PlayQueueItemEntity playQueueItemEntity) {
        movieRepository.findById(playQueueItemEntity.getItemId()).ifPresent(movieEntity -> {
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
