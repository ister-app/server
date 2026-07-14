package app.ister.core.service;

import app.ister.core.entity.ContinueWatchingEntity;
import app.ister.core.entity.DirectoryEntity;
import app.ister.core.entity.EpisodeEntity;
import app.ister.core.entity.MediaFileEntity;
import app.ister.core.entity.MediaFileStreamEntity;
import app.ister.core.entity.MovieEntity;
import app.ister.core.entity.SeasonEntity;
import app.ister.core.entity.ShowEntity;
import app.ister.core.entity.UserEntity;
import app.ister.core.enums.DirectoryType;
import app.ister.core.enums.MediaType;
import app.ister.core.repository.EpisodeRepository;
import app.ister.core.repository.UserRepository;
import app.ister.core.service.PreTranscodeService.PreTranscodeTarget;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * The work list comes from the users' continue-watching entries; this test drives those entries in
 * and checks which media files come out for a given disk.
 */
@ExtendWith(MockitoExtension.class)
class PreTranscodeServiceTest {

    @InjectMocks
    private PreTranscodeService subject;

    @Mock private UserRepository userRepository;
    @Mock private EpisodeRepository episodeRepository;
    @Mock private ContinueWatchingService continueWatchingService;
    @Mock private UserSettingsService userSettingsService;

    @BeforeEach
    void setUp() {
        lenient().when(userSettingsService.forUser(any())).thenReturn(
                new UserSettingsService.UserSettings(List.of("en", "nl"), List.of("nl"), true, true, null));
        lenient().when(episodeRepository.findNextEpisodeId(any(), anyInt(), anyInt())).thenReturn(List.of());
    }

    // ===== Episodes =====

    @Test
    void episodeToContinueWithIsIncluded() {
        UUID userId = UUID.randomUUID();
        UUID mediaFileId = UUID.randomUUID();
        EpisodeEntity episode = episode(UUID.randomUUID(), 1, mediaFile(mediaFileId, "disk1"));

        givenEntries(userId, episodeEntry(userId, episode));

        Set<UUID> result = subject.collectMediaFilesToPreTranscode("disk1").mediaFileIds();

        assertTrue(result.contains(mediaFileId));
    }

    @Test
    void episodeAfterItIsKeptWarmForAutoplay() {
        UUID userId = UUID.randomUUID();
        UUID mf1Id = UUID.randomUUID();
        UUID mf2Id = UUID.randomUUID();
        EpisodeEntity ep1 = episode(UUID.randomUUID(), 1, mediaFile(mf1Id, "disk1"));
        EpisodeEntity ep2 = episode(UUID.randomUUID(), 2, mediaFile(mf2Id, "disk1"));

        givenEntries(userId, episodeEntry(userId, ep1));
        when(episodeRepository.findNextEpisodeId(ep1.getShowEntity().getId(), 1, 1))
                .thenReturn(List.of(ep2.getId()));
        when(episodeRepository.findById(ep2.getId())).thenReturn(Optional.of(ep2));

        Set<UUID> result = subject.collectMediaFilesToPreTranscode("disk1").mediaFileIds();

        assertTrue(result.contains(mf1Id), "the episode the user continues with");
        assertTrue(result.contains(mf2Id), "and the one after it");
    }

    @Test
    void noNextEpisodeWhenLastInShow() {
        UUID userId = UUID.randomUUID();
        UUID mfId = UUID.randomUUID();
        EpisodeEntity ep = episode(UUID.randomUUID(), 1, mediaFile(mfId, "disk1"));

        givenEntries(userId, episodeEntry(userId, ep));

        Set<UUID> result = subject.collectMediaFilesToPreTranscode("disk1").mediaFileIds();

        assertEquals(Set.of(mfId), result);
    }

    /** A show whose episodes the user has all seen has no target; there is nothing to keep warm. */
    @Test
    void entryWithoutATargetIsSkipped() {
        UUID userId = UUID.randomUUID();

        givenEntries(userId, entry(userId, MediaType.EPISODE));

        assertTrue(subject.collectMediaFilesToPreTranscode("disk1").mediaFileIds().isEmpty());
    }

