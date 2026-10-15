package app.ister.core.service;

import app.ister.core.entity.MediaFileEntity;
import app.ister.core.entity.PlayQueueItemEntity;
import app.ister.core.entity.TrackEntity;
import app.ister.core.entity.UserEntity;
import app.ister.core.entity.WatchStatusEntity;
import app.ister.core.enums.MediaType;
import app.ister.core.repository.ChapterRepository;
import app.ister.core.repository.EpisodeRepository;
import app.ister.core.repository.MovieRepository;
import app.ister.core.repository.PodcastEpisodeRepository;
import app.ister.core.repository.TrackRepository;
import app.ister.core.repository.WatchStatusRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The recorder on its own; the heartbeat path through {@code PlayQueueService.updatePlayQueue}
 * is covered in {@code PlayQueueServiceTest}.
 */
@ExtendWith(MockitoExtension.class)
class PlayQueueWatchStatusRecorderTest {

    @Mock private EpisodeRepository episodeRepository;
    @Mock private MovieRepository movieRepository;
    @Mock private TrackRepository trackRepository;
    @Mock private ChapterRepository chapterRepository;
    @Mock private PodcastEpisodeRepository podcastEpisodeRepository;
    @Mock private WatchStatusRepository watchStatusRepository;
    @Mock private WatchStatusService watchStatusService;
    @Mock private ContinueWatchingService continueWatchingService;
    @Mock private MediaFileEpisodeService mediaFileEpisodeService;

    @InjectMocks
    private PlayQueueWatchStatusRecorder subject;

    private final UserEntity owner = UserEntity.builder().id(UUID.randomUUID()).build();
    private final UserEntity follower = UserEntity.builder().id(UUID.randomUUID()).build();

    private static PlayQueueItemEntity trackItem(UUID trackId) {
        PlayQueueItemEntity item = PlayQueueItemEntity.builder().type(MediaType.TRACK).build();
        item.setId(UUID.randomUUID());
        item.setTrackEntityId(trackId);
        return item;
    }

    private static TrackEntity trackWithDuration(long durationInMilliseconds) {
        return TrackEntity.builder()
                .mediaFileEntities(List.of(MediaFileEntity.builder().durationInMilliseconds(durationInMilliseconds).build()))
                .build();
    }

    @Test
    void belowTheTrackThresholdNothingIsWritten() {
        PlayQueueItemEntity item = trackItem(UUID.randomUUID());
        when(trackRepository.findById(item.getTrackEntityId())).thenReturn(Optional.of(trackWithDuration(180_000)));

        subject.recordWatchStatuses(5_000, item.getId(), List.of(owner), item);

        verifyNoInteractions(watchStatusService, watchStatusRepository, continueWatchingService);
    }

    /** Every listener gets a row, and each write keeps continue-watching in the same transaction. */
    @Test
    void aPlayIsRecordedForTheOwnerAndEveryFollower() {
        PlayQueueItemEntity item = trackItem(UUID.randomUUID());
        TrackEntity track = trackWithDuration(180_000);
        when(trackRepository.findById(item.getTrackEntityId())).thenReturn(Optional.of(track));
        WatchStatusEntity ownerStatus = WatchStatusEntity.builder().build();
        WatchStatusEntity followerStatus = WatchStatusEntity.builder().build();
        when(watchStatusService.getOrCreateForTrack(owner, item.getId(), track)).thenReturn(ownerStatus);
        when(watchStatusService.getOrCreateForTrack(follower, item.getId(), track)).thenReturn(followerStatus);

        subject.recordWatchStatuses(179_500, item.getId(), List.of(owner, follower), item);

        verify(watchStatusRepository).save(ownerStatus);
        verify(watchStatusRepository).save(followerStatus);
        verify(continueWatchingService).onWatchStatusChanged(ownerStatus);
        verify(continueWatchingService).onWatchStatusChanged(followerStatus);
        assertEquals(179_500, followerStatus.getProgressInMilliseconds());
        assertTrue(ownerStatus.isWatched(), "within a minute of the end counts as watched");
    }

    @Test
    void progressWithoutAKnownEndLeavesWatchedUntouched() {
        PlayQueueItemEntity item = trackItem(UUID.randomUUID());
        TrackEntity track = TrackEntity.builder().mediaFileEntities(List.of()).build();
        when(trackRepository.findById(item.getTrackEntityId())).thenReturn(Optional.of(track));
        WatchStatusEntity status = WatchStatusEntity.builder().build();
        when(watchStatusService.getOrCreateForTrack(owner, item.getId(), track)).thenReturn(status);

        subject.recordWatchStatuses(45_000, item.getId(), List.of(owner), item);

        assertEquals(45_000, status.getProgressInMilliseconds());
        assertFalse(status.isWatched());
    }
}
