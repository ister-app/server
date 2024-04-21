package app.ister.server.controller;

import app.ister.server.entitiy.PlayQueueEntity;
import app.ister.server.entitiy.PlayQueueItemEntity;
import app.ister.server.entitiy.UserEntity;
import app.ister.server.entitiy.WatchStatusEntity;
import app.ister.server.repository.EpisodeRepository;
import app.ister.server.repository.PlayQueueRepository;
import app.ister.server.repository.WatchStatusRepository;
import app.ister.server.service.UserService;
import app.ister.server.service.WatchStatusService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Sort;
import org.springframework.graphql.data.method.annotation.Argument;
import org.springframework.graphql.data.method.annotation.MutationMapping;
import org.springframework.graphql.data.method.annotation.SchemaMapping;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Controller
@Slf4j
public class PlayQueueController {
    @Autowired
    private PlayQueueRepository playQueueRepository;

    @Autowired
    private EpisodeRepository episodeRepository;

    @Autowired
    private UserService userService;

    @Autowired
    private WatchStatusRepository watchStatusRepository;

    @Autowired
    private WatchStatusService watchStatusService;

    @PreAuthorize("hasRole('user')")
    @MutationMapping
    public PlayQueueEntity createPlayQueue(@Argument UUID showId, Authentication authentication) {
        log.debug("Creating play queue for user: {}, show: {}", authentication.getName(), showId);
        List<PlayQueueItemEntity> items = episodeRepository.findIdsOnlyByShowEntityId(
                        UUID.fromString(showId.toString()),
                        Sort.by("SeasonEntityNumber").ascending().and(
                                Sort.by("number").ascending())).stream()
                .map(idOnly -> PlayQueueItemEntity.builder().id(UUID.randomUUID()).itemId(idOnly.getId()).build()).collect(Collectors.toList());
        UserEntity userEntity = userService.getOrCreateUser(authentication);
        var playQueueEntity = PlayQueueEntity.builder().userEntity(userEntity).items(items).build();
        playQueueRepository.save(playQueueEntity);
        return playQueueEntity;
    }

    @PreAuthorize("hasRole('user')")
    @MutationMapping
    public Boolean updatePlayQueue(@Argument UUID id, @Argument long progressInMilliseconds, @Argument UUID playQueueItemId, Authentication authentication) {
        log.debug("Updating play queue for user: {}", authentication.getName());
        // Update the current playing episode
        playQueueRepository.findById(id).ifPresent(playQueueEntity -> {
            playQueueEntity.getItems().stream().filter(item -> item.getId().equals(playQueueItemId)).findAny().ifPresent(playQueueItemEntity -> {
                playQueueEntity.setCurrentItem(playQueueItemEntity.getId());
                playQueueRepository.save(playQueueEntity);
                // Update the watch status of an episode if it's played for more then one minute
                if (progressInMilliseconds > 60000) {
                    episodeRepository.findById(playQueueItemEntity.getItemId()).ifPresent(episodeEntity -> {
                        WatchStatusEntity watchStatusEntity = watchStatusService.getOrCreate(authentication, playQueueItemId, episodeEntity);
                        watchStatusEntity.setProgressInMilliseconds(progressInMilliseconds);
                        if (!episodeEntity.getMediaFileEntities().isEmpty()) {
                            long durationOfMediaFile = episodeEntity.getMediaFileEntities().get(0).getDurationInMilliseconds();
                            boolean durationIsLessThenOneMinute = durationOfMediaFile - progressInMilliseconds < 60000;
                            watchStatusEntity.setWatched(durationIsLessThenOneMinute);
                        }
                        watchStatusRepository.save(watchStatusEntity);
                    });
                }
            });
        });
        return true;
    }

    @SchemaMapping(typeName = "PlayQueue", field = "playQueueItems")
    public List<PlayQueueItemEntity> playQueueItems(PlayQueueEntity playQueueEntity) {
        return playQueueEntity.getItems();
    }
}
