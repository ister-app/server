package app.ister.core.service;

import app.ister.core.entity.PodcastEntity;
import app.ister.core.entity.UserEntity;
import app.ister.core.entity.UserPodcastPreferenceEntity;
import app.ister.core.enums.SortingOrder;
import app.ister.core.repository.PodcastRepository;
import app.ister.core.repository.UserPodcastPreferenceRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;

/**
 * Stores each user's per-podcast episode sort order. Kept server-side so the choice applies to
 * every client the user has. A podcast the user never touched has no row and sorts DESCENDING.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class PodcastPreferenceService {
    /** Feed order: what a podcast list has always shown, and what an unset preference means. */
    public static final SortingOrder DEFAULT_EPISODE_ORDER = SortingOrder.DESCENDING;

    private final UserService userService;
    private final UserPodcastPreferenceRepository userPodcastPreferenceRepository;
    private final PodcastRepository podcastRepository;

    /** The caller's episode order for this podcast, or the default when they never set one. */
    @Transactional(readOnly = true)
    public SortingOrder getEpisodeOrder(Authentication authentication, UUID podcastId) {
        UserEntity userEntity = userService.getOrCreateUser(authentication);
        return userPodcastPreferenceRepository
                .findByUserEntityAndPodcastEntity(userEntity, podcast(podcastId))
                .map(UserPodcastPreferenceEntity::getEpisodeOrder)
                .orElse(DEFAULT_EPISODE_ORDER);
    }

    /**
     * Sets the caller's episode order for this podcast.
     *
     * @throws NoSuchElementException if the podcast does not exist
     */
    @Transactional
    public void setEpisodeOrder(Authentication authentication, UUID podcastId, SortingOrder episodeOrder) {
        UserEntity userEntity = userService.getOrCreateUser(authentication);
        PodcastEntity podcastEntity = podcast(podcastId);
        Optional<UserPodcastPreferenceEntity> existing =
                userPodcastPreferenceRepository.findByUserEntityAndPodcastEntity(userEntity, podcastEntity);

        if (existing.isPresent()) {
            UserPodcastPreferenceEntity preference = existing.get();
            preference.setEpisodeOrder(episodeOrder);
            userPodcastPreferenceRepository.save(preference);
        } else {
            userPodcastPreferenceRepository.save(UserPodcastPreferenceEntity.builder()
                    .userEntity(userEntity)
                    .podcastEntity(podcastEntity)
                    .episodeOrder(episodeOrder)
                    .build());
        }
    }

    private PodcastEntity podcast(UUID id) {
        return podcastRepository.findById(id)
                .orElseThrow(() -> new NoSuchElementException("Podcast not found: " + id));
    }
}
