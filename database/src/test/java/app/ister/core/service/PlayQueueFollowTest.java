package app.ister.core.service;

import app.ister.core.entity.LibraryEntity;
import app.ister.core.entity.MediaFileEntity;
import app.ister.core.entity.PlayQueueEntity;
import app.ister.core.entity.PlayQueueItemEntity;
import app.ister.core.entity.TrackEntity;
import app.ister.core.entity.UserEntity;
import app.ister.core.entity.WatchStatusEntity;
import app.ister.core.enums.FollowResult;
import app.ister.core.enums.MediaType;
import app.ister.core.enums.PlayQueueSourceType;
import app.ister.core.repository.ChapterRepository;
import app.ister.core.repository.EpisodeRepository;
import app.ister.core.repository.LibraryRepository;
import app.ister.core.repository.MovieRepository;
import app.ister.core.repository.PlayQueueControlGrantRepository;
import app.ister.core.repository.PlayQueueRepository;
import app.ister.core.repository.PodcastEpisodeRepository;
import app.ister.core.repository.TrackRepository;
import app.ister.core.repository.UserRepository;
import app.ister.core.repository.WatchStatusRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.Authentication;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Follow mode ("listen along"): access decision order of checkFollowAccess and the
 * follower watch-status writes of updatePlayQueue.
 */
@ExtendWith(MockitoExtension.class)
class PlayQueueFollowTest {

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
    private ChapterRepository chapterRepository;
    @Mock
    private PodcastEpisodeRepository podcastEpisodeRepository;
    @Mock
    private LibraryRepository libraryRepository;
    @Mock
    private UserService userService;
    @Mock
    private UserRepository userRepository;
    @Mock
    private WatchStatusRepository watchStatusRepository;
    @Mock
    private WatchStatusService watchStatusService;
    @Mock
    private ContinueWatchingService continueWatchingService;
    @Mock
    private PodcastPreferenceService podcastPreferenceService;
    @Mock
    private LibraryAccessService libraryAccessService;
    @Mock
    private MediaLibraryResolver mediaLibraryResolver;
    @Mock
    private PlaybackSharingService playbackSharingService;
    @Mock
    private PlayQueueControlGrantRepository playQueueControlGrantRepository;
    @Mock
    private FilterQueryService filterQueryService;
    @Mock
    private SavedViewService savedViewService;
    @Mock
    private Authentication authentication;

    private UserEntity owner;
    private UserEntity caller;

    @BeforeEach
    void setUp() {
        owner = UserEntity.builder().id(UUID.randomUUID()).name("owner").build();
        caller = UserEntity.builder().id(UUID.randomUUID()).name("caller").build();
    }

    private PlayQueueEntity queueOwnedBy(UserEntity user, List<PlayQueueItemEntity> items) {
        return PlayQueueEntity.builder()
                .id(UUID.randomUUID())
                .userEntity(user)
                .sourceType(PlayQueueSourceType.ALBUM)
                .sourceId(UUID.randomUUID())
                .sourceExhausted(true)
                .items(new ArrayList<>(items))
                .build();
    }

    private PlayQueueItemEntity trackItem(UUID trackId) {
        PlayQueueItemEntity item = PlayQueueItemEntity.builder()
                .type(MediaType.TRACK)
                .trackEntityId(trackId)
                .position(new BigDecimal("1000"))
                .build();
        item.setId(UUID.randomUUID());
        return item;
    }

    private TrackEntity trackWithDuration(long durationInMilliseconds) {
        return TrackEntity.builder()
                .mediaFileEntities(List.of(MediaFileEntity.builder()
                        .durationInMilliseconds(durationInMilliseconds).build()))
                .build();
    }

    // --- checkFollowAccess ---

    @Test
    void checkFollowAccessMissingQueueIsNotFound() {
        UUID id = UUID.randomUUID();
        when(playQueueRepository.findById(id)).thenReturn(Optional.empty());

        assertEquals(FollowResult.NOT_FOUND, subject.checkFollowAccess(id, authentication));
    }

    @Test
    void checkFollowAccessWithoutControlPermissionIsNotFoundAndNeverRevealsLibraryAccess() {
        PlayQueueEntity queue = queueOwnedBy(owner, List.of());
        when(playQueueRepository.findById(queue.getId())).thenReturn(Optional.of(queue));
        when(userService.getOrCreateUser(authentication)).thenReturn(caller);
        when(playbackSharingService.canControl(caller.getId(), owner.getId(), null, Set.of())).thenReturn(false);

        assertEquals(FollowResult.NOT_FOUND, subject.checkFollowAccess(queue.getId(), authentication));
        // The denied caller's response must be indistinguishable from a missing queue: the
        // library-access check may not even run (its timing would otherwise leak existence).
        verify(mediaLibraryResolver, never()).ofSource(any(), any());
    }

