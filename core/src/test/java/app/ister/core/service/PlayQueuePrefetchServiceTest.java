package app.ister.core.service;

import app.ister.core.entity.DirectoryEntity;
import app.ister.core.entity.EpisodeEntity;
import app.ister.core.entity.MediaFileEntity;
import app.ister.core.entity.MediaFileStreamEntity;
import app.ister.core.entity.PlayQueueEntity;
import app.ister.core.entity.PlayQueueItemEntity;
import app.ister.core.entity.TrackEntity;
import app.ister.core.entity.UserEntity;
import app.ister.core.enums.DirectoryType;
import app.ister.core.enums.MediaType;
import app.ister.core.enums.SubtitleFormat;
import app.ister.core.eventdata.TranscodeRequestedData;
import app.ister.core.repository.EpisodeRepository;
import app.ister.core.repository.MovieRepository;
import app.ister.core.repository.TrackRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class PlayQueuePrefetchServiceTest {

    private static final long TEN_MINUTES_MS = 10 * 60 * 1000L;
    private static final UUID USER_ID = UUID.randomUUID();

    @InjectMocks
    private PlayQueuePrefetchService subject;

    @Mock private MovieRepository movieRepository;
    @Mock private EpisodeRepository episodeRepository;
    @Mock private TrackRepository trackRepository;
    @Mock private MessageSender messageSender;
    @Mock private UserSettingsService userSettingsService;

    @BeforeEach
    void setUp() {
        lenient().when(userSettingsService.forUser(USER_ID)).thenReturn(
                new UserSettingsService.UserSettings(List.of("nl", "en"), List.of("nl"), true, true, null));
        ReflectionTestUtils.setField(subject, "prefetchEnabled", true);
        ReflectionTestUtils.setField(subject, "videoThresholdSeconds", 120L);
        ReflectionTestUtils.setField(subject, "trackThresholdSeconds", 60L);
        ReflectionTestUtils.setField(subject, "trackDepth", 2);
        ReflectionTestUtils.setField(subject, "keepHours", 24L);
    }

    // ===== Video =====

    @Test
    void prefetchesNextEpisodeNearEndOfCurrent() {
        UUID currentEpisodeId = UUID.randomUUID();
        UUID nextEpisodeId = UUID.randomUUID();
        UUID nextMediaFileId = UUID.randomUUID();
        stubEpisode(currentEpisodeId, mediaFile(UUID.randomUUID(), "disk1", TEN_MINUTES_MS));
        stubEpisode(nextEpisodeId, mediaFile(nextMediaFileId, "disk1", TEN_MINUTES_MS));

        PlayQueueItemEntity current = episodeItem(currentEpisodeId);
        PlayQueueItemEntity next = episodeItem(nextEpisodeId);
        PlayQueueEntity queue = queue(current, next);

        // 60 seconds remaining of a 10-minute episode
        subject.maybePrefetchNext(queue, current.getId(), TEN_MINUTES_MS - 60_000);

        ArgumentCaptor<TranscodeRequestedData> captor = ArgumentCaptor.forClass(TranscodeRequestedData.class);
        verify(messageSender).sendTranscodeRequested(captor.capture(), eq("disk1"));
        TranscodeRequestedData sent = captor.getValue();
        assertEquals(nextMediaFileId, sent.getMediaFileId());
        assertEquals(Boolean.TRUE, sent.getPreTranscode());
        assertTrue(sent.getKeepUntilEpochMillis() > System.currentTimeMillis() + 23 * 3_600_000L,
                "prefetched item should be kept for ~24 hours");
        // Defaults when the client never reported stream settings
        assertEquals(Boolean.FALSE, sent.getDirect());
        assertEquals(Boolean.TRUE, sent.getTranscode());
        assertEquals(SubtitleFormat.WEBVTT, sent.getSubtitleFormat());
    }

    @Test
    void doesNotPrefetchWhenFarFromEnd() {
        UUID currentEpisodeId = UUID.randomUUID();
        stubEpisode(currentEpisodeId, mediaFile(UUID.randomUUID(), "disk1", TEN_MINUTES_MS));

        PlayQueueItemEntity current = episodeItem(currentEpisodeId);
        PlayQueueItemEntity next = episodeItem(UUID.randomUUID());
        PlayQueueEntity queue = queue(current, next);

        subject.maybePrefetchNext(queue, current.getId(), 60_000);

        verifyNoInteractions(messageSender);
    }

    @Test
    void prefetchUsesReportedStreamSettings() {
        UUID currentEpisodeId = UUID.randomUUID();
        UUID nextEpisodeId = UUID.randomUUID();
        stubEpisode(currentEpisodeId, mediaFile(UUID.randomUUID(), "disk1", TEN_MINUTES_MS));
        stubEpisode(nextEpisodeId, mediaFile(UUID.randomUUID(), "disk1", TEN_MINUTES_MS));

        PlayQueueItemEntity current = episodeItem(currentEpisodeId);
        PlayQueueItemEntity next = episodeItem(nextEpisodeId);
        PlayQueueEntity queue = queue(current, next);
        queue.setStreamDirect(true);
        queue.setStreamTranscode(false);
        queue.setStreamSubtitleFormat(SubtitleFormat.SRT);

        subject.maybePrefetchNext(queue, current.getId(), TEN_MINUTES_MS - 60_000);

        ArgumentCaptor<TranscodeRequestedData> captor = ArgumentCaptor.forClass(TranscodeRequestedData.class);
        verify(messageSender).sendTranscodeRequested(captor.capture(), eq("disk1"));
        assertEquals(Boolean.TRUE, captor.getValue().getDirect());
        assertEquals(Boolean.FALSE, captor.getValue().getTranscode());
        assertEquals(SubtitleFormat.SRT, captor.getValue().getSubtitleFormat());
    }

    @Test
    void prefetchIsIdempotentAcrossProgressUpdates() {
        UUID currentEpisodeId = UUID.randomUUID();
        UUID nextEpisodeId = UUID.randomUUID();
        stubEpisode(currentEpisodeId, mediaFile(UUID.randomUUID(), "disk1", TEN_MINUTES_MS));
        stubEpisode(nextEpisodeId, mediaFile(UUID.randomUUID(), "disk1", TEN_MINUTES_MS));

        PlayQueueItemEntity current = episodeItem(currentEpisodeId);
        PlayQueueItemEntity next = episodeItem(nextEpisodeId);
        PlayQueueEntity queue = queue(current, next);

        subject.maybePrefetchNext(queue, current.getId(), TEN_MINUTES_MS - 60_000);
        subject.maybePrefetchNext(queue, current.getId(), TEN_MINUTES_MS - 50_000);

        verify(messageSender, times(1)).sendTranscodeRequested(any(), eq("disk1"));
    }

    @Test
    void unanalyzedNextMediaFileIsSkipped() {
        UUID currentEpisodeId = UUID.randomUUID();
        UUID nextEpisodeId = UUID.randomUUID();
        stubEpisode(currentEpisodeId, mediaFile(UUID.randomUUID(), "disk1", TEN_MINUTES_MS));
        MediaFileEntity unanalyzed = mediaFile(UUID.randomUUID(), "disk1", TEN_MINUTES_MS);
        ReflectionTestUtils.setField(unanalyzed, "mediaFileStreamEntity", List.of());
        stubEpisode(nextEpisodeId, unanalyzed);

        PlayQueueItemEntity current = episodeItem(currentEpisodeId);
        PlayQueueItemEntity next = episodeItem(nextEpisodeId);
        PlayQueueEntity queue = queue(current, next);

        subject.maybePrefetchNext(queue, current.getId(), TEN_MINUTES_MS - 60_000);

        verifyNoInteractions(messageSender);
    }

    @Test
    void disabledPrefetchSendsNothing() {
        ReflectionTestUtils.setField(subject, "prefetchEnabled", false);
        PlayQueueItemEntity current = episodeItem(UUID.randomUUID());
        PlayQueueEntity queue = queue(current, episodeItem(UUID.randomUUID()));

        subject.maybePrefetchNext(queue, current.getId(), TEN_MINUTES_MS - 60_000);

        verifyNoInteractions(messageSender);
    }

    // ===== Tracks =====

    @Test
    void prefetchesNextTwoTracksWhenHalfOfTrackIsPlayed() {
        long trackDuration = 3 * 60 * 1000L;
        UUID currentTrackId = UUID.randomUUID();
        UUID next1Id = UUID.randomUUID();
        UUID next2Id = UUID.randomUUID();
        stubTrack(currentTrackId, mediaFile(UUID.randomUUID(), "music", trackDuration));
        stubTrack(next1Id, mediaFile(UUID.randomUUID(), "music", trackDuration));
        stubTrack(next2Id, mediaFile(UUID.randomUUID(), "music", trackDuration));

        PlayQueueItemEntity current = trackItem(currentTrackId);
        PlayQueueItemEntity next1 = trackItem(next1Id);
        PlayQueueItemEntity next2 = trackItem(next2Id);
        PlayQueueEntity queue = queue(current, next1, next2);

        subject.maybePrefetchNext(queue, current.getId(), trackDuration / 2 + 1_000);

        verify(messageSender, times(2)).sendTranscodeRequested(any(), eq("music"));
    }

    // ===== Helpers =====

    private PlayQueueEntity queue(PlayQueueItemEntity... items) {
        UserEntity user = UserEntity.builder().externalId("user-1").build();
        ReflectionTestUtils.setField(user, "id", USER_ID);
        PlayQueueEntity queue = PlayQueueEntity.builder()
                .items(new ArrayList<>(List.of(items)))
                .userEntity(user)
                .build();
        ReflectionTestUtils.setField(queue, "id", UUID.randomUUID());
        return queue;
    }

    private PlayQueueItemEntity episodeItem(UUID episodeId) {
        PlayQueueItemEntity item = PlayQueueItemEntity.builder()
                .type(MediaType.EPISODE)
                .episodeEntityId(episodeId)
                .position(BigDecimal.ONE)
                .build();
        ReflectionTestUtils.setField(item, "id", UUID.randomUUID());
        return item;
    }

    private PlayQueueItemEntity trackItem(UUID trackId) {
        PlayQueueItemEntity item = PlayQueueItemEntity.builder()
                .type(MediaType.TRACK)
                .trackEntityId(trackId)
                .position(BigDecimal.ONE)
                .build();
        ReflectionTestUtils.setField(item, "id", UUID.randomUUID());
        return item;
    }

    private void stubEpisode(UUID episodeId, MediaFileEntity... files) {
        EpisodeEntity episode = EpisodeEntity.builder().number(1).build();
        ReflectionTestUtils.setField(episode, "id", episodeId);
        ReflectionTestUtils.setField(episode, "mediaFileEntities", List.of(files));
        lenient().when(episodeRepository.findById(episodeId)).thenReturn(Optional.of(episode));
    }

    private void stubTrack(UUID trackId, MediaFileEntity... files) {
        TrackEntity track = TrackEntity.builder().number(1).discNumber(1).build();
        ReflectionTestUtils.setField(track, "id", trackId);
        ReflectionTestUtils.setField(track, "mediaFileEntities", List.of(files));
        lenient().when(trackRepository.findById(trackId)).thenReturn(Optional.of(track));
    }

    private MediaFileEntity mediaFile(UUID id, String diskName, long durationMs) {
        DirectoryEntity dir = DirectoryEntity.builder()
                .name(diskName)
                .path("/" + diskName)
                .directoryType(DirectoryType.LIBRARY)
                .build();
        MediaFileEntity mf = MediaFileEntity.builder()
                .path("/test/file.mkv")
                .size(0)
                .durationInMilliseconds(durationMs)
                .directoryEntityId(UUID.randomUUID())
                .build();
        ReflectionTestUtils.setField(mf, "id", id);
        ReflectionTestUtils.setField(mf, "mediaFileStreamEntity", List.of(new MediaFileStreamEntity()));
        mf.setDirectoryEntity(dir);
        return mf;
    }
}
