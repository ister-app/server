package app.ister.core.service;

import app.ister.core.entity.*;
import app.ister.core.enums.LibraryType;
import app.ister.core.enums.MediaType;
import app.ister.core.enums.PlayQueueSourceType;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
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
    private WatchStatusService watchStatusService;

    @Mock
    private Authentication authentication;

    private UserEntity user;

    @BeforeEach
    void setUp() {
        user = UserEntity.builder().id(UUID.randomUUID()).build();
    }

    private void mockUser() {
        when(userService.getOrCreateUser(authentication)).thenReturn(user);
    }

    /**
     * Simulates JPA assigning IDs to new items on save.
     */
    private void mockSaveAssignsItemIds() {
        when(playQueueRepository.save(any(PlayQueueEntity.class))).thenAnswer(inv -> {
            PlayQueueEntity queue = inv.getArgument(0);
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
    void getPlayQueueThrowsForOtherUsersQueue() {
        mockUser();
        PlayQueueEntity queue = PlayQueueEntity.builder()
                .id(UUID.randomUUID())
                .userEntity(UserEntity.builder().id(UUID.randomUUID()).build())
                .items(new ArrayList<>())
                .build();
        when(playQueueRepository.findById(queue.getId())).thenReturn(Optional.of(queue));

        assertThrows(AccessDeniedException.class, () -> subject.getPlayQueue(queue.getId(), authentication));
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

        PlayQueueEntity result = subject.createPlayQueue(PlayQueueSourceType.SHOW, showId, null, false, authentication);

        assertEquals(50, result.getItems().size());
        assertEquals(episodeIds.getFirst(), result.getItems().getFirst().getEpisodeEntityId());
        assertEquals(0, GAP.compareTo(result.getItems().getFirst().getPosition()));
        assertEquals(0, GAP.multiply(new BigDecimal(50)).compareTo(result.getItems().getLast().getPosition()));
        assertEquals(50, result.getSourceOffset());
        assertFalse(result.isSourceExhausted());
        assertEquals(result.getItems().getFirst().getId(), result.getCurrentItem());
    }

    @Test
    void createPlayQueueForShowMarksSourceExhaustedOnShortChunk() {
        mockUser();
        mockSaveAssignsItemIds();
        UUID showId = UUID.randomUUID();
        when(episodeRepository.findEpisodeIdsForShowOrdered(showId, 50, 0))
                .thenReturn(List.of(UUID.randomUUID(), UUID.randomUUID()));

        PlayQueueEntity result = subject.createPlayQueue(PlayQueueSourceType.SHOW, showId, null, false, authentication);

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

        PlayQueueEntity result = subject.createPlayQueue(PlayQueueSourceType.SHOW, showId, startId, false, authentication);

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

        assertThrows(IllegalArgumentException.class,
                () -> subject.createPlayQueue(PlayQueueSourceType.SHOW, showId, UUID.randomUUID(), false, authentication));
    }

    @Test
    void createPlayQueueForMovieCreatesSingleExhaustedQueue() {
        mockUser();
        mockSaveAssignsItemIds();
        UUID movieId = UUID.randomUUID();

        PlayQueueEntity result = subject.createPlayQueue(PlayQueueSourceType.MOVIE, movieId, null, false, authentication);

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

        PlayQueueEntity result = subject.createPlayQueue(PlayQueueSourceType.ALBUM, albumId, null, false, authentication);

        assertEquals(2, result.getItems().size());
        assertEquals(MediaType.TRACK, result.getItems().getFirst().getType());
        assertEquals(trackIds.getFirst(), result.getItems().getFirst().getTrackEntityId());
    }

    @Test
    void createPlayQueueForLibraryRequiresShuffle() {
        mockUser();
        UUID libraryId = UUID.randomUUID();

        assertThrows(IllegalArgumentException.class,
                () -> subject.createPlayQueue(PlayQueueSourceType.LIBRARY, libraryId, null, false, authentication));
    }

    @Test
    void createPlayQueueForShowLibraryThrows() {
        mockUser();
        UUID libraryId = UUID.randomUUID();
        when(libraryRepository.findById(libraryId))
                .thenReturn(Optional.of(LibraryEntity.builder().libraryType(LibraryType.SHOW).build()));

        assertThrows(IllegalArgumentException.class,
                () -> subject.createPlayQueue(PlayQueueSourceType.LIBRARY, libraryId, null, true, authentication));
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

        PlayQueueEntity result = subject.createPlayQueue(PlayQueueSourceType.LIBRARY, libraryId, null, true, authentication);

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

        PlayQueueEntity result = subject.createPlayQueue(PlayQueueSourceType.LIBRARY, libraryId, null, true, authentication);

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

        PlayQueueEntity result = subject.createPlayQueue(PlayQueueSourceType.SHOW, showId, startId, true, authentication);

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

    // --- updatePlayQueue ---

    @Test
    void updatePlayQueueReturnsEmptyWhenQueueNotFound() {
        UUID id = UUID.randomUUID();
        when(playQueueRepository.findById(id)).thenReturn(Optional.empty());

        Optional<PlayQueueEntity> result = subject.updatePlayQueue(id, 5000L, UUID.randomUUID(), authentication);

        assertTrue(result.isEmpty());
    }

    @Test
    void updatePlayQueueUpdatesProgressAndSaves() {
        mockUser();
        PlayQueueItemEntity item = buildItem(MediaType.EPISODE, UUID.randomUUID(), "1000");
        PlayQueueEntity queue = ownedQueue(List.of(item));
        when(playQueueRepository.findById(queue.getId())).thenReturn(Optional.of(queue));

        Optional<PlayQueueEntity> result = subject.updatePlayQueue(queue.getId(), 5000L, item.getId(), authentication);

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

        subject.updatePlayQueue(queue.getId(), 5000L, item.getId(), authentication);

        assertEquals(51, queue.getItems().size());
        assertEquals(100, queue.getSourceOffset());
        assertFalse(queue.isSourceExhausted());
        // Appended items continue after the current maximum position.
        assertEquals(0, new BigDecimal("2000").compareTo(queue.getItems().get(1).getPosition()));
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

        subject.updatePlayQueue(queue.getId(), 5000L, item.getId(), authentication);

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

        subject.updatePlayQueue(queue.getId(), 5000L, item.getId(), authentication);

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
        when(watchStatusService.getOrCreate(authentication, item.getId(), episode, null)).thenReturn(watchStatus);

        subject.updatePlayQueue(queue.getId(), 90000L, item.getId(), authentication);

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
        when(watchStatusService.getOrCreate(authentication, item.getId(), null, movie)).thenReturn(watchStatus);

        subject.updatePlayQueue(queue.getId(), 90000L, item.getId(), authentication);

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
    void movePlayQueueItemThrowsForOtherUsersQueue() {
        mockUser();
        PlayQueueEntity queue = PlayQueueEntity.builder()
                .id(UUID.randomUUID())
                .userEntity(UserEntity.builder().id(UUID.randomUUID()).build())
                .items(new ArrayList<>())
                .build();
        when(playQueueRepository.findById(queue.getId())).thenReturn(Optional.of(queue));
        UUID queueId = queue.getId();
        UUID itemId = UUID.randomUUID();

        assertThrows(AccessDeniedException.class,
                () -> subject.movePlayQueueItem(queueId, itemId, null, authentication));
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
}