    @Test
    void checkFollowAccessWithControlButNoLibraryAccessIsDistinguished() {
        PlayQueueEntity queue = queueOwnedBy(owner, List.of());
        LibraryEntity library = LibraryEntity.builder().id(UUID.randomUUID()).build();
        when(playQueueRepository.findById(queue.getId())).thenReturn(Optional.of(queue));
        when(userService.getOrCreateUser(authentication)).thenReturn(caller);
        when(playbackSharingService.canControl(caller.getId(), owner.getId(), null, Set.of())).thenReturn(true);
        when(mediaLibraryResolver.ofSource(queue.getSourceType(), queue.getSourceId())).thenReturn(Optional.of(library));
        when(libraryAccessService.canAccess(library, authentication)).thenReturn(false);

        assertEquals(FollowResult.NO_LIBRARY_ACCESS, subject.checkFollowAccess(queue.getId(), authentication));
    }

    @Test
    void checkFollowAccessOwnerIsOk() {
        PlayQueueEntity queue = queueOwnedBy(owner, List.of());
        LibraryEntity library = LibraryEntity.builder().id(UUID.randomUUID()).build();
        when(playQueueRepository.findById(queue.getId())).thenReturn(Optional.of(queue));
        when(userService.getOrCreateUser(authentication)).thenReturn(owner);
        when(playbackSharingService.canControl(owner.getId(), owner.getId(), null, Set.of())).thenReturn(true);
        when(mediaLibraryResolver.ofSource(queue.getSourceType(), queue.getSourceId())).thenReturn(Optional.of(library));
        when(libraryAccessService.canAccess(library, authentication)).thenReturn(true);

        assertEquals(FollowResult.OK, subject.checkFollowAccess(queue.getId(), authentication));
    }

    @Test
    void checkFollowAccessDeletedSourceStaysFollowable() {
        PlayQueueEntity queue = queueOwnedBy(owner, List.of());
        when(playQueueRepository.findById(queue.getId())).thenReturn(Optional.of(queue));
        when(userService.getOrCreateUser(authentication)).thenReturn(owner);
        when(playbackSharingService.canControl(owner.getId(), owner.getId(), null, Set.of())).thenReturn(true);
        when(mediaLibraryResolver.ofSource(queue.getSourceType(), queue.getSourceId())).thenReturn(Optional.empty());

        assertEquals(FollowResult.OK, subject.checkFollowAccess(queue.getId(), authentication));
    }

    // --- follower watch status ---

    @Test
    void updatePlayQueueWritesWatchStatusForOwnerAndEachFollowingUserOnce() {
        UUID trackId = UUID.randomUUID();
        PlayQueueItemEntity item = trackItem(trackId);
        PlayQueueEntity queue = queueOwnedBy(owner, List.of(item));
        TrackEntity track = trackWithDuration(200_000);
        UserEntity follower = UserEntity.builder().id(UUID.randomUUID()).name("follower").build();

        when(playQueueRepository.findById(queue.getId())).thenReturn(Optional.of(queue));
        when(userService.getOrCreateUser(authentication)).thenReturn(owner);
        when(userRepository.findById(follower.getId())).thenReturn(Optional.of(follower));
        when(trackRepository.findById(trackId)).thenReturn(Optional.of(track));
        lenient().when(watchStatusService.getOrCreateForTrack(any(UserEntity.class), any(), any()))
                .thenReturn(WatchStatusEntity.builder().build());

        // The owner's id doubles as a "follower" (their own second device): still one write.
        subject.updatePlayQueue(queue.getId(), 45_000L, item.getId(), null,
                Set.of(owner.getId(), follower.getId()), authentication);

        verify(watchStatusService).getOrCreateForTrack(owner, item.getId(), track);
        verify(watchStatusService).getOrCreateForTrack(follower, item.getId(), track);
    }

    @Test
    void updatePlayQueueSkipsUnknownFollowerIds() {
        UUID trackId = UUID.randomUUID();
        PlayQueueItemEntity item = trackItem(trackId);
        PlayQueueEntity queue = queueOwnedBy(owner, List.of(item));
        TrackEntity track = trackWithDuration(200_000);
        UUID vanished = UUID.randomUUID();

        when(playQueueRepository.findById(queue.getId())).thenReturn(Optional.of(queue));
        when(userService.getOrCreateUser(authentication)).thenReturn(owner);
        when(userRepository.findById(vanished)).thenReturn(Optional.empty());
        when(trackRepository.findById(trackId)).thenReturn(Optional.of(track));
        when(watchStatusService.getOrCreateForTrack(owner, item.getId(), track))
                .thenReturn(WatchStatusEntity.builder().build());

        subject.updatePlayQueue(queue.getId(), 45_000L, item.getId(), null, Set.of(vanished), authentication);

        // Only the owner's write happened; the vanished follower id produced none.
        verify(watchStatusService, times(1)).getOrCreateForTrack(any(UserEntity.class), any(), any());
        verify(watchStatusService).getOrCreateForTrack(owner, item.getId(), track);
    }
}