    // ===== Movies =====

    @Test
    void movieToContinueWithIsIncluded() {
        UUID userId = UUID.randomUUID();
        UUID mediaFileId = UUID.randomUUID();
        MovieEntity movie = movie(UUID.randomUUID(), mediaFile(mediaFileId, "disk1"));

        givenEntries(userId, movieEntry(userId, movie));

        assertTrue(subject.collectMediaFilesToPreTranscode("disk1").mediaFileIds().contains(mediaFileId));
    }

    @Test
    void movieOnOtherDiskIsExcluded() {
        UUID userId = UUID.randomUUID();
        UUID mediaFileId = UUID.randomUUID();
        MovieEntity movie = movie(UUID.randomUUID(), mediaFile(mediaFileId, "disk2"));

        givenEntries(userId, movieEntry(userId, movie));

        assertFalse(subject.collectMediaFilesToPreTranscode("disk1").mediaFileIds().contains(mediaFileId));
    }

    // ===== Audio-only entries are not video work =====

    @Test
    void chapterAndPodcastEntriesAreNotPreTranscoded() {
        UUID userId = UUID.randomUUID();

        givenEntries(userId, entry(userId, MediaType.CHAPTER), entry(userId, MediaType.PODCAST_EPISODE),
                entry(userId, MediaType.BOOK));

        assertTrue(subject.collectMediaFilesToPreTranscode("disk1").mediaFileIds().isEmpty());
    }

    // ===== Unanalyzed media files =====

    @Test
    void unanalyzedMediaFileIsReportedSeparatelyAndNotQueued() {
        UUID userId = UUID.randomUUID();
        UUID mediaFileId = UUID.randomUUID();
        MediaFileEntity mf = mediaFileWithoutStreams(mediaFileId, "disk1");
        EpisodeEntity episode = episode(UUID.randomUUID(), 1, mf);

        givenEntries(userId, episodeEntry(userId, episode));

        PreTranscodeService.PreTranscodeCollection result = subject.collectMediaFilesToPreTranscode("disk1");

        assertFalse(result.mediaFileIds().contains(mediaFileId), "unanalyzed file should not be queued for transcode");
        assertEquals(1, result.unanalyzedFiles().size());
        PreTranscodeService.UnanalyzedMediaFile unanalyzed = result.unanalyzedFiles().iterator().next();
        assertEquals(mediaFileId, unanalyzed.mediaFileId());
        assertEquals(episode.getId(), unanalyzed.episodeId());
        assertEquals(mf.getPath(), unanalyzed.path());
        assertNull(unanalyzed.movieId());
    }

    // ===== Filtering & deduplication =====

    @Test
    void mediaFilesOnOtherDiskAreExcluded() {
        UUID userId = UUID.randomUUID();
        UUID mfOnDisk1 = UUID.randomUUID();
        UUID mfOnDisk2 = UUID.randomUUID();
        EpisodeEntity ep = episode(UUID.randomUUID(), 1, mediaFile(mfOnDisk1, "disk1"), mediaFile(mfOnDisk2, "disk2"));

        givenEntries(userId, episodeEntry(userId, ep));

        Set<UUID> result = subject.collectMediaFilesToPreTranscode("disk1").mediaFileIds();

        assertTrue(result.contains(mfOnDisk1));
        assertFalse(result.contains(mfOnDisk2));
    }

    @Test
    void sameEpisodeContinuedByTwoUsersIsDeduplicatedInResult() {
        UUID userId1 = UUID.randomUUID();
        UUID userId2 = UUID.randomUUID();
        UUID mediaFileId = UUID.randomUUID();
        EpisodeEntity ep = episode(UUID.randomUUID(), 1, mediaFile(mediaFileId, "disk1"));

        when(userRepository.findAll()).thenReturn(List.of(user(userId1), user(userId2)));
        when(continueWatchingService.entriesFor(userId1)).thenReturn(List.of(episodeEntry(userId1, ep)));
        when(continueWatchingService.entriesFor(userId2)).thenReturn(List.of(episodeEntry(userId2, ep)));

        Set<UUID> result = subject.collectMediaFilesToPreTranscode("disk1").mediaFileIds();

        assertEquals(Set.of(mediaFileId), result);
    }

