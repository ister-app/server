package app.ister.server.service;

import app.ister.server.entitiy.EpisodeEntity;
import app.ister.server.entitiy.MovieEntity;
import app.ister.server.entitiy.UserEntity;
import app.ister.server.entitiy.WatchStatusEntity;
import app.ister.server.repository.WatchStatusRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;

import java.util.Optional;
import java.util.UUID;

@Service
@Slf4j
public class WatchStatusService {
    @Autowired
    private UserService userService;
    @Autowired
    private WatchStatusRepository watchStatusRepository;

    public WatchStatusEntity getOrCreate(Authentication authentication, UUID playQueueItemId, EpisodeEntity episodeEntity, MovieEntity movieEntity) {
        UserEntity userEntity = userService.getOrCreateUser(authentication);
        Optional<WatchStatusEntity> user = watchStatusRepository.findByUserEntityAndPlayQueueItemIdAndEpisodeEntity(userEntity, playQueueItemId, episodeEntity);
        if (user.isPresent()) {
            return user.get();
        } else {
            WatchStatusEntity watchStatusEntity = WatchStatusEntity.builder()
                    .userEntity(userEntity)
                    .playQueueItemId(playQueueItemId)
                    .episodeEntity(episodeEntity)
                    .movieEntity(movieEntity)
                    .watched(false).build();
            watchStatusRepository.save(watchStatusEntity);
            return watchStatusEntity;
        }
    }
}
