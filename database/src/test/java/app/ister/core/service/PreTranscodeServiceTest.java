package app.ister.core.service;

import app.ister.core.entity.DirectoryEntity;
import app.ister.core.entity.EpisodeEntity;
import app.ister.core.entity.MediaFileEntity;
import app.ister.core.entity.MediaFileStreamEntity;
import app.ister.core.entity.MovieEntity;
import app.ister.core.entity.UserEntity;
import app.ister.core.enums.DirectoryType;
import app.ister.core.repository.EpisodeRepository;
import app.ister.core.repository.MovieRepository;
import app.ister.core.repository.UserRepository;
import app.ister.core.repository.WatchStatusRepository;
import app.ister.core.service.PreTranscodeService.PreTranscodeTarget;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Sort;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PreTranscodeServiceTest {

    private static final int NEXT_EPISODE_RECENT_DAYS = 150;

    @InjectMocks
    private PreTranscodeService subject;

    @Mock private UserRepository userRepository;
    @Mock private WatchStatusRepository watchStatusRepository;
    @Mock private EpisodeRepository episodeRepository;
    @Mock private MovieRepository movieRepository;
    @Mock private UserSettingsService userSettingsService;

    @BeforeEach
    void setUp() {
        lenient().when(userSettingsService.forUser(any())).thenReturn(
                new UserSettingsService.UserSettings(List.of("en", "nl"), List.of("nl"), true, true, null));
        ReflectionTestUtils.setField(subject, "nextEpisodeRecentDays", NEXT_EPISODE_RECENT_DAYS);
    }

    // ===== Episodes =====

    @Test
    void recentlyWatchedEpisodeIsAlwaysIncluded() {
        UUID userId = UUID.randomUUID();
        UUID episodeId = UUID.randomUUID();
        UUID mediaFileId = UUID.randomUUID();
        EpisodeEntity episode = episode(episodeId, mediaFile(mediaFileId, "disk1"));

        when(userRepository.findAll()).thenReturn(List.of(user(userId)));
        when(watchStatusRepository.findRecentEpisodesWithDateByUserId(userId))
                .thenReturn(rows(episodeId, UUID.randomUUID(), daysAgo(30)));
        when(watchStatusRepository.findRecentUnwatchedMovieIdsByUserId(userId)).thenReturn(List.of());
        when(episodeRepository.findById(episodeId)).thenReturn(Optional.of(episode));

        Set<UUID> result = subject.collectMediaFilesToPreTranscode("disk1").mediaFileIds();

        assertTrue(result.contains(mediaFileId));
    }

    @Test
    void nextEpisodeIncludedWhenWatchedWithinWindow() {
        UUID userId = UUID.randomUUID();
        UUID showId = UUID.randomUUID();
        UUID ep1Id = UUID.randomUUID();
        UUID ep2Id = UUID.randomUUID();
        UUID mf1Id = UUID.randomUUID();
        UUID mf2Id = UUID.randomUUID();
        EpisodeEntity ep1 = episode(ep1Id, mediaFile(mf1Id, "disk1"));
        EpisodeEntity ep2 = episode(ep2Id, mediaFile(mf2Id, "disk1"));

        when(userRepository.findAll()).thenReturn(List.of(user(userId)));
        when(watchStatusRepository.findRecentEpisodesWithDateByUserId(userId))
                .thenReturn(rows(ep1Id, showId, daysAgo(20)));
        when(watchStatusRepository.findRecentUnwatchedMovieIdsByUserId(userId)).thenReturn(List.of());
        when(episodeRepository.findById(ep1Id)).thenReturn(Optional.of(ep1));
        when(episodeRepository.findByShowEntityId(eq(showId), any(Sort.class)))
                .thenReturn(List.of(ep1, ep2));

        Set<UUID> result = subject.collectMediaFilesToPreTranscode("disk1").mediaFileIds();

        assertTrue(result.contains(mf1Id), "last-watched episode should be included");
        assertTrue(result.contains(mf2Id), "next episode should be included when watched within window");
    }

    @Test
    void nextEpisodeNotIncludedWhenWatchedOutsideWindow() {
        UUID userId = UUID.randomUUID();
        UUID showId = UUID.randomUUID();
        UUID ep1Id = UUID.randomUUID();
        UUID ep2Id = UUID.randomUUID();
        UUID mf1Id = UUID.randomUUID();
        UUID mf2Id = UUID.randomUUID();
        EpisodeEntity ep1 = episode(ep1Id, mediaFile(mf1Id, "disk1"));
        EpisodeEntity ep2 = episode(ep2Id, mediaFile(mf2Id, "disk1"));

        lenient().when(episodeRepository.findByShowEntityId(any(), any())).thenReturn(List.of(ep1, ep2));

        when(userRepository.findAll()).thenReturn(List.of(user(userId)));
        when(watchStatusRepository.findRecentEpisodesWithDateByUserId(userId))
                .thenReturn(rows(ep1Id, showId, daysAgo(NEXT_EPISODE_RECENT_DAYS + 1)));
        when(watchStatusRepository.findRecentUnwatchedMovieIdsByUserId(userId)).thenReturn(List.of());
        when(episodeRepository.findById(ep1Id)).thenReturn(Optional.of(ep1));

        Set<UUID> result = subject.collectMediaFilesToPreTranscode("disk1").mediaFileIds();

        assertTrue(result.contains(mf1Id), "last-watched episode should still be included");
        assertFalse(result.contains(mf2Id), "next episode should NOT be included when watched outside window");
        verify(episodeRepository, never()).findByShowEntityId(any(), any());
    }

    @Test
    void nextEpisodeWindowIsConfigurable() {
        ReflectionTestUtils.setField(subject, "nextEpisodeRecentDays", 7);
        UUID userId = UUID.randomUUID();
        UUID showId = UUID.randomUUID();
        UUID ep1Id = UUID.randomUUID();
        UUID mf1Id = UUID.randomUUID();
        UUID mf2Id = UUID.randomUUID();
        EpisodeEntity ep1 = episode(ep1Id, mediaFile(mf1Id, "disk1"));
        EpisodeEntity ep2 = episode(UUID.randomUUID(), mediaFile(mf2Id, "disk1"));

        lenient().when(episodeRepository.findByShowEntityId(any(), any())).thenReturn(List.of(ep1, ep2));

        when(userRepository.findAll()).thenReturn(List.of(user(userId)));
        when(watchStatusRepository.findRecentEpisodesWithDateByUserId(userId))
                .thenReturn(rows(ep1Id, showId, daysAgo(8)));
        when(watchStatusRepository.findRecentUnwatchedMovieIdsByUserId(userId)).thenReturn(List.of());
        when(episodeRepository.findById(ep1Id)).thenReturn(Optional.of(ep1));

        Set<UUID> result = subject.collectMediaFilesToPreTranscode("disk1").mediaFileIds();

        assertFalse(result.contains(mf2Id), "next episode should respect the configured window");
    }

    @Test
    void noNextEpisodeWhenLastInShow() {
        UUID userId = UUID.randomUUID();
        UUID showId = UUID.randomUUID();
        UUID epId = UUID.randomUUID();
        UUID mfId = UUID.randomUUID();
        EpisodeEntity ep = episode(epId, mediaFile(mfId, "disk1"));

        when(userRepository.findAll()).thenReturn(List.of(user(userId)));
        when(watchStatusRepository.findRecentEpisodesWithDateByUserId(userId))
                .thenReturn(rows(epId, showId, daysAgo(1)));
        when(watchStatusRepository.findRecentUnwatchedMovieIdsByUserId(userId)).thenReturn(List.of());
        when(episodeRepository.findById(epId)).thenReturn(Optional.of(ep));
        when(episodeRepository.findByShowEntityId(eq(showId), any(Sort.class)))
                .thenReturn(List.of(ep));

        Set<UUID> result = subject.collectMediaFilesToPreTranscode("disk1").mediaFileIds();

        assertEquals(Set.of(mfId), result);
    }

    @Test
    void fullyWatchedEpisodeIsExcludedButNextEpisodeIsIncluded() {
        UUID userId = UUID.randomUUID();
        UUID showId = UUID.randomUUID();
        UUID ep1Id = UUID.randomUUID();
        UUID ep2Id = UUID.randomUUID();
        UUID mf1Id = UUID.randomUUID();
        UUID mf2Id = UUID.randomUUID();
        EpisodeEntity ep1 = episode(ep1Id, mediaFile(mf1Id, "disk1"));
        EpisodeEntity ep2 = episode(ep2Id, mediaFile(mf2Id, "disk1"));

        when(userRepository.findAll()).thenReturn(List.of(user(userId)));
        when(watchStatusRepository.findRecentEpisodesWithDateByUserId(userId))
                .thenReturn(rows(ep1Id, showId, daysAgo(1), true));
        when(watchStatusRepository.findRecentUnwatchedMovieIdsByUserId(userId)).thenReturn(List.of());
        when(episodeRepository.findById(ep1Id)).thenReturn(Optional.of(ep1));
        when(episodeRepository.findByShowEntityId(eq(showId), any(Sort.class)))
                .thenReturn(List.of(ep1, ep2));

        Set<UUID> result = subject.collectMediaFilesToPreTranscode("disk1").mediaFileIds();

        assertFalse(result.contains(mf1Id), "fully watched episode should not be pre-transcoded");
        assertTrue(result.contains(mf2Id), "next episode should be included");
    }

    // ===== Movies =====

    @Test
    void recentlyWatchedMovieIsIncluded() {
        UUID userId = UUID.randomUUID();
        UUID movieId = UUID.randomUUID();
        UUID mediaFileId = UUID.randomUUID();
        MovieEntity movie = movie(movieId, mediaFile(mediaFileId, "disk1"));

        when(userRepository.findAll()).thenReturn(List.of(user(userId)));
        when(watchStatusRepository.findRecentEpisodesWithDateByUserId(userId)).thenReturn(List.of());
        when(watchStatusRepository.findRecentUnwatchedMovieIdsByUserId(userId))
                .thenReturn(List.of(movieId.toString()));
        when(movieRepository.findById(movieId)).thenReturn(Optional.of(movie));

        Set<UUID> result = subject.collectMediaFilesToPreTranscode("disk1").mediaFileIds();

        assertTrue(result.contains(mediaFileId));
    }

    @Test
    void movieOnOtherDiskIsExcluded() {
        UUID userId = UUID.randomUUID();
        UUID movieId = UUID.randomUUID();
        UUID mediaFileId = UUID.randomUUID();
        MovieEntity movie = movie(movieId, mediaFile(mediaFileId, "disk2"));

        when(userRepository.findAll()).thenReturn(List.of(user(userId)));
        when(watchStatusRepository.findRecentEpisodesWithDateByUserId(userId)).thenReturn(List.of());
        when(watchStatusRepository.findRecentUnwatchedMovieIdsByUserId(userId))
                .thenReturn(List.of(movieId.toString()));
        when(movieRepository.findById(movieId)).thenReturn(Optional.of(movie));

        Set<UUID> result = subject.collectMediaFilesToPreTranscode("disk1").mediaFileIds();

        assertFalse(result.contains(mediaFileId));
    }

    // ===== Unanalyzed media files =====

    @Test
    void unanalyzedMediaFileIsReportedSeparatelyAndNotQueued() {
        UUID userId = UUID.randomUUID();
        UUID episodeId = UUID.randomUUID();
        UUID mediaFileId = UUID.randomUUID();
        MediaFileEntity mf = mediaFileWithoutStreams(mediaFileId, "disk1");
        EpisodeEntity episode = episode(episodeId, mf);

        when(userRepository.findAll()).thenReturn(List.of(user(userId)));
        when(watchStatusRepository.findRecentEpisodesWithDateByUserId(userId))
                .thenReturn(rows(episodeId, UUID.randomUUID(), daysAgo(30)));
        when(watchStatusRepository.findRecentUnwatchedMovieIdsByUserId(userId)).thenReturn(List.of());
        when(episodeRepository.findById(episodeId)).thenReturn(Optional.of(episode));

        PreTranscodeService.PreTranscodeCollection result = subject.collectMediaFilesToPreTranscode("disk1");

        assertFalse(result.mediaFileIds().contains(mediaFileId), "unanalyzed file should not be queued for transcode");
        assertEquals(1, result.unanalyzedFiles().size());
        PreTranscodeService.UnanalyzedMediaFile unanalyzed = result.unanalyzedFiles().iterator().next();
        assertEquals(mediaFileId, unanalyzed.mediaFileId());
        assertEquals(episodeId, unanalyzed.episodeId());
        assertEquals(mf.getPath(), unanalyzed.path());
        assertNull(unanalyzed.movieId());
    }

    // ===== Filtering & deduplication =====

    @Test
    void mediaFilesOnOtherDiskAreExcluded() {
        UUID userId = UUID.randomUUID();
        UUID episodeId = UUID.randomUUID();
        UUID mfOnDisk1 = UUID.randomUUID();
        UUID mfOnDisk2 = UUID.randomUUID();
        EpisodeEntity ep = episode(episodeId, mediaFile(mfOnDisk1, "disk1"), mediaFile(mfOnDisk2, "disk2"));

        when(userRepository.findAll()).thenReturn(List.of(user(userId)));
        when(watchStatusRepository.findRecentEpisodesWithDateByUserId(userId))
                .thenReturn(rows(episodeId, UUID.randomUUID(), daysAgo(30)));
        when(watchStatusRepository.findRecentUnwatchedMovieIdsByUserId(userId)).thenReturn(List.of());
        when(episodeRepository.findById(episodeId)).thenReturn(Optional.of(ep));

        Set<UUID> result = subject.collectMediaFilesToPreTranscode("disk1").mediaFileIds();

        assertTrue(result.contains(mfOnDisk1));
        assertFalse(result.contains(mfOnDisk2));
    }

    @Test
    void sameEpisodeWatchedByTwoUsersIsDeduplicatedInResult() {
        UUID userId1 = UUID.randomUUID();
        UUID userId2 = UUID.randomUUID();
        UUID episodeId = UUID.randomUUID();
        UUID mediaFileId = UUID.randomUUID();
        EpisodeEntity ep = episode(episodeId, mediaFile(mediaFileId, "disk1"));

        when(userRepository.findAll()).thenReturn(List.of(user(userId1), user(userId2)));
        when(watchStatusRepository.findRecentEpisodesWithDateByUserId(userId1))
                .thenReturn(rows(episodeId, UUID.randomUUID(), daysAgo(30)));
        when(watchStatusRepository.findRecentEpisodesWithDateByUserId(userId2))
                .thenReturn(rows(episodeId, UUID.randomUUID(), daysAgo(30)));
        when(watchStatusRepository.findRecentUnwatchedMovieIdsByUserId(any())).thenReturn(List.of());
        when(episodeRepository.findById(episodeId)).thenReturn(Optional.of(ep));

        Set<UUID> result = subject.collectMediaFilesToPreTranscode("disk1").mediaFileIds();

        assertEquals(1, result.size());
        assertTrue(result.contains(mediaFileId));
    }

    // ===== User settings drive what gets transcoded =====

    @Test
    void audioLanguagesOfEveryInterestedUserAreMerged() {
        UUID userId1 = UUID.randomUUID();
        UUID userId2 = UUID.randomUUID();
        UUID episodeId = UUID.randomUUID();
        UUID mediaFileId = UUID.randomUUID();
        EpisodeEntity ep = episode(episodeId, mediaFile(mediaFileId, "disk1"));

        when(userSettingsService.forUser(userId1)).thenReturn(
                new UserSettingsService.UserSettings(List.of("nl"), List.of("nl"), true, true, 480));
        when(userSettingsService.forUser(userId2)).thenReturn(
                new UserSettingsService.UserSettings(List.of("en"), List.of("en"), true, true, 720));
        when(userRepository.findAll()).thenReturn(List.of(user(userId1), user(userId2)));
        when(watchStatusRepository.findRecentEpisodesWithDateByUserId(any()))
                .thenReturn(rows(episodeId, UUID.randomUUID(), daysAgo(30)));
        when(watchStatusRepository.findRecentUnwatchedMovieIdsByUserId(any())).thenReturn(List.of());
        when(episodeRepository.findById(episodeId)).thenReturn(Optional.of(ep));

        Set<PreTranscodeTarget> targets = subject.collectMediaFilesToPreTranscode("disk1").targets();

        assertEquals(1, targets.size());
        PreTranscodeTarget target = targets.iterator().next();
        assertEquals(mediaFileId, target.mediaFileId());
        assertEquals(Set.of("nl", "en"), target.audioLanguages(), "both users must get their language");
        assertEquals(720, target.maxVideoHeight(), "the highest cap of the interested users wins");
    }

    @Test
    void oneUserWithoutAQualityCapLiftsItForTheFile() {
        UUID cappedUser = UUID.randomUUID();
        UUID uncappedUser = UUID.randomUUID();
        UUID episodeId = UUID.randomUUID();
        EpisodeEntity ep = episode(episodeId, mediaFile(UUID.randomUUID(), "disk1"));

        when(userSettingsService.forUser(cappedUser)).thenReturn(
                new UserSettingsService.UserSettings(List.of("nl"), List.of(), true, true, 480));
        when(userSettingsService.forUser(uncappedUser)).thenReturn(
                new UserSettingsService.UserSettings(List.of("nl"), List.of(), true, true, null));
        when(userRepository.findAll()).thenReturn(List.of(user(cappedUser), user(uncappedUser)));
        when(watchStatusRepository.findRecentEpisodesWithDateByUserId(any()))
                .thenReturn(rows(episodeId, UUID.randomUUID(), daysAgo(30)));
        when(watchStatusRepository.findRecentUnwatchedMovieIdsByUserId(any())).thenReturn(List.of());
        when(episodeRepository.findById(episodeId)).thenReturn(Optional.of(ep));

        PreTranscodeTarget target = subject.collectMediaFilesToPreTranscode("disk1").targets().iterator().next();

        assertNull(target.maxVideoHeight());
    }

    @Test
    void showEpisodesAreCachedAcrossUsers() {
        UUID userId1 = UUID.randomUUID();
        UUID userId2 = UUID.randomUUID();
        UUID showId = UUID.randomUUID();
        UUID ep1Id = UUID.randomUUID();
        EpisodeEntity ep1 = episode(ep1Id, mediaFile(UUID.randomUUID(), "disk1"));
        EpisodeEntity ep2 = episode(UUID.randomUUID(), mediaFile(UUID.randomUUID(), "disk1"));

        when(userRepository.findAll()).thenReturn(List.of(user(userId1), user(userId2)));
        when(watchStatusRepository.findRecentEpisodesWithDateByUserId(userId1))
                .thenReturn(rows(ep1Id, showId, daysAgo(1)));
        when(watchStatusRepository.findRecentEpisodesWithDateByUserId(userId2))
                .thenReturn(rows(ep1Id, showId, daysAgo(1)));
        when(watchStatusRepository.findRecentUnwatchedMovieIdsByUserId(any())).thenReturn(List.of());
        when(episodeRepository.findById(ep1Id)).thenReturn(Optional.of(ep1));
        when(episodeRepository.findByShowEntityId(eq(showId), any(Sort.class)))
                .thenReturn(List.of(ep1, ep2));

        subject.collectMediaFilesToPreTranscode("disk1");

        // Show episodes fetched only once despite two users watching the same show
        verify(episodeRepository, times(1)).findByShowEntityId(eq(showId), any(Sort.class));
    }

    // ===== Helpers =====

    private UserEntity user(UUID id) {
        UserEntity user = UserEntity.builder().externalId("ext").build();
        ReflectionTestUtils.setField(user, "id", id);
        return user;
    }

    private MediaFileEntity mediaFile(UUID id, String diskName) {
        MediaFileEntity mf = mediaFileWithoutStreams(id, diskName);
        ReflectionTestUtils.setField(mf, "mediaFileStreamEntity", List.of(new MediaFileStreamEntity()));
        return mf;
    }

    private MediaFileEntity mediaFileWithoutStreams(UUID id, String diskName) {
        DirectoryEntity dir = DirectoryEntity.builder()
                .name(diskName)
                .path("/" + diskName)
                .directoryType(DirectoryType.LIBRARY)
                .build();
        MediaFileEntity mf = MediaFileEntity.builder()
                .path("/test/file.mkv")
                .size(0)
                .directoryEntityId(UUID.randomUUID())
                .build();
        ReflectionTestUtils.setField(mf, "id", id);
        mf.setDirectoryEntity(dir);
        return mf;
    }

    private EpisodeEntity episode(UUID id, MediaFileEntity... files) {
        EpisodeEntity ep = EpisodeEntity.builder().number(1).build();
        ReflectionTestUtils.setField(ep, "id", id);
        ReflectionTestUtils.setField(ep, "mediaFileEntities", List.of(files));
        return ep;
    }

    private MovieEntity movie(UUID id, MediaFileEntity... files) {
        MovieEntity movie = MovieEntity.builder().name("Test Movie").releaseYear(2024).build();
        ReflectionTestUtils.setField(movie, "id", id);
        ReflectionTestUtils.setField(movie, "mediaFileEntities", List.of(files));
        return movie;
    }

    private List<Object[]> rows(UUID episodeId, UUID showId, Instant lastWatched) {
        return rows(episodeId, showId, lastWatched, false);
    }

    private List<Object[]> rows(UUID episodeId, UUID showId, Instant lastWatched, boolean watched) {
        Object[] row = new Object[]{episodeId, showId, lastWatched, watched};
        List<Object[]> list = new java.util.ArrayList<>();
        list.add(row);
        return list;
    }

    private Instant daysAgo(int days) {
        return Instant.now().minusSeconds((long) days * 86400);
    }
}