    // ===== User settings drive what gets transcoded =====

    @Test
    void audioLanguagesOfEveryInterestedUserAreMerged() {
        UUID userId1 = UUID.randomUUID();
        UUID userId2 = UUID.randomUUID();
        UUID mediaFileId = UUID.randomUUID();
        EpisodeEntity ep = episode(UUID.randomUUID(), 1, mediaFile(mediaFileId, "disk1"));

        when(userSettingsService.forUser(userId1)).thenReturn(
                new UserSettingsService.UserSettings(List.of("nl"), List.of("nl"), true, true, 480));
        when(userSettingsService.forUser(userId2)).thenReturn(
                new UserSettingsService.UserSettings(List.of("en"), List.of("en"), true, true, 720));
        when(userRepository.findAll()).thenReturn(List.of(user(userId1), user(userId2)));
        when(continueWatchingService.entriesFor(userId1)).thenReturn(List.of(episodeEntry(userId1, ep)));
        when(continueWatchingService.entriesFor(userId2)).thenReturn(List.of(episodeEntry(userId2, ep)));

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
        EpisodeEntity ep = episode(UUID.randomUUID(), 1, mediaFile(UUID.randomUUID(), "disk1"));

        when(userSettingsService.forUser(cappedUser)).thenReturn(
                new UserSettingsService.UserSettings(List.of("nl"), List.of(), true, true, 480));
        when(userSettingsService.forUser(uncappedUser)).thenReturn(
                new UserSettingsService.UserSettings(List.of("nl"), List.of(), true, true, null));
        when(userRepository.findAll()).thenReturn(List.of(user(cappedUser), user(uncappedUser)));
        when(continueWatchingService.entriesFor(cappedUser)).thenReturn(List.of(episodeEntry(cappedUser, ep)));
        when(continueWatchingService.entriesFor(uncappedUser)).thenReturn(List.of(episodeEntry(uncappedUser, ep)));

        PreTranscodeTarget target = subject.collectMediaFilesToPreTranscode("disk1").targets().iterator().next();

        assertNull(target.maxVideoHeight());
    }

    // ===== Helpers =====

    private void givenEntries(UUID userId, ContinueWatchingEntity... entries) {
        when(userRepository.findAll()).thenReturn(List.of(user(userId)));
        when(continueWatchingService.entriesFor(userId)).thenReturn(List.of(entries));
    }

    private ContinueWatchingEntity episodeEntry(UUID userId, EpisodeEntity episode) {
        ContinueWatchingEntity entry = entry(userId, MediaType.EPISODE);
        entry.setEpisodeEntity(episode);
        entry.setGroupId(episode.getShowEntity().getId());
        return entry;
    }

    private ContinueWatchingEntity movieEntry(UUID userId, MovieEntity movie) {
        ContinueWatchingEntity entry = entry(userId, MediaType.MOVIE);
        entry.setMovieEntity(movie);
        entry.setGroupId(movie.getId());
        return entry;
    }

    private ContinueWatchingEntity entry(UUID userId, MediaType type) {
        ContinueWatchingEntity entry = ContinueWatchingEntity.builder()
                .userEntity(user(userId))
                .entryType(type)
                .groupId(UUID.randomUUID())
                .lastWatched(Instant.now())
                .build();
        ReflectionTestUtils.setField(entry, "id", UUID.randomUUID());
        return entry;
    }

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

    private EpisodeEntity episode(UUID id, int number, MediaFileEntity... files) {
        ShowEntity show = ShowEntity.builder().name("Test Show").releaseYear(2024).build();
        ReflectionTestUtils.setField(show, "id", UUID.randomUUID());
        SeasonEntity season = SeasonEntity.builder().number(1).build();
        ReflectionTestUtils.setField(season, "id", UUID.randomUUID());

        EpisodeEntity ep = EpisodeEntity.builder().number(number).showEntity(show).seasonEntity(season).build();
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
}
