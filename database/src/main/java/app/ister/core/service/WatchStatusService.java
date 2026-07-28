package app.ister.core.service;

import app.ister.core.entity.BookEntity;
import app.ister.core.entity.ChapterEntity;
import app.ister.core.entity.EpisodeEntity;
import app.ister.core.entity.MovieEntity;
import app.ister.core.entity.PodcastEpisodeEntity;
import app.ister.core.entity.TrackEntity;
import app.ister.core.entity.UserEntity;
import app.ister.core.entity.WatchStatusEntity;
import app.ister.core.repository.WatchStatusRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;

import java.util.Optional;
import java.util.UUID;

@Service
@Slf4j
@RequiredArgsConstructor
public class WatchStatusService {
    private final UserService userService;
    private final WatchStatusRepository watchStatusRepository;

    // Sonar FP: Lombok @SuperBuilder declares builder() on the subclass itself
    @SuppressWarnings("java:S3252")
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

    /**
     * Listening progress for an audiobook chapter. Deliberately not scoped to a play queue item:
     * the reader web app writes this position too (reading has no queue), and clients read
     * {@code Chapter.watchStatus} expecting a single row. The chapter id doubles as the play queue
     * item id, exactly like {@link #getOrCreateForBook} does with the book id.
     */
    @SuppressWarnings("java:S3252")
    public WatchStatusEntity getOrCreateForChapter(Authentication authentication, ChapterEntity chapterEntity) {
        UserEntity userEntity = userService.getOrCreateUser(authentication);
        return watchStatusRepository.findByUserEntityAndChapterEntity(userEntity, chapterEntity)
                .orElseGet(() -> watchStatusRepository.save(WatchStatusEntity.builder()
                        .userEntity(userEntity)
                        .playQueueItemId(chapterEntity.getId())
                        .chapterEntity(chapterEntity)
                        .watched(false).build()));
    }

    @SuppressWarnings("java:S3252")
    public WatchStatusEntity getOrCreateForPodcastEpisode(Authentication authentication, UUID playQueueItemId, PodcastEpisodeEntity podcastEpisodeEntity) {
        UserEntity userEntity = userService.getOrCreateUser(authentication);
        return watchStatusRepository.findByUserEntityAndPlayQueueItemIdAndPodcastEpisodeEntity(userEntity, playQueueItemId, podcastEpisodeEntity)
                .orElseGet(() -> watchStatusRepository.save(WatchStatusEntity.builder()
                        .userEntity(userEntity)
                        .playQueueItemId(playQueueItemId)
                        .podcastEpisodeEntity(podcastEpisodeEntity)
                        .watched(false).build()));
    }

    /**
     * A music track play. Scoped to the play queue item: replaying a track (a new queue item)
     * creates a new row, so a user's play count for a track is their number of rows.
     */
    @SuppressWarnings("java:S3252")
    public WatchStatusEntity getOrCreateForTrack(Authentication authentication, UUID playQueueItemId, TrackEntity trackEntity) {
        UserEntity userEntity = userService.getOrCreateUser(authentication);
        return watchStatusRepository.findByUserEntityAndPlayQueueItemIdAndTrackEntity(userEntity, playQueueItemId, trackEntity)
                .orElseGet(() -> watchStatusRepository.save(WatchStatusEntity.builder()
                        .userEntity(userEntity)
                        .playQueueItemId(playQueueItemId)
                        .trackEntity(trackEntity)
                        .watched(false).build()));
    }

    /**
     * Watch status for reading an epub. Reading has no play queue, so the book id doubles as the
     * play queue item id (see {@link WatchStatusEntity#getPlayQueueItemId()}), giving one row per
     * user per book.
     */
    @SuppressWarnings("java:S3252")
    public WatchStatusEntity getOrCreateForBook(Authentication authentication, BookEntity bookEntity) {
        UserEntity userEntity = userService.getOrCreateUser(authentication);
        return watchStatusRepository.findByUserEntityAndBookEntity(userEntity, bookEntity)
                .orElseGet(() -> watchStatusRepository.save(WatchStatusEntity.builder()
                        .userEntity(userEntity)
                        .playQueueItemId(bookEntity.getId())
                        .bookEntity(bookEntity)
                        .watched(false).build()));
    }
}
