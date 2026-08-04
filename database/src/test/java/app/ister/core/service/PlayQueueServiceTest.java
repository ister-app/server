package app.ister.core.service;

import app.ister.core.entity.*;
import app.ister.core.enums.LibraryType;
import app.ister.core.enums.MediaType;
import app.ister.core.enums.PlayQueueSourceType;
import app.ister.core.enums.PlaylistType;
import app.ister.core.enums.RankKind;
import app.ister.core.enums.SortingEnum;
import app.ister.core.enums.SortingOrder;
import app.ister.core.enums.SubtitleFormat;
import app.ister.core.filter.FilterJson;
import app.ister.core.filter.FilterKind;
import app.ister.core.filter.FilterMatch;
import app.ister.core.filter.MediaFilter;
import app.ister.core.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Sort;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PlayQueueServiceTest {

    private static final UUID NIL_UUID = new UUID(0, 0);
    private static final BigDecimal GAP = new BigDecimal("1000");

    @InjectMocks
    private PlayQueueService subject;

    @Mock
    private PlayQueueRepository playQueueRepository;

    @Mock
    private EpisodeRepository episodeRepository;

    @Mock
    private MovieRepository movieRepository;

    @Mock
    private TrackRepository trackRepository;

    @Mock
    private LibraryRepository libraryRepository;

    @Mock
    private UserService userService;

    @Mock
    private WatchStatusRepository watchStatusRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private WatchStatusService watchStatusService;

    @Mock
    private PodcastEpisodeRepository podcastEpisodeRepository;

    @Mock
    private ChapterRepository chapterRepository;

    @Mock
    private PodcastPreferenceService podcastPreferenceService;

    @Mock
    private ContinueWatchingService continueWatchingService;

    /**
     * Unstubbed: ofSource defaults to Optional.empty(), which counts as accessible (the
     * source-deleted fallback), so the pre-permissions behaviour of every test is preserved.
     */
    @Mock
    private MediaLibraryResolver mediaLibraryResolver;

    @Mock
    private LibraryAccessService libraryAccessService;

    @Mock
    private PlaybackSharingService playbackSharingService;

    @Mock
    private PlayQueueControlGrantRepository playQueueControlGrantRepository;

    @Mock
    private FilterQueryService filterQueryService;

    @Mock
    private SavedViewService savedViewService;

    @Mock
    private PlaylistService playlistService;

    @Mock
    private PlaylistRepository playlistRepository;

    @Mock
    private PlaylistItemRepository playlistItemRepository;

    @Mock
    private Authentication authentication;

    private UserEntity user;

    @BeforeEach
    void setUp() {
        user = UserEntity.builder().id(UUID.randomUUID()).build();
        // Reads and edits now resolve the caller (for the remote-control gate); default the gate
        // open so the mechanics tests behave as before. Permission tests stub these explicitly.
        lenient().when(userService.getOrCreateUser(authentication)).thenReturn(user);
        lenient().when(playbackSharingService.canControl(any(), any(), any(), any())).thenReturn(true);
    }

    /**
     * Lenient: since queue reads/edits became party-mode (no ownership check), only the
     * create/update/watch-status paths still resolve the user.
     */
    private void mockUser() {
        lenient().when(userService.getOrCreateUser(authentication)).thenReturn(user);
    }

    /**
     * Simulates JPA assigning IDs to new items and auditing stamping dateCreated on save.
     */
    private void mockSaveAssignsItemIds() {
        when(playQueueRepository.save(any(PlayQueueEntity.class))).thenAnswer(inv -> {
            PlayQueueEntity queue = inv.getArgument(0);
            if (queue.getDateCreated() == null) {
                queue.setDateCreated(Instant.now());
            }
            queue.getItems().stream()
                    .filter(item -> item.getId() == null)
                    .forEach(item -> item.setId(UUID.randomUUID()));
            return queue;
        });
    }

    private PlayQueueItemEntity buildItem(MediaType type, UUID mediaId, String position) {
        PlayQueueItemEntity item = PlayQueueItemEntity.builder()
                .type(type)
                .position(new BigDecimal(position))
                .build();
        item.setId(UUID.randomUUID());
        switch (type) {
            case MOVIE -> item.setMovieEntityId(mediaId);
            case EPISODE -> item.setEpisodeEntityId(mediaId);
            case TRACK -> item.setTrackEntityId(mediaId);
            case CHAPTER -> item.setChapterEntityId(mediaId);
            case PODCAST_EPISODE -> item.setPodcastEpisodeEntityId(mediaId);
            default -> throw new IllegalArgumentException("unsupported media type in test fixture: " + type);
        }
        return item;
    }

    private PlayQueueEntity ownedQueue(List<PlayQueueItemEntity> items) {
        return PlayQueueEntity.builder()
                .id(UUID.randomUUID())
                .userEntity(user)
                .sourceExhausted(true)
                .items(new ArrayList<>(items))
                .build();
    }

    // --- getPlayQueue ---

    @Test
    void getPlayQueueReturnsFromRepository() {
        mockUser();
        PlayQueueEntity queue = ownedQueue(List.of());
        when(playQueueRepository.findById(queue.getId())).thenReturn(Optional.of(queue));

        Optional<PlayQueueEntity> result = subject.getPlayQueue(queue.getId(), authentication);

        assertTrue(result.isPresent());
        assertEquals(queue, result.get());
    }

    @Test
    void getPlayQueueReturnsOtherUsersQueueWhenControllable() {
        mockUser();
        PlayQueueEntity queue = PlayQueueEntity.builder()
                .id(UUID.randomUUID())
                .userEntity(UserEntity.builder().id(UUID.randomUUID()).build())
                .sourceExhausted(true)
                .items(new ArrayList<>())
                .build();
        when(playQueueRepository.findById(queue.getId())).thenReturn(Optional.of(queue));

        Optional<PlayQueueEntity> result = subject.getPlayQueue(queue.getId(), authentication);

        assertTrue(result.isPresent());
    }

    @Test
    void getPlayQueueHidesOtherUsersQueueWhenNotControllable() {
        mockUser();
        PlayQueueEntity queue = PlayQueueEntity.builder()
                .id(UUID.randomUUID())
                .userEntity(UserEntity.builder().id(UUID.randomUUID()).build())
                .sourceExhausted(true)
                .items(new ArrayList<>())
                .build();
        when(playQueueRepository.findById(queue.getId())).thenReturn(Optional.of(queue));
        // A caller who may not control this session gets not-found, never the queue.
        when(playbackSharingService.canControl(any(), any(), any(), any())).thenReturn(false);

        assertTrue(subject.getPlayQueue(queue.getId(), authentication).isEmpty());
    }

    @Test
    void editingAQueueYouMayNotControlIsRejectedAsNotFound() {
        mockUser();
        PlayQueueEntity queue = PlayQueueEntity.builder()
                .id(UUID.randomUUID())
                .userEntity(UserEntity.builder().id(UUID.randomUUID()).build())
                .items(new ArrayList<>())
                .build();
        when(playQueueRepository.findById(queue.getId())).thenReturn(Optional.of(queue));
        when(playbackSharingService.canControl(any(), any(), any(), any())).thenReturn(false);

        UUID queueId = queue.getId();
        UUID itemId = UUID.randomUUID();
        assertThrows(IllegalArgumentException.class,
                () -> subject.removePlayQueueItem(queueId, itemId, authentication));
    }

    @Test
    void getPlayQueueExtendsQueueNearingItsEnd() {
        mockUser();
        UUID showId = UUID.randomUUID();
        PlayQueueItemEntity item = buildItem(MediaType.EPISODE, UUID.randomUUID(), "1000");
        PlayQueueEntity queue = PlayQueueEntity.builder()
                .id(UUID.randomUUID())
                .userEntity(user)
                .sourceType(PlayQueueSourceType.SHOW)
                .sourceId(showId)
                .sourceOffset(50)
                .currentItem(item.getId())
                .items(new ArrayList<>(List.of(item)))
                .build();
        when(playQueueRepository.findById(queue.getId())).thenReturn(Optional.of(queue));
        when(episodeRepository.findEpisodeIdsForShowOrdered(showId, 50, 50)).thenReturn(List.of(UUID.randomUUID()));

        subject.getPlayQueue(queue.getId(), authentication);

        assertEquals(2, queue.getItems().size());
        assertEquals(51, queue.getSourceOffset());
        assertTrue(queue.isSourceExhausted());
        verify(playQueueRepository).save(queue);
    }

    // --- createPlayQueue ---

    @Test
    void createPlayQueueForShowMaterializesOnlyFirstChunk() {
        mockUser();
        mockSaveAssignsItemIds();
        UUID showId = UUID.randomUUID();
        List<UUID> episodeIds = IntStream.range(0, 50).mapToObj(i -> UUID.randomUUID()).toList();
        when(episodeRepository.findEpisodeIdsForShowOrdered(showId, 50, 0)).thenReturn(episodeIds);

        PlayQueueEntity result = subject.createPlayQueue(PlayQueueSourceType.SHOW, showId, null, false, null, authentication);

        assertEquals(50, result.getItems().size());
        assertEquals(episodeIds.getFirst(), result.getItems().getFirst().getEpisodeEntityId());
        assertEquals(0, GAP.compareTo(result.getItems().getFirst().getPosition()));
        assertEquals(0, GAP.multiply(new BigDecimal(50)).compareTo(result.getItems().getLast().getPosition()));
        assertEquals(50, result.getSourceOffset());
        assertFalse(result.isSourceExhausted());
        assertEquals(result.getItems().getFirst().getId(), result.getCurrentItem());
    }

    @Test
    void createPlayQueueForPodcastFollowsTheUsersEpisodeOrder() {
        mockUser();
        mockSaveAssignsItemIds();
        UUID podcastId = UUID.randomUUID();
        List<UUID> oldestFirst = List.of(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
        when(podcastPreferenceService.getEpisodeOrder(authentication, podcastId))
                .thenReturn(SortingOrder.ASCENDING);
        when(podcastEpisodeRepository.findEpisodeIdsForPodcastOrderedAsc(podcastId, 50, 0))
                .thenReturn(oldestFirst);

        PlayQueueEntity result = subject.createPlayQueue(PlayQueueSourceType.PODCAST, podcastId, null, false, null, authentication);

        assertTrue(result.isSourceAscending());
        assertEquals(oldestFirst.getFirst(), result.getItems().getFirst().getPodcastEpisodeEntityId());
        verify(podcastEpisodeRepository, never()).findEpisodeIdsForPodcastOrdered(any(), anyInt(), anyInt());
    }

    @Test
    void createPlayQueueForPodcastDefaultsToNewestFirst() {
        mockUser();
        mockSaveAssignsItemIds();
        UUID podcastId = UUID.randomUUID();
        when(podcastPreferenceService.getEpisodeOrder(authentication, podcastId))
                .thenReturn(SortingOrder.DESCENDING);
        when(podcastEpisodeRepository.findEpisodeIdsForPodcastOrdered(podcastId, 50, 0))
                .thenReturn(List.of(UUID.randomUUID()));

        PlayQueueEntity result = subject.createPlayQueue(PlayQueueSourceType.PODCAST, podcastId, null, false, null, authentication);

        assertFalse(result.isSourceAscending());
    }

    /**
     * The order is frozen on the queue at creation: a queue that is already playing must not flip
     * around when the user changes the preference, so extension chunks never re-read it.
     */
    @Test
    void extendingAPodcastQueueKeepsTheOrderItWasBuiltWith() {
        UUID podcastId = UUID.randomUUID();
        PlayQueueEntity queue = PlayQueueEntity.builder()
                .id(UUID.randomUUID())
                .userEntity(user)
                .sourceType(PlayQueueSourceType.PODCAST)
                .sourceId(podcastId)
                .sourceAscending(true)
                .sourceOffset(50)
                .items(new ArrayList<>(List.of(podcastItem(UUID.randomUUID(), GAP))))
                .build();
        queue.setCurrentItem(queue.getItems().getFirst().getId());
        when(playQueueRepository.findById(queue.getId())).thenReturn(Optional.of(queue));
        when(podcastEpisodeRepository.findEpisodeIdsForPodcastOrderedAsc(podcastId, 50, 50))
                .thenReturn(List.of(UUID.randomUUID()));

        subject.getPlayQueue(queue.getId(), authentication);

        assertEquals(2, queue.getItems().size());
        verify(podcastPreferenceService, never()).getEpisodeOrder(any(), any());
        verify(podcastEpisodeRepository, never()).findEpisodeIdsForPodcastOrdered(any(), anyInt(), anyInt());
    }

    private PlayQueueItemEntity podcastItem(UUID episodeId, BigDecimal position) {
        PlayQueueItemEntity item = PlayQueueItemEntity.builder()
                .type(MediaType.PODCAST_EPISODE)
                .podcastEpisodeEntityId(episodeId)
                .position(position)
                .build();
        item.setId(UUID.randomUUID());
        return item;
    }

    @Test
    void createPlayQueueForShowMarksSourceExhaustedOnShortChunk() {
        mockUser();
        mockSaveAssignsItemIds();
        UUID showId = UUID.randomUUID();
        when(episodeRepository.findEpisodeIdsForShowOrdered(showId, 50, 0))
                .thenReturn(List.of(UUID.randomUUID(), UUID.randomUUID()));

        PlayQueueEntity result = subject.createPlayQueue(PlayQueueSourceType.SHOW, showId, null, false, null, authentication);

        assertEquals(2, result.getItems().size());
        assertTrue(result.isSourceExhausted());
    }

    @Test
    void createPlayQueueForShowWithStartIdStartsWindowBeforeStartItem() {
        mockUser();
        mockSaveAssignsItemIds();
        UUID showId = UUID.randomUUID();
        List<UUID> allIds = IntStream.range(0, 200).mapToObj(i -> UUID.randomUUID()).toList();
        UUID startId = allIds.get(40);
        List<EpisodeRepository.IdOnly> idOnlies = allIds.stream()
                .map(id -> (EpisodeRepository.IdOnly) () -> id)
                .toList();
        when(episodeRepository.findIdsOnlyByShowEntityId(eq(showId), any(Sort.class))).thenReturn(idOnlies);
        when(episodeRepository.findEpisodeIdsForShowOrdered(showId, 50, 30)).thenReturn(allIds.subList(30, 80));

        PlayQueueEntity result = subject.createPlayQueue(PlayQueueSourceType.SHOW, showId, startId, false, null, authentication);

        assertEquals(50, result.getItems().size());
        assertEquals(80, result.getSourceOffset());
        PlayQueueItemEntity current = result.getItems().stream()
                .filter(item -> item.getId().equals(result.getCurrentItem()))
                .findFirst().orElseThrow();
        assertEquals(startId, current.getEpisodeEntityId());
    }

    @Test
    void createPlayQueueForShowWithUnknownStartIdThrows() {
        mockUser();
        UUID showId = UUID.randomUUID();
        when(episodeRepository.findIdsOnlyByShowEntityId(eq(showId), any(Sort.class))).thenReturn(List.of());

        UUID startId = UUID.randomUUID();
        assertThrows(IllegalArgumentException.class,
                () -> subject.createPlayQueue(PlayQueueSourceType.SHOW, showId, startId, false, null, authentication));
    }

    @Test
    void createPlayQueueForMovieCreatesSingleExhaustedQueue() {
        mockUser();
        mockSaveAssignsItemIds();
        UUID movieId = UUID.randomUUID();

        PlayQueueEntity result = subject.createPlayQueue(PlayQueueSourceType.MOVIE, movieId, null, false, null, authentication);

        assertEquals(1, result.getItems().size());
        assertEquals(movieId, result.getItems().getFirst().getMovieEntityId());
        assertTrue(result.isSourceExhausted());
        assertEquals(result.getItems().getFirst().getId(), result.getCurrentItem());
    }

    @Test
    void createPlayQueueForAlbumUsesTrackOrder() {
        mockUser();
        mockSaveAssignsItemIds();
        UUID albumId = UUID.randomUUID();
        List<UUID> trackIds = List.of(UUID.randomUUID(), UUID.randomUUID());
        when(trackRepository.findTrackIdsForAlbumOrdered(albumId, 50, 0)).thenReturn(trackIds);

        PlayQueueEntity result = subject.createPlayQueue(PlayQueueSourceType.ALBUM, albumId, null, false, null, authentication);

        assertEquals(2, result.getItems().size());
        assertEquals(MediaType.TRACK, result.getItems().getFirst().getType());
        assertEquals(trackIds.getFirst(), result.getItems().getFirst().getTrackEntityId());
    }

    @Test
    void createPlayQueueForLibraryRequiresShuffle() {
        mockUser();
        UUID libraryId = UUID.randomUUID();

        assertThrows(IllegalArgumentException.class,
                () -> subject.createPlayQueue(PlayQueueSourceType.LIBRARY, libraryId, null, false, null, authentication));
    }

    @Test
    void createPlayQueueForShowLibraryThrows() {
        mockUser();
        UUID libraryId = UUID.randomUUID();
        when(libraryRepository.findById(libraryId))
                .thenReturn(Optional.of(LibraryEntity.builder().libraryType(LibraryType.SHOW).build()));

        assertThrows(IllegalArgumentException.class,
                () -> subject.createPlayQueue(PlayQueueSourceType.LIBRARY, libraryId, null, true, null, authentication));
    }

    @Test
    void createPlayQueueForMusicLibraryShuffledCreatesTrackItems() {
        mockUser();
        mockSaveAssignsItemIds();
        UUID libraryId = UUID.randomUUID();
        List<UUID> trackIds = List.of(UUID.randomUUID(), UUID.randomUUID());
        when(libraryRepository.findById(libraryId))
                .thenReturn(Optional.of(LibraryEntity.builder().libraryType(LibraryType.MUSIC).build()));
        when(trackRepository.findTrackIdsForLibraryShuffled(eq(libraryId), anyString(), eq(NIL_UUID), eq(50), eq(0)))
                .thenReturn(trackIds);

        PlayQueueEntity result = subject.createPlayQueue(PlayQueueSourceType.LIBRARY, libraryId, null, true, null, authentication);

        assertTrue(result.isShuffle());
        assertNotNull(result.getShuffleSeed());
        assertEquals(2, result.getItems().size());
        assertEquals(MediaType.TRACK, result.getItems().getFirst().getType());
    }

    @Test
    void createPlayQueueForMovieLibraryShuffledCreatesMovieItems() {
        mockUser();
        mockSaveAssignsItemIds();
        UUID libraryId = UUID.randomUUID();
        when(libraryRepository.findById(libraryId))
                .thenReturn(Optional.of(LibraryEntity.builder().libraryType(LibraryType.MOVIE).build()));
        when(movieRepository.findMovieIdsForLibraryShuffled(eq(libraryId), anyString(), eq(NIL_UUID), eq(50), eq(0)))
                .thenReturn(List.of(UUID.randomUUID()));

        PlayQueueEntity result = subject.createPlayQueue(PlayQueueSourceType.LIBRARY, libraryId, null, true, null, authentication);

        assertEquals(MediaType.MOVIE, result.getItems().getFirst().getType());
    }

    @Test
    void createShuffledPlayQueueWithStartIdMaterializesStartItemFirstAndExcludesIt() {
        mockUser();
        mockSaveAssignsItemIds();
        UUID showId = UUID.randomUUID();
        UUID startId = UUID.randomUUID();
        List<UUID> chunkIds = List.of(UUID.randomUUID(), UUID.randomUUID());
        when(episodeRepository.findEpisodeIdsForShowShuffled(eq(showId), anyString(), eq(startId), eq(50), eq(0)))
                .thenReturn(chunkIds);

        PlayQueueEntity result = subject.createPlayQueue(PlayQueueSourceType.SHOW, showId, startId, true, null, authentication);

        assertEquals(3, result.getItems().size());
        assertEquals(startId, result.getItems().getFirst().getEpisodeEntityId());
        assertEquals(0, GAP.compareTo(result.getItems().getFirst().getPosition()));
        // The up-front start item does not count towards the source cursor.
        assertEquals(2, result.getSourceOffset());
        assertEquals(startId, result.getSourceStartId());
        PlayQueueItemEntity current = result.getItems().stream()
                .filter(item -> item.getId().equals(result.getCurrentItem()))
                .findFirst().orElseThrow();
        assertEquals(startId, current.getEpisodeEntityId());
    }

    // --- ARTIST (ranked artist list) queues ---

    @Test
    void createPlayQueueForArtistUsesRankedOrderFrozenAtCreation() {
        user = UserEntity.builder().id(UUID.randomUUID()).externalId("listener").build();
        mockUser();
        mockSaveAssignsItemIds();
        UUID personId = UUID.randomUUID();
        List<UUID> trackIds = List.of(UUID.randomUUID(), UUID.randomUUID());
        when(libraryAccessService.allowedLibraryIdsForUser(user)).thenReturn(Optional.empty());
        when(trackRepository.findTopPlayedTrackIdsForPerson(eq(personId), eq("listener"), any(Instant.class), eq(50), eq(0)))
                .thenReturn(trackIds);

        PlayQueueEntity result = subject.createPlayQueue(PlayQueueSourceType.ARTIST, personId, null, false, RankKind.MOST_PLAYED, authentication);

        assertEquals(2, result.getItems().size());
        assertEquals(MediaType.TRACK, result.getItems().getFirst().getType());
        assertEquals(trackIds.getFirst(), result.getItems().getFirst().getTrackEntityId());
        assertEquals(RankKind.MOST_PLAYED, result.getRankKind());
        assertTrue(result.isSourceExhausted());
        // The ranking's freeze point is the queue's own creation time.
        verify(trackRepository).findTopPlayedTrackIdsForPerson(personId, "listener", result.getDateCreated(), 50, 0);
    }

    @Test
    void createPlayQueueForArtistStartsAtStartIdWithBackWindow() {
        user = UserEntity.builder().id(UUID.randomUUID()).externalId("listener").build();
        mockUser();
        mockSaveAssignsItemIds();
        UUID personId = UUID.randomUUID();
        List<UUID> ranked = IntStream.range(0, 30).mapToObj(i -> UUID.randomUUID()).toList();
        UUID startId = ranked.get(20);
        when(libraryAccessService.allowedLibraryIdsForUser(user)).thenReturn(Optional.empty());
        when(trackRepository.findRecentlyPlayedTrackIdsForPerson(eq(personId), eq("listener"), any(Instant.class), eq(Integer.MAX_VALUE), eq(0)))
                .thenReturn(ranked);
        when(trackRepository.findRecentlyPlayedTrackIdsForPerson(eq(personId), eq("listener"), any(Instant.class), eq(50), eq(10)))
                .thenReturn(ranked.subList(10, 30));

        PlayQueueEntity result = subject.createPlayQueue(PlayQueueSourceType.ARTIST, personId, startId, false, RankKind.RECENTLY_PLAYED, authentication);

        // The materialized window starts BACK_WINDOW items before the start item.
        assertEquals(20, result.getItems().size());
        assertEquals(ranked.get(10), result.getItems().getFirst().getTrackEntityId());
        assertTrue(result.isSourceExhausted());
        PlayQueueItemEntity current = result.getItems().stream()
                .filter(item -> item.getId().equals(result.getCurrentItem()))
                .findFirst().orElseThrow();
        assertEquals(startId, current.getTrackEntityId());
    }

    @Test
    void createPlayQueueForArtistFiltersToAllowedLibraries() {
        user = UserEntity.builder().id(UUID.randomUUID()).externalId("listener").build();
        mockUser();
        mockSaveAssignsItemIds();
        UUID personId = UUID.randomUUID();
        UUID trackId = UUID.randomUUID();
        Set<UUID> allowed = Set.of(UUID.randomUUID());
        when(libraryAccessService.allowedLibraryIdsForUser(user)).thenReturn(Optional.of(allowed));
        when(trackRepository.findTopRatedTrackIdsForPersonInLibraries(personId, "listener", allowed, 50, 0))
                .thenReturn(List.of(trackId));

        PlayQueueEntity result = subject.createPlayQueue(PlayQueueSourceType.ARTIST, personId, null, false, RankKind.HIGHEST_RATED, authentication);

        assertEquals(1, result.getItems().size());
        assertEquals(trackId, result.getItems().getFirst().getTrackEntityId());
    }

    @Test
    void createPlayQueueForArtistWithoutAnyAllowedLibraryThrows() {
        mockUser();
        mockSaveAssignsItemIds();
        UUID personId = UUID.randomUUID();
        when(libraryAccessService.allowedLibraryIdsForUser(user)).thenReturn(Optional.of(Set.of()));

        assertThrows(IllegalArgumentException.class,
                () -> subject.createPlayQueue(PlayQueueSourceType.ARTIST, personId, null, false, RankKind.MOST_PLAYED, authentication));
    }

    @Test
    void createPlayQueueForArtistRejectsShuffle() {
        mockUser();
        UUID personId = UUID.randomUUID();

        assertThrows(IllegalArgumentException.class,
                () -> subject.createPlayQueue(PlayQueueSourceType.ARTIST, personId, null, true, RankKind.MOST_PLAYED, authentication));
    }

    @Test
    void createPlayQueueForArtistRequiresRankKind() {
        UUID personId = UUID.randomUUID();

        assertThrows(IllegalArgumentException.class,
                () -> subject.createPlayQueue(PlayQueueSourceType.ARTIST, personId, null, false, null, authentication));
    }

    @Test
    void createPlayQueueRejectsRankKindForOtherSources() {
        UUID albumId = UUID.randomUUID();

        assertThrows(IllegalArgumentException.class,
                () -> subject.createPlayQueue(PlayQueueSourceType.ALBUM, albumId, null, false, RankKind.MOST_PLAYED, authentication));
    }

    @Test
    void getPlayQueueExtendsArtistQueueWithTheCreationTimeRanking() {
        user = UserEntity.builder().id(UUID.randomUUID()).externalId("listener").build();
        mockUser();
        UUID personId = UUID.randomUUID();
        Instant asOf = Instant.parse("2026-01-01T00:00:00Z");
        PlayQueueItemEntity item = buildItem(MediaType.TRACK, UUID.randomUUID(), "1000");
        PlayQueueEntity queue = PlayQueueEntity.builder()
                .id(UUID.randomUUID())
                .userEntity(user)
                .sourceType(PlayQueueSourceType.ARTIST)
                .sourceId(personId)
                .rankKind(RankKind.MOST_PLAYED)
                .sourceOffset(50)
                .currentItem(item.getId())
                .items(new ArrayList<>(List.of(item)))
                .build();
        queue.setDateCreated(asOf);
        when(playQueueRepository.findById(queue.getId())).thenReturn(Optional.of(queue));
        when(libraryAccessService.allowedLibraryIdsForUser(user)).thenReturn(Optional.empty());
        // Extension pages with the frozen asOf, not the current time.
        when(trackRepository.findTopPlayedTrackIdsForPerson(personId, "listener", asOf, 50, 50))
                .thenReturn(List.of(UUID.randomUUID()));

        subject.getPlayQueue(queue.getId(), authentication);

        assertEquals(2, queue.getItems().size());
        assertEquals(51, queue.getSourceOffset());
        assertTrue(queue.isSourceExhausted());
        verify(playQueueRepository).save(queue);
    }

    // --- updatePlayQueue ---

    @Test
    void updatePlayQueueReturnsEmptyWhenQueueNotFound() {
        UUID id = UUID.randomUUID();
        when(playQueueRepository.findById(id)).thenReturn(Optional.empty());

        Optional<PlayQueueEntity> result = subject.updatePlayQueue(id, 5000L, UUID.randomUUID(), null, Set.of(), authentication);

        assertTrue(result.isEmpty());
    }

    @Test
    void updatePlayQueueThrowsForOtherUsersQueue() {
        // The heartbeat stays owner-only: it writes the caller's watch status and
        // defines the session identity, unlike the party-mode queue reads/edits.
        mockUser();
        PlayQueueItemEntity item = buildItem(MediaType.TRACK, UUID.randomUUID(), "1000");
        PlayQueueEntity queue = PlayQueueEntity.builder()
                .id(UUID.randomUUID())
                .userEntity(UserEntity.builder().id(UUID.randomUUID()).build())
                .sourceExhausted(true)
                .items(new ArrayList<>(List.of(item)))
                .build();
        when(playQueueRepository.findById(queue.getId())).thenReturn(Optional.of(queue));
        UUID queueId = queue.getId();
        UUID itemId = item.getId();

        assertThrows(AccessDeniedException.class,
                () -> subject.updatePlayQueue(queueId, 5000L, itemId, null, Set.of(), authentication));
    }

    @Test
    void updatePlayQueueUpdatesProgressAndSaves() {
        mockUser();
        PlayQueueItemEntity item = buildItem(MediaType.EPISODE, UUID.randomUUID(), "1000");
        PlayQueueEntity queue = ownedQueue(List.of(item));
        when(playQueueRepository.findById(queue.getId())).thenReturn(Optional.of(queue));

        Optional<PlayQueueEntity> result = subject.updatePlayQueue(queue.getId(), 5000L, item.getId(), null, Set.of(), authentication);

        assertTrue(result.isPresent());
        assertEquals(5000L, result.get().getProgressInMilliseconds());
        assertEquals(item.getId(), result.get().getCurrentItem());
        verify(playQueueRepository).save(queue);
        verifyNoInteractions(episodeRepository, watchStatusService);
    }

    @Test
    void updatePlayQueueExtendsQueueNearingItsEnd() {
        mockUser();
        UUID showId = UUID.randomUUID();
        PlayQueueItemEntity item = buildItem(MediaType.EPISODE, UUID.randomUUID(), "1000");
        PlayQueueEntity queue = PlayQueueEntity.builder()
                .id(UUID.randomUUID())
                .userEntity(user)
                .sourceType(PlayQueueSourceType.SHOW)
                .sourceId(showId)
                .sourceOffset(50)
                .items(new ArrayList<>(List.of(item)))
                .build();
        when(playQueueRepository.findById(queue.getId())).thenReturn(Optional.of(queue));
        List<UUID> nextChunk = IntStream.range(0, 50).mapToObj(i -> UUID.randomUUID()).toList();
        when(episodeRepository.findEpisodeIdsForShowOrdered(showId, 50, 50)).thenReturn(nextChunk);

        subject.updatePlayQueue(queue.getId(), 5000L, item.getId(), null, Set.of(), authentication);

        assertEquals(51, queue.getItems().size());
        assertEquals(100, queue.getSourceOffset());
        assertFalse(queue.isSourceExhausted());
        // Appended items continue after the current maximum position.
        assertEquals(0, new BigDecimal("2000").compareTo(queue.getItems().get(1).getPosition()));
    }

    @Test
    void trackHeartbeatBelowThresholdRecordsNoPlay() {
        mockUser();
        PlayQueueItemEntity item = buildItem(MediaType.TRACK, UUID.randomUUID(), "1000");
        PlayQueueEntity queue = ownedQueue(List.of(item));
        when(playQueueRepository.findById(queue.getId())).thenReturn(Optional.of(queue));
        when(trackRepository.findById(item.getTrackEntityId())).thenReturn(Optional.of(trackWithDuration(180_000)));

        subject.updatePlayQueue(queue.getId(), 5000L, item.getId(), null, Set.of(), authentication);

        verifyNoInteractions(watchStatusService);
    }

    @Test
    void trackHeartbeatPastThresholdRecordsPlay() {
        mockUser();
        PlayQueueItemEntity item = buildItem(MediaType.TRACK, UUID.randomUUID(), "1000");
        PlayQueueEntity queue = ownedQueue(List.of(item));
        when(playQueueRepository.findById(queue.getId())).thenReturn(Optional.of(queue));
        TrackEntity track = trackWithDuration(180_000);
        when(trackRepository.findById(item.getTrackEntityId())).thenReturn(Optional.of(track));
        WatchStatusEntity status = WatchStatusEntity.builder().build();
        when(watchStatusService.getOrCreateForTrack(user, item.getId(), track)).thenReturn(status);

        subject.updatePlayQueue(queue.getId(), 35_000L, item.getId(), null, Set.of(), authentication);

        // The same play-queue item resolves to the same watch-status row on every heartbeat,
        // so a play is never counted twice.
        verify(watchStatusService).getOrCreateForTrack(user, item.getId(), track);
        verify(watchStatusRepository).save(status);
        assertEquals(35_000L, status.getProgressInMilliseconds());
    }

    @Test
    void shortTrackCountsAfterHalfItsDuration() {
        mockUser();
        PlayQueueItemEntity item = buildItem(MediaType.TRACK, UUID.randomUUID(), "1000");
        PlayQueueEntity queue = ownedQueue(List.of(item));
        when(playQueueRepository.findById(queue.getId())).thenReturn(Optional.of(queue));
        TrackEntity track = trackWithDuration(40_000);
        when(trackRepository.findById(item.getTrackEntityId())).thenReturn(Optional.of(track));
        WatchStatusEntity status = WatchStatusEntity.builder().build();
        when(watchStatusService.getOrCreateForTrack(user, item.getId(), track)).thenReturn(status);

        subject.updatePlayQueue(queue.getId(), 25_000L, item.getId(), null, Set.of(), authentication);

        verify(watchStatusService).getOrCreateForTrack(user, item.getId(), track);
    }

    private TrackEntity trackWithDuration(long durationInMilliseconds) {
        return TrackEntity.builder()
                .mediaFileEntities(List.of(MediaFileEntity.builder()
                        .durationInMilliseconds(durationInMilliseconds).build()))
                .build();
    }

    @Test
    void updatePlayQueueDoesNotExtendExhaustedQueue() {
        mockUser();
        PlayQueueItemEntity item = buildItem(MediaType.EPISODE, UUID.randomUUID(), "1000");
        PlayQueueEntity queue = PlayQueueEntity.builder()
                .id(UUID.randomUUID())
                .userEntity(user)
                .sourceType(PlayQueueSourceType.SHOW)
                .sourceId(UUID.randomUUID())
                .sourceExhausted(true)
                .items(new ArrayList<>(List.of(item)))
                .build();
        when(playQueueRepository.findById(queue.getId())).thenReturn(Optional.of(queue));

        subject.updatePlayQueue(queue.getId(), 5000L, item.getId(), null, Set.of(), authentication);

        assertEquals(1, queue.getItems().size());
        verifyNoInteractions(episodeRepository);
    }

    @Test
    void updatePlayQueueDoesNotExtendLegacyQueueWithoutSource() {
        mockUser();
        PlayQueueItemEntity item = buildItem(MediaType.EPISODE, UUID.randomUUID(), "1000");
        PlayQueueEntity queue = PlayQueueEntity.builder()
                .id(UUID.randomUUID())
                .userEntity(user)
                .items(new ArrayList<>(List.of(item)))
                .build();
        when(playQueueRepository.findById(queue.getId())).thenReturn(Optional.of(queue));

        subject.updatePlayQueue(queue.getId(), 5000L, item.getId(), null, Set.of(), authentication);

        assertEquals(1, queue.getItems().size());
        verifyNoInteractions(episodeRepository);
    }

    @Test
    void updatePlayQueueUpdatesEpisodeWatchStatusWhenProgressOver60s() {
        mockUser();
        UUID epId = UUID.randomUUID();
        PlayQueueItemEntity item = buildItem(MediaType.EPISODE, epId, "1000");
        PlayQueueEntity queue = ownedQueue(List.of(item));

        MediaFileEntity mediaFile = MediaFileEntity.builder().durationInMilliseconds(3600000L).build();
        EpisodeEntity episode = EpisodeEntity.builder()
                .id(epId)
                .mediaFileEntities(List.of(mediaFile))
                .build();
        WatchStatusEntity watchStatus = WatchStatusEntity.builder().watched(false).build();

        when(playQueueRepository.findById(queue.getId())).thenReturn(Optional.of(queue));
        when(episodeRepository.findById(epId)).thenReturn(Optional.of(episode));
        when(watchStatusService.getOrCreate(user, item.getId(), episode, null)).thenReturn(watchStatus);

        subject.updatePlayQueue(queue.getId(), 90000L, item.getId(), null, Set.of(), authentication);

        verify(watchStatusRepository).save(watchStatus);
        assertEquals(90000L, watchStatus.getProgressInMilliseconds());
    }

    @Test
    void updatePlayQueueUpdatesMovieWatchStatusWhenProgressOver60s() {
        mockUser();
        UUID movieId = UUID.randomUUID();
        PlayQueueItemEntity item = buildItem(MediaType.MOVIE, movieId, "1000");
        PlayQueueEntity queue = ownedQueue(List.of(item));

        MediaFileEntity mediaFile = MediaFileEntity.builder().durationInMilliseconds(7200000L).build();
        MovieEntity movie = MovieEntity.builder()
                .id(movieId)
                .mediaFileEntities(List.of(mediaFile))
                .build();
        WatchStatusEntity watchStatus = WatchStatusEntity.builder().watched(false).build();

        when(playQueueRepository.findById(queue.getId())).thenReturn(Optional.of(queue));
        when(movieRepository.findById(movieId)).thenReturn(Optional.of(movie));
        when(watchStatusService.getOrCreate(user, item.getId(), null, movie)).thenReturn(watchStatus);

        subject.updatePlayQueue(queue.getId(), 90000L, item.getId(), null, Set.of(), authentication);

        verify(watchStatusRepository).save(watchStatus);
    }

    // --- movePlayQueueItem ---

    @Test
    void movePlayQueueItemPlacesItemBetweenNeighbours() {
        mockUser();
        PlayQueueItemEntity first = buildItem(MediaType.EPISODE, UUID.randomUUID(), "1000");
        PlayQueueItemEntity second = buildItem(MediaType.EPISODE, UUID.randomUUID(), "2000");
        PlayQueueItemEntity third = buildItem(MediaType.EPISODE, UUID.randomUUID(), "3000");
        PlayQueueEntity queue = ownedQueue(List.of(first, second, third));
        when(playQueueRepository.findById(queue.getId())).thenReturn(Optional.of(queue));

        subject.movePlayQueueItem(queue.getId(), third.getId(), first.getId(), authentication);

        assertEquals(0, new BigDecimal("1500").compareTo(third.getPosition()));
        assertEquals(List.of(first, third, second), queue.getItems());
        verify(playQueueRepository).save(queue);
    }

    @Test
    void movePlayQueueItemToFrontHalvesFirstPosition() {
        mockUser();
        PlayQueueItemEntity first = buildItem(MediaType.EPISODE, UUID.randomUUID(), "1000");
        PlayQueueItemEntity second = buildItem(MediaType.EPISODE, UUID.randomUUID(), "2000");
        PlayQueueEntity queue = ownedQueue(List.of(first, second));
        when(playQueueRepository.findById(queue.getId())).thenReturn(Optional.of(queue));

        subject.movePlayQueueItem(queue.getId(), second.getId(), null, authentication);

        assertEquals(0, new BigDecimal("500").compareTo(second.getPosition()));
        assertEquals(List.of(second, first), queue.getItems());
    }

    @Test
    void movePlayQueueItemToEndAddsGap() {
        mockUser();
        PlayQueueItemEntity first = buildItem(MediaType.EPISODE, UUID.randomUUID(), "1000");
        PlayQueueItemEntity second = buildItem(MediaType.EPISODE, UUID.randomUUID(), "2000");
        PlayQueueEntity queue = ownedQueue(List.of(first, second));
        when(playQueueRepository.findById(queue.getId())).thenReturn(Optional.of(queue));

        subject.movePlayQueueItem(queue.getId(), first.getId(), second.getId(), authentication);

        assertEquals(0, new BigDecimal("3000").compareTo(first.getPosition()));
        assertEquals(List.of(second, first), queue.getItems());
    }

    @Test
    void movePlayQueueItemRebalancesWhenGapIsExhausted() {
        mockUser();
        PlayQueueItemEntity first = buildItem(MediaType.EPISODE, UUID.randomUUID(), "1000.0000000000");
        PlayQueueItemEntity second = buildItem(MediaType.EPISODE, UUID.randomUUID(), "1000.0000000001");
        PlayQueueItemEntity third = buildItem(MediaType.EPISODE, UUID.randomUUID(), "3000");
        PlayQueueEntity queue = ownedQueue(List.of(first, second, third));
        when(playQueueRepository.findById(queue.getId())).thenReturn(Optional.of(queue));

        subject.movePlayQueueItem(queue.getId(), third.getId(), first.getId(), authentication);

        // Rebalance renumbered to 1000/2000/3000, then the midpoint was applied.
        assertEquals(0, new BigDecimal("1000").compareTo(first.getPosition()));
        assertEquals(0, new BigDecimal("2000").compareTo(second.getPosition()));
        assertEquals(0, new BigDecimal("1500").compareTo(third.getPosition()));
        assertEquals(List.of(first, third, second), queue.getItems());
    }

    @Test
    void movePlayQueueItemAfterItselfThrows() {
        mockUser();
        PlayQueueItemEntity first = buildItem(MediaType.EPISODE, UUID.randomUUID(), "1000");
        PlayQueueEntity queue = ownedQueue(List.of(first));
        when(playQueueRepository.findById(queue.getId())).thenReturn(Optional.of(queue));
        UUID queueId = queue.getId();
        UUID itemId = first.getId();

        assertThrows(IllegalArgumentException.class,
                () -> subject.movePlayQueueItem(queueId, itemId, itemId, authentication));
    }

    @Test
    void movePlayQueueItemAllowedOnOtherUsersQueueForPartyMode() {
        mockUser();
        PlayQueueItemEntity first = buildItem(MediaType.TRACK, UUID.randomUUID(), "1000");
        PlayQueueItemEntity second = buildItem(MediaType.TRACK, UUID.randomUUID(), "2000");
        PlayQueueEntity queue = PlayQueueEntity.builder()
                .id(UUID.randomUUID())
                .userEntity(UserEntity.builder().id(UUID.randomUUID()).build())
                .sourceExhausted(true)
                .items(new ArrayList<>(List.of(first, second)))
                .build();
        when(playQueueRepository.findById(queue.getId())).thenReturn(Optional.of(queue));

        subject.movePlayQueueItem(queue.getId(), second.getId(), null, authentication);

        assertEquals(List.of(second, first), queue.getItems());
    }

    // --- removePlayQueueItem ---

    @Test
    void removePlayQueueItemRemovesItem() {
        mockUser();
        PlayQueueItemEntity first = buildItem(MediaType.EPISODE, UUID.randomUUID(), "1000");
        PlayQueueItemEntity second = buildItem(MediaType.EPISODE, UUID.randomUUID(), "2000");
        PlayQueueEntity queue = ownedQueue(List.of(first, second));
        queue.setCurrentItem(first.getId());
        when(playQueueRepository.findById(queue.getId())).thenReturn(Optional.of(queue));

        subject.removePlayQueueItem(queue.getId(), second.getId(), authentication);

        assertEquals(List.of(first), queue.getItems());
        assertEquals(first.getId(), queue.getCurrentItem());
    }

    @Test
    void removeCurrentItemAdvancesToNextItem() {
        mockUser();
        PlayQueueItemEntity first = buildItem(MediaType.EPISODE, UUID.randomUUID(), "1000");
        PlayQueueItemEntity second = buildItem(MediaType.EPISODE, UUID.randomUUID(), "2000");
        PlayQueueEntity queue = ownedQueue(List.of(first, second));
        queue.setCurrentItem(first.getId());
        queue.setProgressInMilliseconds(120000L);
        when(playQueueRepository.findById(queue.getId())).thenReturn(Optional.of(queue));

        subject.removePlayQueueItem(queue.getId(), first.getId(), authentication);

        assertEquals(List.of(second), queue.getItems());
        assertEquals(second.getId(), queue.getCurrentItem());
        assertEquals(0, queue.getProgressInMilliseconds());
    }

    @Test
    void removeCurrentItemAtEndFallsBackToPreviousItem() {
        mockUser();
        PlayQueueItemEntity first = buildItem(MediaType.EPISODE, UUID.randomUUID(), "1000");
        PlayQueueItemEntity second = buildItem(MediaType.EPISODE, UUID.randomUUID(), "2000");
        PlayQueueEntity queue = ownedQueue(List.of(first, second));
        queue.setCurrentItem(second.getId());
        when(playQueueRepository.findById(queue.getId())).thenReturn(Optional.of(queue));

        subject.removePlayQueueItem(queue.getId(), second.getId(), authentication);

        assertEquals(first.getId(), queue.getCurrentItem());
    }

    @Test
    void removeUnknownItemThrows() {
        mockUser();
        PlayQueueEntity queue = ownedQueue(List.of(buildItem(MediaType.EPISODE, UUID.randomUUID(), "1000")));
        when(playQueueRepository.findById(queue.getId())).thenReturn(Optional.of(queue));
        UUID queueId = queue.getId();
        UUID itemId = UUID.randomUUID();

        assertThrows(IllegalArgumentException.class,
                () -> subject.removePlayQueueItem(queueId, itemId, authentication));
    }

    // --- addPlayQueueItem ---

    @Test
    void addPlayQueueItemAppendsAtEnd() {
        mockUser();
        UUID trackId = UUID.randomUUID();
        PlayQueueItemEntity first = buildItem(MediaType.EPISODE, UUID.randomUUID(), "1000");
        PlayQueueEntity queue = ownedQueue(List.of(first));
        when(playQueueRepository.findById(queue.getId())).thenReturn(Optional.of(queue));
        when(trackRepository.existsById(trackId)).thenReturn(true);

        subject.addPlayQueueItem(queue.getId(), MediaType.TRACK, trackId, null, authentication);

        assertEquals(2, queue.getItems().size());
        PlayQueueItemEntity added = queue.getItems().getLast();
        assertEquals(trackId, added.getTrackEntityId());
        assertEquals(0, new BigDecimal("2000").compareTo(added.getPosition()));
    }

    @Test
    void addPlayQueueItemInsertsAfterGivenItem() {
        mockUser();
        UUID movieId = UUID.randomUUID();
        PlayQueueItemEntity first = buildItem(MediaType.EPISODE, UUID.randomUUID(), "1000");
        PlayQueueItemEntity second = buildItem(MediaType.EPISODE, UUID.randomUUID(), "2000");
        PlayQueueEntity queue = ownedQueue(List.of(first, second));
        when(playQueueRepository.findById(queue.getId())).thenReturn(Optional.of(queue));
        when(movieRepository.existsById(movieId)).thenReturn(true);

        subject.addPlayQueueItem(queue.getId(), MediaType.MOVIE, movieId, first.getId(), authentication);

        assertEquals(3, queue.getItems().size());
        PlayQueueItemEntity added = queue.getItems().get(1);
        assertEquals(movieId, added.getMovieEntityId());
        assertEquals(0, new BigDecimal("1500").compareTo(added.getPosition()));
    }

    @Test
    void addUnknownMediaThrows() {
        mockUser();
        PlayQueueEntity queue = ownedQueue(List.of());
        when(playQueueRepository.findById(queue.getId())).thenReturn(Optional.of(queue));
        when(episodeRepository.existsById(any(UUID.class))).thenReturn(false);
        UUID queueId = queue.getId();
        UUID mediaId = UUID.randomUUID();

        assertThrows(IllegalArgumentException.class,
                () -> subject.addPlayQueueItem(queueId, MediaType.EPISODE, mediaId, null, authentication));
    }

    @Test
    void addChapterItemLooksUpTheChapterRepository() {
        mockUser();
        UUID chapterId = UUID.randomUUID();
        PlayQueueEntity queue = ownedQueue(List.of());
        when(playQueueRepository.findById(queue.getId())).thenReturn(Optional.of(queue));
        when(chapterRepository.existsById(chapterId)).thenReturn(true);

        subject.addPlayQueueItem(queue.getId(), MediaType.CHAPTER, chapterId, null, authentication);

        assertEquals(chapterId, queue.getItems().getFirst().getChapterEntityId());
        assertEquals(0, GAP.compareTo(queue.getItems().getFirst().getPosition()));
    }

    @Test
    void addPodcastEpisodeItemLooksUpThePodcastEpisodeRepository() {
        mockUser();
        UUID episodeId = UUID.randomUUID();
        PlayQueueEntity queue = ownedQueue(List.of());
        when(playQueueRepository.findById(queue.getId())).thenReturn(Optional.of(queue));
        when(podcastEpisodeRepository.existsById(episodeId)).thenReturn(true);

        subject.addPlayQueueItem(queue.getId(), MediaType.PODCAST_EPISODE, episodeId, null, authentication);

        assertEquals(episodeId, queue.getItems().getFirst().getPodcastEpisodeEntityId());
    }

    @Test
    void addBookItemThrowsBecauseOnlyChaptersArePlayable() {
        mockUser();
        PlayQueueEntity queue = ownedQueue(List.of());
        when(playQueueRepository.findById(queue.getId())).thenReturn(Optional.of(queue));
        UUID queueId = queue.getId();
        UUID bookId = UUID.randomUUID();

        assertThrows(IllegalArgumentException.class,
                () -> subject.addPlayQueueItem(queueId, MediaType.BOOK, bookId, null, authentication));
    }

    @Test
    void addPlayQueueItemRebalancesWhenGapIsExhausted() {
        mockUser();
        UUID trackId = UUID.randomUUID();
        PlayQueueItemEntity first = buildItem(MediaType.TRACK, UUID.randomUUID(), "1000.0000000000");
        PlayQueueItemEntity second = buildItem(MediaType.TRACK, UUID.randomUUID(), "1000.0000000001");
        PlayQueueEntity queue = ownedQueue(List.of(first, second));
        when(playQueueRepository.findById(queue.getId())).thenReturn(Optional.of(queue));
        when(trackRepository.existsById(trackId)).thenReturn(true);

        subject.addPlayQueueItem(queue.getId(), MediaType.TRACK, trackId, first.getId(), authentication);

        // Rebalance renumbered to 1000/2000, then the new item took the midpoint.
        assertEquals(0, new BigDecimal("1000").compareTo(first.getPosition()));
        assertEquals(0, new BigDecimal("2000").compareTo(second.getPosition()));
        assertEquals(0, new BigDecimal("1500").compareTo(queue.getItems().get(1).getPosition()));
        assertEquals(trackId, queue.getItems().get(1).getTrackEntityId());
    }

    @Test
    void addPlayQueueItemAfterAnUnknownItemThrows() {
        mockUser();
        UUID trackId = UUID.randomUUID();
        PlayQueueEntity queue = ownedQueue(List.of(buildItem(MediaType.TRACK, UUID.randomUUID(), "1000")));
        when(playQueueRepository.findById(queue.getId())).thenReturn(Optional.of(queue));
        when(trackRepository.existsById(trackId)).thenReturn(true);
        UUID queueId = queue.getId();
        UUID unknownItemId = UUID.randomUUID();

        assertThrows(IllegalArgumentException.class,
                () -> subject.addPlayQueueItem(queueId, MediaType.TRACK, trackId, unknownItemId, authentication));
    }

    // --- addPlayQueueAlbum ---

    @Test
    void addPlayQueueAlbumAppendsAllTracksInOrderAtTheEnd() {
        mockUser();
        UUID albumId = UUID.randomUUID();
        UUID trackA = UUID.randomUUID();
        UUID trackB = UUID.randomUUID();
        PlayQueueItemEntity first = buildItem(MediaType.TRACK, UUID.randomUUID(), "1000");
        PlayQueueEntity queue = ownedQueue(List.of(first));
        when(playQueueRepository.findById(queue.getId())).thenReturn(Optional.of(queue));
        when(trackRepository.findTrackIdsForAlbumOrdered(albumId, Integer.MAX_VALUE, 0))
                .thenReturn(List.of(trackA, trackB));

        subject.addPlayQueueAlbum(queue.getId(), albumId, authentication);

        assertEquals(3, queue.getItems().size());
        assertEquals(trackA, queue.getItems().get(1).getTrackEntityId());
        assertEquals(trackB, queue.getItems().get(2).getTrackEntityId());
        assertEquals(0, new BigDecimal("2000").compareTo(queue.getItems().get(1).getPosition()));
        assertEquals(0, new BigDecimal("3000").compareTo(queue.getItems().get(2).getPosition()));
    }

    @Test
    void addPlayQueueAlbumWithoutTracksThrows() {
        mockUser();
        UUID albumId = UUID.randomUUID();
        PlayQueueEntity queue = ownedQueue(List.of());
        when(playQueueRepository.findById(queue.getId())).thenReturn(Optional.of(queue));
        when(trackRepository.findTrackIdsForAlbumOrdered(albumId, Integer.MAX_VALUE, 0))
                .thenReturn(List.of());
        UUID queueId = queue.getId();

        assertThrows(IllegalArgumentException.class,
                () -> subject.addPlayQueueAlbum(queueId, albumId, authentication));
    }

    @Test
    void addPlayQueueAlbumFromAnInaccessibleLibraryThrows() {
        mockUser();
        UUID albumId = UUID.randomUUID();
        PlayQueueEntity queue = ownedQueue(List.of());
        when(playQueueRepository.findById(queue.getId())).thenReturn(Optional.of(queue));
        LibraryEntity library = LibraryEntity.builder().id(UUID.randomUUID()).build();
        when(mediaLibraryResolver.ofSource(PlayQueueSourceType.ALBUM, albumId)).thenReturn(Optional.of(library));
        when(libraryAccessService.canAccess(library, authentication)).thenReturn(false);
        UUID queueId = queue.getId();

        assertThrows(IllegalArgumentException.class,
                () -> subject.addPlayQueueAlbum(queueId, albumId, authentication));
    }

    // --- queue lookup ---

    @Test
    void getPlayQueueReturnsEmptyWhenNotFound() {
        UUID id = UUID.randomUUID();
        when(playQueueRepository.findById(id)).thenReturn(Optional.empty());

        assertTrue(subject.getPlayQueue(id, authentication).isEmpty());
    }

    @Test
    void editingAnUnknownQueueThrows() {
        UUID queueId = UUID.randomUUID();
        UUID itemId = UUID.randomUUID();
        when(playQueueRepository.findById(queueId)).thenReturn(Optional.empty());

        assertThrows(IllegalArgumentException.class,
                () -> subject.removePlayQueueItem(queueId, itemId, authentication));
    }

    @Test
    void movePlayQueueItemNotInTheQueueThrows() {
        mockUser();
        PlayQueueItemEntity first = buildItem(MediaType.TRACK, UUID.randomUUID(), "1000");
        PlayQueueEntity queue = ownedQueue(List.of(first));
        when(playQueueRepository.findById(queue.getId())).thenReturn(Optional.of(queue));
        UUID queueId = queue.getId();
        UUID unknownItemId = UUID.randomUUID();
        UUID afterItemId = first.getId();

        assertThrows(IllegalArgumentException.class,
                () -> subject.movePlayQueueItem(queueId, unknownItemId, afterItemId, authentication));
    }

    @Test
    void removingTheOnlyItemClearsTheCurrentItem() {
        mockUser();
        PlayQueueItemEntity only = buildItem(MediaType.TRACK, UUID.randomUUID(), "1000");
        PlayQueueEntity queue = ownedQueue(List.of(only));
        queue.setCurrentItem(only.getId());
        when(playQueueRepository.findById(queue.getId())).thenReturn(Optional.of(queue));

        subject.removePlayQueueItem(queue.getId(), only.getId(), authentication);

        assertTrue(queue.getItems().isEmpty());
        assertNull(queue.getCurrentItem());
    }

    // --- book and podcast sources ---

    @Test
    void createPlayQueueForBookUsesChapterOrder() {
        mockUser();
        mockSaveAssignsItemIds();
        UUID bookId = UUID.randomUUID();
        List<UUID> chapterIds = List.of(UUID.randomUUID(), UUID.randomUUID());
        when(chapterRepository.findChapterIdsForBookOrdered(bookId, 50, 0)).thenReturn(chapterIds);

        PlayQueueEntity result = subject.createPlayQueue(PlayQueueSourceType.BOOK, bookId, null, false, null, authentication);

        assertEquals(MediaType.CHAPTER, result.getItems().getFirst().getType());
        assertEquals(chapterIds.getFirst(), result.getItems().getFirst().getChapterEntityId());
        assertTrue(result.isSourceExhausted());
    }

    @Test
    void createPlayQueueForBookWithStartChapterStartsThere() {
        mockUser();
        mockSaveAssignsItemIds();
        UUID bookId = UUID.randomUUID();
        List<ChapterEntity> chapters = IntStream.range(0, 3)
                .mapToObj(i -> {
                    ChapterEntity chapter = ChapterEntity.builder().number(i).build();
                    chapter.setId(UUID.randomUUID());
                    return chapter;
                })
                .toList();
        UUID startId = chapters.get(2).getId();
        when(chapterRepository.findByBookEntity_Id(eq(bookId), any(Sort.class))).thenReturn(chapters);
        when(chapterRepository.findChapterIdsForBookOrdered(bookId, 50, 0))
                .thenReturn(chapters.stream().map(ChapterEntity::getId).toList());

        PlayQueueEntity result = subject.createPlayQueue(PlayQueueSourceType.BOOK, bookId, startId, false, null, authentication);

        PlayQueueItemEntity current = result.getItems().stream()
                .filter(item -> item.getId().equals(result.getCurrentItem()))
                .findFirst().orElseThrow();
        assertEquals(startId, current.getChapterEntityId());
    }

    @Test
    void createPlayQueueForBookCannotBeShuffled() {
        mockUser();
        UUID bookId = UUID.randomUUID();

        assertThrows(IllegalArgumentException.class,
                () -> subject.createPlayQueue(PlayQueueSourceType.BOOK, bookId, null, true, null, authentication));
    }

    @Test
    void createPlayQueueForPodcastCannotBeShuffled() {
        mockUser();
        UUID podcastId = UUID.randomUUID();

        assertThrows(IllegalArgumentException.class,
                () -> subject.createPlayQueue(PlayQueueSourceType.PODCAST, podcastId, null, true, null, authentication));
    }

    @Test
    void createPlayQueueForPodcastWithStartEpisodeStartsThere() {
        mockUser();
        mockSaveAssignsItemIds();
        UUID podcastId = UUID.randomUUID();
        List<UUID> newestFirst = List.of(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
        UUID startId = newestFirst.get(1);
        when(podcastPreferenceService.getEpisodeOrder(authentication, podcastId)).thenReturn(SortingOrder.DESCENDING);
        when(podcastEpisodeRepository.findEpisodeIdsForPodcastOrdered(podcastId, Integer.MAX_VALUE, 0))
                .thenReturn(newestFirst);
        when(podcastEpisodeRepository.findEpisodeIdsForPodcastOrdered(podcastId, 50, 0)).thenReturn(newestFirst);

        PlayQueueEntity result = subject.createPlayQueue(PlayQueueSourceType.PODCAST, podcastId, startId, false, null, authentication);

        PlayQueueItemEntity current = result.getItems().stream()
                .filter(item -> item.getId().equals(result.getCurrentItem()))
                .findFirst().orElseThrow();
        assertEquals(startId, current.getPodcastEpisodeEntityId());
    }

    @Test
    void createPlayQueueForAnAscendingPodcastLocatesTheStartEpisodeInTheSameOrder() {
        mockUser();
        mockSaveAssignsItemIds();
        UUID podcastId = UUID.randomUUID();
        List<UUID> oldestFirst = List.of(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
        UUID startId = oldestFirst.get(2);
        when(podcastPreferenceService.getEpisodeOrder(authentication, podcastId)).thenReturn(SortingOrder.ASCENDING);
        when(podcastEpisodeRepository.findEpisodeIdsForPodcastOrderedAsc(podcastId, Integer.MAX_VALUE, 0))
                .thenReturn(oldestFirst);
        when(podcastEpisodeRepository.findEpisodeIdsForPodcastOrderedAsc(podcastId, 50, 0)).thenReturn(oldestFirst);

        PlayQueueEntity result = subject.createPlayQueue(PlayQueueSourceType.PODCAST, podcastId, startId, false, null, authentication);

        PlayQueueItemEntity current = result.getItems().stream()
                .filter(item -> item.getId().equals(result.getCurrentItem()))
                .findFirst().orElseThrow();
        assertEquals(startId, current.getPodcastEpisodeEntityId());
    }

    @Test
    void addPlayQueueItemAfterAnItemInAnEmptyQueueStartsAtTheFirstPosition() {
        mockUser();
        UUID trackId = UUID.randomUUID();
        PlayQueueEntity queue = ownedQueue(List.of());
        when(playQueueRepository.findById(queue.getId())).thenReturn(Optional.of(queue));
        when(trackRepository.existsById(trackId)).thenReturn(true);

        subject.addPlayQueueItem(queue.getId(), MediaType.TRACK, trackId, UUID.randomUUID(), authentication);

        assertEquals(1, queue.getItems().size());
        assertEquals(0, GAP.compareTo(queue.getItems().getFirst().getPosition()));
    }

    @Test
    void createPlayQueueForAlbumWithStartTrackStartsThere() {
        mockUser();
        mockSaveAssignsItemIds();
        UUID albumId = UUID.randomUUID();
        List<TrackEntity> tracks = IntStream.range(0, 3)
                .mapToObj(i -> {
                    TrackEntity track = TrackEntity.builder().number(i).build();
                    track.setId(UUID.randomUUID());
                    return track;
                })
                .toList();
        UUID startId = tracks.get(1).getId();
        when(trackRepository.findByAlbumEntity_Id(eq(albumId), any(Sort.class))).thenReturn(tracks);
        when(trackRepository.findTrackIdsForAlbumOrdered(albumId, 50, 0))
                .thenReturn(tracks.stream().map(TrackEntity::getId).toList());

        PlayQueueEntity result = subject.createPlayQueue(PlayQueueSourceType.ALBUM, albumId, startId, false, null, authentication);

        PlayQueueItemEntity current = result.getItems().stream()
                .filter(item -> item.getId().equals(result.getCurrentItem()))
                .findFirst().orElseThrow();
        assertEquals(startId, current.getTrackEntityId());
    }

    @Test
    void createShuffledAlbumPlayQueueUsesTheShuffledTrackQuery() {
        mockUser();
        mockSaveAssignsItemIds();
        UUID albumId = UUID.randomUUID();
        when(trackRepository.findTrackIdsForAlbumShuffled(eq(albumId), anyString(), eq(NIL_UUID), eq(50), eq(0)))
                .thenReturn(List.of(UUID.randomUUID()));

        PlayQueueEntity result = subject.createPlayQueue(PlayQueueSourceType.ALBUM, albumId, null, true, null, authentication);

        assertTrue(result.isShuffle());
        assertNotNull(result.getShuffleSeed());
        assertEquals(MediaType.TRACK, result.getItems().getFirst().getType());
    }

    @Test
    void createPlayQueueForAnEmptySourceThrows() {
        mockUser();
        UUID showId = UUID.randomUUID();
        when(episodeRepository.findEpisodeIdsForShowOrdered(showId, 50, 0)).thenReturn(List.of());

        assertThrows(IllegalArgumentException.class,
                () -> subject.createPlayQueue(PlayQueueSourceType.SHOW, showId, null, false, null, authentication));
    }

    @Test
    void createPlayQueueForAnUnknownLibraryThrows() {
        mockUser();
        UUID libraryId = UUID.randomUUID();
        when(libraryRepository.findById(libraryId)).thenReturn(Optional.empty());

        assertThrows(IllegalArgumentException.class,
                () -> subject.createPlayQueue(PlayQueueSourceType.LIBRARY, libraryId, null, true, null, authentication));
    }

    @Test
    void createPlayQueueForBookLibraryThrows() {
        mockUser();
        UUID libraryId = UUID.randomUUID();
        when(libraryRepository.findById(libraryId))
                .thenReturn(Optional.of(LibraryEntity.builder().libraryType(LibraryType.BOOK).build()));

        assertThrows(IllegalArgumentException.class,
                () -> subject.createPlayQueue(PlayQueueSourceType.LIBRARY, libraryId, null, true, null, authentication));
    }

    @Test
    void createPlayQueueForPodcastLibraryThrows() {
        mockUser();
        UUID libraryId = UUID.randomUUID();
        when(libraryRepository.findById(libraryId))
                .thenReturn(Optional.of(LibraryEntity.builder().libraryType(LibraryType.PODCAST).build()));

        assertThrows(IllegalArgumentException.class,
                () -> subject.createPlayQueue(PlayQueueSourceType.LIBRARY, libraryId, null, true, null, authentication));
    }

    // --- stream settings & watch status ---

    @Test
    void updatePlayQueueStoresTheReportedStreamSettings() {
        mockUser();
        PlayQueueItemEntity item = buildItem(MediaType.EPISODE, UUID.randomUUID(), "1000");
        PlayQueueEntity queue = ownedQueue(List.of(item));
        when(playQueueRepository.findById(queue.getId())).thenReturn(Optional.of(queue));

        subject.updatePlayQueue(queue.getId(), 1000L, item.getId(),
                new PlayQueueService.StreamSettings(true, false, SubtitleFormat.WEBVTT), Set.of(), authentication);

        assertTrue(queue.getStreamDirect());
        assertFalse(queue.getStreamTranscode());
        assertEquals(SubtitleFormat.WEBVTT, queue.getStreamSubtitleFormat());
    }

    @Test
    void updatePlayQueueLeavesStreamSettingsUntouchedForNullFields() {
        mockUser();
        PlayQueueItemEntity item = buildItem(MediaType.EPISODE, UUID.randomUUID(), "1000");
        PlayQueueEntity queue = ownedQueue(List.of(item));
        queue.setStreamDirect(true);
        when(playQueueRepository.findById(queue.getId())).thenReturn(Optional.of(queue));

        subject.updatePlayQueue(queue.getId(), 1000L, item.getId(),
                new PlayQueueService.StreamSettings(null, null, null), Set.of(), authentication);

        assertTrue(queue.getStreamDirect());
        assertNull(queue.getStreamTranscode());
        assertNull(queue.getStreamSubtitleFormat());
    }

    @Test
    void updatePlayQueueIgnoresAnUnknownItem() {
        mockUser();
        PlayQueueItemEntity item = buildItem(MediaType.EPISODE, UUID.randomUUID(), "1000");
        PlayQueueEntity queue = ownedQueue(List.of(item));
        when(playQueueRepository.findById(queue.getId())).thenReturn(Optional.of(queue));

        subject.updatePlayQueue(queue.getId(), 90000L, UUID.randomUUID(), null, Set.of(), authentication);

        assertNull(queue.getCurrentItem());
        verify(playQueueRepository, never()).save(any(PlayQueueEntity.class));
        verifyNoInteractions(watchStatusService, watchStatusRepository);
    }

    /**
     * Chapters record progress from 5s on: the reader shares this position, so the first
     * minute of a chapter has to survive a switch to text.
     */
    @Test
    void updatePlayQueueRecordsChapterProgressFromFiveSeconds() {
        mockUser();
        UUID chapterId = UUID.randomUUID();
        PlayQueueItemEntity item = buildItem(MediaType.CHAPTER, chapterId, "1000");
        PlayQueueEntity queue = ownedQueue(List.of(item));
        MediaFileEntity mediaFile = MediaFileEntity.builder().durationInMilliseconds(600000L).build();
        ChapterEntity chapter = ChapterEntity.builder().mediaFileEntities(List.of(mediaFile)).build();
        chapter.setId(chapterId);
        WatchStatusEntity watchStatus = WatchStatusEntity.builder().watched(false).build();

        when(playQueueRepository.findById(queue.getId())).thenReturn(Optional.of(queue));
        when(chapterRepository.findById(chapterId)).thenReturn(Optional.of(chapter));
        when(watchStatusService.getOrCreateForChapter(user, chapter)).thenReturn(watchStatus);

        subject.updatePlayQueue(queue.getId(), 6000L, item.getId(), null, Set.of(), authentication);

        verify(watchStatusRepository).save(watchStatus);
        assertEquals(6000L, watchStatus.getProgressInMilliseconds());
        assertFalse(watchStatus.isWatched());
    }

    @Test
    void updatePlayQueueDoesNotRecordChapterProgressBelowFiveSeconds() {
        mockUser();
        PlayQueueItemEntity item = buildItem(MediaType.CHAPTER, UUID.randomUUID(), "1000");
        PlayQueueEntity queue = ownedQueue(List.of(item));
        when(playQueueRepository.findById(queue.getId())).thenReturn(Optional.of(queue));

        subject.updatePlayQueue(queue.getId(), 4000L, item.getId(), null, Set.of(), authentication);

        verifyNoInteractions(chapterRepository, watchStatusService, watchStatusRepository);
    }

    @Test
    void updatePlayQueueUpdatesPodcastEpisodeWatchStatus() {
        mockUser();
        UUID episodeId = UUID.randomUUID();
        PlayQueueItemEntity item = buildItem(MediaType.PODCAST_EPISODE, episodeId, "1000");
        PlayQueueEntity queue = ownedQueue(List.of(item));
        MediaFileEntity mediaFile = MediaFileEntity.builder().durationInMilliseconds(120000L).build();
        PodcastEpisodeEntity episode = PodcastEpisodeEntity.builder()
                .mediaFileEntities(List.of(mediaFile))
                .build();
        episode.setId(episodeId);
        WatchStatusEntity watchStatus = WatchStatusEntity.builder().watched(false).build();

        when(playQueueRepository.findById(queue.getId())).thenReturn(Optional.of(queue));
        when(podcastEpisodeRepository.findById(episodeId)).thenReturn(Optional.of(episode));
        when(watchStatusService.getOrCreateForPodcastEpisode(user, item.getId(), episode))
                .thenReturn(watchStatus);

        subject.updatePlayQueue(queue.getId(), 90000L, item.getId(), null, Set.of(), authentication);

        verify(watchStatusRepository).save(watchStatus);
        // Less than a minute left of a two-minute episode: counts as watched.
        assertTrue(watchStatus.isWatched());
    }

    @Test
    void updatePlayQueueLeavesWatchedUnchangedWithoutAMediaFile() {
        mockUser();
        UUID epId = UUID.randomUUID();
        PlayQueueItemEntity item = buildItem(MediaType.EPISODE, epId, "1000");
        PlayQueueEntity queue = ownedQueue(List.of(item));
        EpisodeEntity episode = EpisodeEntity.builder().id(epId).mediaFileEntities(List.of()).build();
        WatchStatusEntity watchStatus = WatchStatusEntity.builder().watched(false).build();

        when(playQueueRepository.findById(queue.getId())).thenReturn(Optional.of(queue));
        when(episodeRepository.findById(epId)).thenReturn(Optional.of(episode));
        when(watchStatusService.getOrCreate(user, item.getId(), episode, null)).thenReturn(watchStatus);

        subject.updatePlayQueue(queue.getId(), 90000L, item.getId(), null, Set.of(), authentication);

        assertFalse(watchStatus.isWatched());
        assertEquals(90000L, watchStatus.getProgressInMilliseconds());
        verify(watchStatusRepository).save(watchStatus);
    }

    @Test
    void updatePlayQueueSkipsWatchStatusForAnUnknownEpisode() {
        mockUser();
        UUID epId = UUID.randomUUID();
        PlayQueueItemEntity item = buildItem(MediaType.EPISODE, epId, "1000");
        PlayQueueEntity queue = ownedQueue(List.of(item));

        when(playQueueRepository.findById(queue.getId())).thenReturn(Optional.of(queue));
        when(episodeRepository.findById(epId)).thenReturn(Optional.empty());

        subject.updatePlayQueue(queue.getId(), 90000L, item.getId(), null, Set.of(), authentication);

        verifyNoInteractions(watchStatusService, watchStatusRepository);
    }

    // --- FILTER sources ---

    private static MediaFilter emptyFilter() {
        return new MediaFilter(FilterMatch.ALL, List.of(), List.of(), null);
    }

    @Test
    void createFilterPlayQueueMaterializesAChunkAndPinsTheDefinition() {
        List<UUID> trackIds = IntStream.range(0, 50).mapToObj(i -> UUID.randomUUID()).toList();
        when(libraryAccessService.allowedLibraryIdsForUser(user)).thenReturn(Optional.empty());
        when(filterQueryService.chunkIds(eq(FilterKind.TRACK), any(), any(), any(), any(),
                argThat(c -> c.limit() == 50 && c.offset() == 0))).thenReturn(trackIds);

        PlayQueueEntity result = subject.createPlayQueue(new PlayQueueService.CreatePlayQueueRequest(
                PlayQueueSourceType.FILTER, null, null, false,
                null, emptyFilter(), FilterKind.TRACK, null, null, null), authentication);

        assertEquals(50, result.getItems().size());
        assertTrue(result.getItems().stream().allMatch(i -> i.getType() == MediaType.TRACK));
        assertFalse(result.isSourceExhausted(), "a full chunk leaves the source open");
        assertEquals(50, result.getSourceOffset());
        assertNotNull(result.getSourceFilter(), "the definition is pinned onto the queue");
        assertEquals(FilterKind.TRACK, FilterJson.readPinned(result.getSourceFilter()).kind());
        verify(filterQueryService).validate(eq(FilterKind.TRACK), any());
    }

    @Test
    void createFilterPlayQueueMarksAShortChunkExhausted() {
        when(libraryAccessService.allowedLibraryIdsForUser(user)).thenReturn(Optional.empty());
        when(filterQueryService.chunkIds(any(), any(), any(), any(), any(), any()))
                .thenReturn(List.of(UUID.randomUUID(), UUID.randomUUID()));

        PlayQueueEntity result = subject.createPlayQueue(new PlayQueueService.CreatePlayQueueRequest(
                PlayQueueSourceType.FILTER, null, null, true,
                null, emptyFilter(), FilterKind.MOVIE, null, null, null), authentication);

        assertEquals(2, result.getItems().size());
        assertTrue(result.isSourceExhausted());
        assertTrue(result.isShuffle());
        assertNotNull(result.getShuffleSeed(), "a shuffled filter queue gets its seed");
    }

    @Test
    void createFilterPlayQueueWithStartIdStartsWindowBeforeStartItem() {
        mockSaveAssignsItemIds();
        List<UUID> trackIds = IntStream.range(0, 50).mapToObj(i -> UUID.randomUUID()).toList();
        UUID startId = trackIds.get(10);
        when(libraryAccessService.allowedLibraryIdsForUser(user)).thenReturn(Optional.empty());
        // Position 40 in the filter's result: the window opens BACK_WINDOW items earlier.
        when(filterQueryService.indexOf(eq(FilterKind.TRACK), any(), any(), any(), any(), any(), eq(startId)))
                .thenReturn(40);
        when(filterQueryService.chunkIds(eq(FilterKind.TRACK), any(), any(), any(), any(),
                argThat(c -> c.limit() == 50 && c.offset() == 30))).thenReturn(trackIds);

        PlayQueueEntity result = subject.createPlayQueue(new PlayQueueService.CreatePlayQueueRequest(
                PlayQueueSourceType.FILTER, null, startId, false,
                null, emptyFilter(), FilterKind.TRACK, null, null, null), authentication);

        assertEquals(50, result.getItems().size());
        assertEquals(80, result.getSourceOffset(), "the cursor sits past the materialized window");
        PlayQueueItemEntity current = result.getItems().stream()
                .filter(item -> item.getId().equals(result.getCurrentItem()))
                .findFirst().orElseThrow();
        assertEquals(startId, current.getTrackEntityId());
    }

    @Test
    void createFilterPlayQueueRejectsAStartItemOutsideTheFilter() {
        UUID startId = UUID.randomUUID();
        when(libraryAccessService.allowedLibraryIdsForUser(user)).thenReturn(Optional.empty());
        when(filterQueryService.indexOf(any(), any(), any(), any(), any(), any(), eq(startId))).thenReturn(-1);

        PlayQueueService.CreatePlayQueueRequest request = new PlayQueueService.CreatePlayQueueRequest(
                PlayQueueSourceType.FILTER, null, startId, false, null,
                emptyFilter(), FilterKind.TRACK, null, null, null);
        assertThrows(IllegalArgumentException.class, () -> subject.createPlayQueue(request, authentication));
    }

    @Test
    void createFilterPlayQueueRejectsAStartItemWithShuffle() {
        PlayQueueService.CreatePlayQueueRequest request = new PlayQueueService.CreatePlayQueueRequest(
                PlayQueueSourceType.FILTER, null, UUID.randomUUID(), true, null,
                emptyFilter(), FilterKind.TRACK, null, null, null);
        assertThrows(IllegalArgumentException.class, () -> subject.createPlayQueue(request, authentication));
    }

    @Test
    void createFilterPlayQueueRejectsUnplayableKinds() {
        PlayQueueService.CreatePlayQueueRequest request = new PlayQueueService.CreatePlayQueueRequest(
                PlayQueueSourceType.FILTER, null, null, false, null,
                emptyFilter(), FilterKind.ALBUM, null, null, null);
        assertThrows(IllegalArgumentException.class, () -> subject.createPlayQueue(request, authentication));
    }

    @Test
    void createFilterPlayQueueRejectsAViewIdCombinedWithAnInlineFilter() {
        PlayQueueService.CreatePlayQueueRequest request = new PlayQueueService.CreatePlayQueueRequest(
                PlayQueueSourceType.FILTER, UUID.randomUUID(), null, false, null,
                emptyFilter(), FilterKind.TRACK, null, null, null);
        assertThrows(IllegalArgumentException.class, () -> subject.createPlayQueue(request, authentication));
    }

    @Test
    void createFilterPlayQueueRejectsSomeoneElsesOrMissingView() {
        UUID viewId = UUID.randomUUID();
        when(savedViewService.ownedView(authentication, viewId)).thenReturn(Optional.empty());

        PlayQueueService.CreatePlayQueueRequest request = new PlayQueueService.CreatePlayQueueRequest(
                PlayQueueSourceType.FILTER, viewId, null, false, null,
                null, null, null, null, null);
        assertThrows(IllegalArgumentException.class, () -> subject.createPlayQueue(request, authentication));
    }

    @Test
    void createFilterPlayQueueFromASavedViewPinsTheViewsDefinition() {
        UUID viewId = UUID.randomUUID();
        SavedViewEntity view = SavedViewEntity.builder()
                .userEntity(user).name("Never played").kind(FilterKind.TRACK)
                .filter(FilterJson.writeFilter(emptyFilter()))
                .sorting(SortingEnum.NAME).sortingOrder(SortingOrder.ASCENDING)
                .build();
        when(savedViewService.ownedView(authentication, viewId)).thenReturn(Optional.of(view));
        when(libraryAccessService.allowedLibraryIdsForUser(user)).thenReturn(Optional.empty());
        when(filterQueryService.chunkIds(any(), any(), any(), any(), any(), any()))
                .thenReturn(List.of(UUID.randomUUID()));

        PlayQueueEntity result = subject.createPlayQueue(new PlayQueueService.CreatePlayQueueRequest(
                PlayQueueSourceType.FILTER, viewId, null, false,
                null, null, null, null, null, null), authentication);

        assertEquals(viewId, result.getSourceId());
        assertEquals(FilterKind.TRACK, FilterJson.readPinned(result.getSourceFilter()).kind());
        assertEquals(SortingEnum.NAME, FilterJson.readPinned(result.getSourceFilter()).sorting());
    }

    @Test
    void createPlayQueueRejectsFilterArgumentsOnOtherSources() {
        PlayQueueService.CreatePlayQueueRequest request = new PlayQueueService.CreatePlayQueueRequest(
                PlayQueueSourceType.SHOW, UUID.randomUUID(), null, false, null,
                emptyFilter(), null, null, null, null);
        assertThrows(IllegalArgumentException.class, () -> subject.createPlayQueue(request, authentication));
    }

    @Test
    void createPlayQueueRejectsAMissingSourceIdOnNonFilterSources() {
        assertThrows(IllegalArgumentException.class, () -> subject.createPlayQueue(
                PlayQueueSourceType.SHOW, null, null, false, null, authentication));
    }

    private PlaylistEntity buildPlaylist(app.ister.core.enums.PlaylistType type, LibraryType libraryType) {
        PlaylistEntity playlist = PlaylistEntity.builder()
                .userEntity(user)
                .libraryEntity(LibraryEntity.builder().id(UUID.randomUUID()).libraryType(libraryType).build())
                .name("Mine")
                .type(type)
                .build();
        playlist.setId(UUID.randomUUID());
        return playlist;
    }

    @Test
    void createManualPlaylistQueueMaterializesAnOrderedChunk() {
        PlaylistEntity playlist = buildPlaylist(PlaylistType.MANUAL, LibraryType.MUSIC);
        List<UUID> trackIds = IntStream.range(0, 50).mapToObj(i -> UUID.randomUUID()).toList();
        when(playlistService.ownedPlaylist(authentication, playlist.getId())).thenReturn(Optional.of(playlist));
        when(playlistRepository.findById(playlist.getId())).thenReturn(Optional.of(playlist));
        when(playlistItemRepository.findMediaIdsForPlaylistOrdered(playlist.getId(), 50, 0)).thenReturn(trackIds);

        PlayQueueEntity result = subject.createPlayQueue(
                PlayQueueSourceType.PLAYLIST, playlist.getId(), null, false, null, authentication);

        assertEquals(50, result.getItems().size());
        assertTrue(result.getItems().stream().allMatch(i -> i.getType() == MediaType.TRACK));
        assertEquals(50, result.getSourceOffset());
        assertFalse(result.isSourceExhausted(), "a full chunk leaves the source open");
        assertNull(result.getSourceFilter(), "a manual playlist pins no filter");
    }

    @Test
    void createManualPlaylistQueueShuffles() {
        PlaylistEntity playlist = buildPlaylist(PlaylistType.MANUAL, LibraryType.MUSIC);
        when(playlistService.ownedPlaylist(authentication, playlist.getId())).thenReturn(Optional.of(playlist));
        when(playlistRepository.findById(playlist.getId())).thenReturn(Optional.of(playlist));
        when(playlistItemRepository.findMediaIdsForPlaylistShuffled(eq(playlist.getId()), anyString(), eq(NIL_UUID), eq(50), eq(0)))
                .thenReturn(List.of(UUID.randomUUID(), UUID.randomUUID()));

        PlayQueueEntity result = subject.createPlayQueue(
                PlayQueueSourceType.PLAYLIST, playlist.getId(), null, true, null, authentication);

        assertEquals(2, result.getItems().size());
        assertTrue(result.isShuffle());
        assertNotNull(result.getShuffleSeed());
        assertTrue(result.isSourceExhausted());
    }

    @Test
    void createSmartPlaylistQueuePinsTheEmbeddedFilter() {
        PlaylistEntity playlist = buildPlaylist(PlaylistType.SMART, LibraryType.MUSIC);
        playlist.setFilterKind(FilterKind.TRACK);
        playlist.setFilter(FilterJson.writeFilter(emptyFilter()));
        playlist.setSorting(SortingEnum.NAME);
        when(playlistService.ownedPlaylist(authentication, playlist.getId())).thenReturn(Optional.of(playlist));
        when(libraryAccessService.allowedLibraryIdsForUser(user)).thenReturn(Optional.empty());
        when(filterQueryService.chunkIds(eq(FilterKind.TRACK), any(), any(), any(),
                argThat(scope -> playlist.getLibraryEntity().getId().equals(scope.libraryId())), any()))
                .thenReturn(List.of(UUID.randomUUID()));

        PlayQueueEntity result = subject.createPlayQueue(
                PlayQueueSourceType.PLAYLIST, playlist.getId(), null, false, null, authentication);

        assertEquals(1, result.getItems().size());
        assertEquals(MediaType.TRACK, result.getItems().getFirst().getType());
        assertNotNull(result.getSourceFilter(), "the smart playlist's definition is pinned onto the queue");
        assertEquals(FilterKind.TRACK, FilterJson.readPinned(result.getSourceFilter()).kind());
        assertEquals(playlist.getLibraryEntity().getId(), FilterJson.readPinned(result.getSourceFilter()).libraryId());
        verify(filterQueryService).validate(eq(FilterKind.TRACK), any());
    }

    @Test
    void createSmartPlaylistQueueStartsAtTheRequestedTrack() {
        mockSaveAssignsItemIds();
        PlaylistEntity playlist = buildPlaylist(PlaylistType.SMART, LibraryType.MUSIC);
        playlist.setFilterKind(FilterKind.TRACK);
        playlist.setFilter(FilterJson.writeFilter(emptyFilter()));
        List<UUID> trackIds = IntStream.range(0, 50).mapToObj(i -> UUID.randomUUID()).toList();
        UUID startId = trackIds.get(25);
        when(playlistService.ownedPlaylist(authentication, playlist.getId())).thenReturn(Optional.of(playlist));
        when(libraryAccessService.allowedLibraryIdsForUser(user)).thenReturn(Optional.empty());
        when(filterQueryService.indexOf(eq(FilterKind.TRACK), any(), any(), any(), any(), any(), eq(startId)))
                .thenReturn(500);
        when(filterQueryService.chunkIds(eq(FilterKind.TRACK), any(), any(), any(), any(),
                argThat(c -> c.offset() == 490))).thenReturn(trackIds);

        PlayQueueEntity result = subject.createPlayQueue(
                PlayQueueSourceType.PLAYLIST, playlist.getId(), startId, false, null, authentication);

        PlayQueueItemEntity current = result.getItems().stream()
                .filter(item -> item.getId().equals(result.getCurrentItem()))
                .findFirst().orElseThrow();
        assertEquals(startId, current.getTrackEntityId(),
                "a track deep in a large smart playlist is reachable without materializing the ones before it");
    }

    @Test
    void createSmartPlaylistQueueRejectsAStartItemWithShuffle() {
        PlaylistEntity playlist = buildPlaylist(PlaylistType.SMART, LibraryType.MUSIC);
        playlist.setFilterKind(FilterKind.TRACK);
        playlist.setFilter(FilterJson.writeFilter(emptyFilter()));
        when(playlistService.ownedPlaylist(authentication, playlist.getId())).thenReturn(Optional.of(playlist));

        assertThrows(IllegalArgumentException.class, () -> subject.createPlayQueue(
                PlayQueueSourceType.PLAYLIST, playlist.getId(), UUID.randomUUID(), true, null, authentication));
    }

    @Test
    void createPlaylistQueueRejectsSomeoneElsesOrMissingPlaylist() {
        UUID playlistId = UUID.randomUUID();
        when(playlistService.ownedPlaylist(authentication, playlistId)).thenReturn(Optional.empty());

        assertThrows(IllegalArgumentException.class, () -> subject.createPlayQueue(
                PlayQueueSourceType.PLAYLIST, playlistId, null, false, null, authentication));
    }

    @Test
    void createBookPlaylistQueueExpandsBooksToChaptersAndCountsBooks() {
        PlaylistEntity playlist = buildPlaylist(PlaylistType.MANUAL, LibraryType.BOOK);
        UUID book1 = UUID.randomUUID();
        UUID book2 = UUID.randomUUID();
        List<UUID> chapters1 = List.of(UUID.randomUUID(), UUID.randomUUID());
        List<UUID> chapters2 = List.of(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
        when(playlistService.ownedPlaylist(authentication, playlist.getId())).thenReturn(Optional.of(playlist));
        when(playlistRepository.findById(playlist.getId())).thenReturn(Optional.of(playlist));
        when(playlistItemRepository.findMediaIdsForPlaylistOrdered(playlist.getId(), 50, 0)).thenReturn(List.of(book1, book2));
        when(chapterRepository.findChapterIdsForBookOrdered(book1, Integer.MAX_VALUE, 0)).thenReturn(chapters1);
        when(chapterRepository.findChapterIdsForBookOrdered(book2, Integer.MAX_VALUE, 0)).thenReturn(chapters2);

        PlayQueueEntity result = subject.createPlayQueue(
                PlayQueueSourceType.PLAYLIST, playlist.getId(), null, false, null, authentication);

        assertEquals(5, result.getItems().size());
        assertTrue(result.getItems().stream().allMatch(i -> i.getType() == MediaType.CHAPTER));
        assertEquals(2, result.getSourceOffset(), "the cursor advances by books, not by appended chapters");
        assertTrue(result.isSourceExhausted());
    }

    @Test
    void createBookPlaylistQueueRejectsShuffle() {
        PlaylistEntity playlist = buildPlaylist(PlaylistType.MANUAL, LibraryType.BOOK);
        when(playlistService.ownedPlaylist(authentication, playlist.getId())).thenReturn(Optional.of(playlist));
        when(playlistRepository.findById(playlist.getId())).thenReturn(Optional.of(playlist));

        assertThrows(IllegalArgumentException.class, () -> subject.createPlayQueue(
                PlayQueueSourceType.PLAYLIST, playlist.getId(), null, true, null, authentication));
    }

    @Test
    void createBookPlaylistQueueStartsABookAtItsFirstChapter() {
        PlaylistEntity playlist = buildPlaylist(PlaylistType.MANUAL, LibraryType.BOOK);
        UUID book1 = UUID.randomUUID();
        UUID firstChapter = UUID.randomUUID();
        ChapterEntity chapter = ChapterEntity.builder().bookEntity(BookEntity.builder().id(book1).build()).build();
        chapter.setId(firstChapter);
        when(playlistService.ownedPlaylist(authentication, playlist.getId())).thenReturn(Optional.of(playlist));
        when(playlistRepository.findById(playlist.getId())).thenReturn(Optional.of(playlist));
        when(chapterRepository.findChapterIdsForBookOrdered(book1, 1, 0)).thenReturn(List.of(firstChapter));
        when(chapterRepository.findById(firstChapter)).thenReturn(Optional.of(chapter));
        when(playlistItemRepository.findMediaIdsForPlaylistOrdered(playlist.getId(), Integer.MAX_VALUE, 0))
                .thenReturn(List.of(book1));
        when(playlistItemRepository.findMediaIdsForPlaylistOrdered(playlist.getId(), 50, 0)).thenReturn(List.of(book1));
        when(chapterRepository.findChapterIdsForBookOrdered(book1, Integer.MAX_VALUE, 0)).thenReturn(List.of(firstChapter));

        PlayQueueEntity result = subject.createPlayQueue(
                PlayQueueSourceType.PLAYLIST, playlist.getId(), book1, false, null, authentication);

        assertTrue(result.getItems().stream().anyMatch(i -> firstChapter.equals(i.getChapterEntityId())),
                "the start book's first chapter is materialized and startable");
    }

    @Test
    void getPlayQueueStopsExtendingWhenTheManualPlaylistWasDeleted() {
        mockUser();
        PlayQueueItemEntity item = buildItem(MediaType.TRACK, UUID.randomUUID(), "1000");
        PlayQueueEntity queue = PlayQueueEntity.builder()
                .id(UUID.randomUUID())
                .userEntity(user)
                .sourceType(PlayQueueSourceType.PLAYLIST)
                .sourceId(UUID.randomUUID())
                .sourceOffset(1)
                .currentItem(item.getId())
                .items(new ArrayList<>(List.of(item)))
                .build();
        when(playQueueRepository.findById(queue.getId())).thenReturn(Optional.of(queue));
        when(playlistRepository.findById(queue.getSourceId())).thenReturn(Optional.empty());

        Optional<PlayQueueEntity> result = subject.getPlayQueue(queue.getId(), authentication);

        assertTrue(result.isPresent(), "the queue keeps playing its materialized items");
        assertEquals(1, queue.getItems().size());
        assertTrue(queue.isSourceExhausted());
    }
}
