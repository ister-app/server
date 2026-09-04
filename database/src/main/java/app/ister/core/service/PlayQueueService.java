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
import app.ister.core.enums.RankKind;
import app.ister.core.enums.RemoteControlScope;
import app.ister.core.enums.SortingEnum;
import app.ister.core.enums.SortingOrder;
import app.ister.core.enums.SubtitleFormat;
import app.ister.core.filter.FilterJson;
import app.ister.core.filter.FilterKind;
import app.ister.core.filter.MediaFilter;
import app.ister.core.filter.PinnedFilter;
import app.ister.core.entity.ChapterEntity;
import app.ister.core.entity.PlaylistEntity;
import app.ister.core.entity.SavedViewEntity;
import app.ister.core.enums.LibraryType;
import app.ister.core.enums.PlaylistType;
import app.ister.core.repository.ChapterRepository;
import app.ister.core.repository.PlaylistItemRepository;
import app.ister.core.repository.PlaylistRepository;
import app.ister.core.repository.PlayQueueControlGrantRepository;
import app.ister.core.repository.PodcastEpisodeRepository;
import app.ister.core.repository.EpisodeRepository;
import app.ister.core.repository.LibraryRepository;
import app.ister.core.repository.MovieRepository;
import app.ister.core.repository.PlayQueueRepository;
import app.ister.core.repository.TrackRepository;
import app.ister.core.repository.UserRepository;
import app.ister.core.repository.WatchStatusRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Sort;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Slf4j
@Service
public class PlayQueueService {
    /** Audiobook chapters record progress from here on; see updatePlayQueueItemWithProgress. */
    private static final long CHAPTER_PROGRESS_THRESHOLD_MS = 5000;
    /** How much of a track has to be heard before it counts as a play. */
    private static final long TRACK_PLAY_THRESHOLD_MS = 30000;
    private static final String FIELD_NUMBER = "number";

    private final PlayQueueRepository playQueueRepository;

    private final EpisodeRepository episodeRepository;

    private final MovieRepository movieRepository;

    private final TrackRepository trackRepository;

    private final ChapterRepository chapterRepository;

    private final PodcastEpisodeRepository podcastEpisodeRepository;

    private final LibraryRepository libraryRepository;

    private final UserService userService;

    private final UserRepository userRepository;

    private final WatchStatusRepository watchStatusRepository;

    private final WatchStatusService watchStatusService;

    private final ContinueWatchingService continueWatchingService;

    private final MediaFileEpisodeService mediaFileEpisodeService;

    private final PodcastPreferenceService podcastPreferenceService;

    /** Stream settings a client reports via updatePlayQueue; used to prefetch the next item in the same format. */
    public record StreamSettings(Boolean direct, Boolean transcode, SubtitleFormat subtitleFormat) {
    }

    private static final BigDecimal GAP = GapPositions.GAP;
    // Number of source items materialized per append.
    private static final int CHUNK_SIZE = 50;
    // Append a new chunk when fewer than this many items remain after the current item. Sized so
    // the player's fit-to-viewport "up next" tab (~20-25 rows on a tall desktop window) stays full.
    private static final int EXTEND_THRESHOLD = 25;
    // How many already-played items to keep before the start item when creating a queue mid-source.
    // Sized like EXTEND_THRESHOLD: enough to fill the player's "previous" tab on any realistic screen.
    private static final int BACK_WINDOW = 30;
    // Bound for the shuffle exclusion parameter when there is no start item; matches no row.
    private static final UUID NIL_UUID = new UUID(0, 0);

    private final LibraryAccessService libraryAccessService;

    private final MediaLibraryResolver mediaLibraryResolver;

    private final PlaybackSharingService playbackSharingService;

    private final PlayQueueControlGrantRepository playQueueControlGrantRepository;

    private final FilterQueryService filterQueryService;

    private final SavedViewService savedViewService;

    private final PlaylistService playlistService;

    private final PlaylistRepository playlistRepository;

    private final PlaylistItemRepository playlistItemRepository;

    public PlayQueueService(PlayQueueRepository playQueueRepository, EpisodeRepository episodeRepository, MovieRepository movieRepository, TrackRepository trackRepository, ChapterRepository chapterRepository, PodcastEpisodeRepository podcastEpisodeRepository, LibraryRepository libraryRepository, UserService userService, UserRepository userRepository, WatchStatusRepository watchStatusRepository, WatchStatusService watchStatusService, ContinueWatchingService continueWatchingService, PodcastPreferenceService podcastPreferenceService, LibraryAccessService libraryAccessService, MediaLibraryResolver mediaLibraryResolver, PlaybackSharingService playbackSharingService, PlayQueueControlGrantRepository playQueueControlGrantRepository, FilterQueryService filterQueryService, SavedViewService savedViewService, PlaylistService playlistService, PlaylistRepository playlistRepository, PlaylistItemRepository playlistItemRepository, MediaFileEpisodeService mediaFileEpisodeService) {
        this.playQueueRepository = playQueueRepository;
        this.episodeRepository = episodeRepository;
        this.movieRepository = movieRepository;
        this.trackRepository = trackRepository;
        this.chapterRepository = chapterRepository;
        this.podcastEpisodeRepository = podcastEpisodeRepository;
        this.libraryRepository = libraryRepository;
        this.userService = userService;
        this.userRepository = userRepository;
        this.watchStatusRepository = watchStatusRepository;
        this.watchStatusService = watchStatusService;
        this.continueWatchingService = continueWatchingService;
        this.podcastPreferenceService = podcastPreferenceService;
        this.libraryAccessService = libraryAccessService;
        this.mediaLibraryResolver = mediaLibraryResolver;
        this.playbackSharingService = playbackSharingService;
        this.playQueueControlGrantRepository = playQueueControlGrantRepository;
        this.filterQueryService = filterQueryService;
        this.savedViewService = savedViewService;
        this.playlistService = playlistService;
        this.playlistRepository = playlistRepository;
        this.playlistItemRepository = playlistItemRepository;
        this.mediaFileEpisodeService = mediaFileEpisodeService;
    }

    /**
     * Readable by the owner and by users allowed to remote-control the session ("party mode"),
     * so a permitted controller can render another user's queue. A caller without control
     * permission gets an empty Optional (not-found), never another user's queue.
     */
    @Transactional
    public Optional<PlayQueueEntity> getPlayQueue(UUID id, Authentication authentication) {
        Optional<PlayQueueEntity> playQueueEntityOptional = playQueueRepository.findById(id)
                .filter(queue -> canAccessSource(queue.getSourceType(), queue.getSourceId(), authentication))
                // Rendering a queue is only for the owner and users allowed to remote-control it;
                // a plain now-playing viewer has no business opening the remote UI. Deny reads as
                // not-found (empty Optional), consistent with the library-access model.
                .filter(queue -> canControl(queue, authentication));
        playQueueEntityOptional.ifPresent(this::maybeExtend);
        return playQueueEntityOptional;
    }

    /**
     * Whether the caller may follow (listen along with) this queue's session. Unlike
     * {@link #getPlayQueue}, control permission is checked <em>before</em> library access: the
     * distinct NO_LIBRARY_ACCESS result may only be revealed to a caller who has already proven
     * control rights (they can see the session via now-playing anyway), so a caller without them
     * gets the same NOT_FOUND a missing queue gives (deny-as-not-found).
     */
    @Transactional
    public FollowResult checkFollowAccess(UUID id, Authentication authentication) {
        Optional<PlayQueueEntity> queue = playQueueRepository.findById(id)
                .filter(candidate -> canControl(candidate, authentication));
        if (queue.isEmpty()) {
            return FollowResult.NOT_FOUND;
        }
        if (!canAccessSource(queue.get().getSourceType(), queue.get().getSourceId(), authentication)) {
            return FollowResult.NO_LIBRARY_ACCESS;
        }
        return FollowResult.OK;
    }

    /**
     * A queue whose source library the caller may not see behaves like a missing queue. A queue
     * whose source media was deleted since (resolver finds no library) stays readable — its
     * remaining items still play, matching the pre-permissions behaviour.
     */
    private boolean canAccessSource(PlayQueueSourceType sourceType, UUID sourceId, Authentication authentication) {
        if (sourceType == null || sourceId == null) {
            return true;
        }
        return mediaLibraryResolver.ofSource(sourceType, sourceId)
                .map(library -> libraryAccessService.canAccess(library, authentication))
                .orElse(true);
    }

    /**
     * Everything that defines a new play queue.
     *
     * @param startId    the episode/track to start at (ignored for MOVIE, optional otherwise;
     *                   for a filter-backed source only without shuffle)
     * @param shuffle    play the source in a stable seeded random order; required for LIBRARY sources
     * @param rankKind   which ranked track list an ARTIST source plays; required for an unshuffled
     *                   ARTIST source, forbidden with shuffle (which plays the artist's whole
     *                   catalogue) and forbidden for every other source
     * @param filter     inline filter definition for an ad-hoc FILTER source (alternative to a
     *                   saved view id in sourceId)
     * @param filterKind which browse kind an inline filter targets; required with filter
     * @param libraryId  optional library scope for an inline FILTER source
     */
    public record CreatePlayQueueRequest(PlayQueueSourceType sourceType, UUID sourceId, UUID startId,
                                         boolean shuffle, RankKind rankKind, MediaFilter filter,
                                         FilterKind filterKind, UUID libraryId, SortingEnum sorting,
                                         SortingOrder sortingOrder) {
    }

    /** Convenience overload for the non-FILTER sources. */
    @Transactional
    public PlayQueueEntity createPlayQueue(PlayQueueSourceType sourceType, UUID sourceId, UUID startId, boolean shuffle, RankKind rankKind, Authentication authentication) {
        return doCreatePlayQueue(new CreatePlayQueueRequest(sourceType, sourceId, startId, shuffle, rankKind, null, null, null, null, null), authentication);
    }

    /**
     * Creates a play queue from a source. Only an initial window of items is materialized;
     * more items are appended lazily while the user plays through the queue.
     */
    @Transactional
    public PlayQueueEntity createPlayQueue(CreatePlayQueueRequest request, Authentication authentication) {
        return doCreatePlayQueue(request, authentication);
    }

    private PlayQueueEntity doCreatePlayQueue(CreatePlayQueueRequest request, Authentication authentication) {
        log.debug("Creating play queue for user: {}, source type: {}, source: {}, shuffle: {}, rank kind: {}", authentication.getName(), request.sourceType(), request.sourceId(), request.shuffle(), request.rankKind());
        if (!canAccessSource(request.sourceType(), request.sourceId(), authentication)) {
            throw new IllegalArgumentException("Source not found");
        }
        validateCreateRequest(request);
        PinnedFilter pinned = null;
        if (request.sourceType() == PlayQueueSourceType.FILTER) {
            pinned = resolvePinnedFilter(request.sourceId(), request.filter(), request.filterKind(), request.libraryId(), request.sorting(), request.sortingOrder(), authentication);
        } else if (request.sourceType() == PlayQueueSourceType.PLAYLIST) {
            // Ownership on top of the library access check: someone else's playlist id is not-found.
            PlaylistEntity playlist = playlistService.ownedPlaylist(authentication, request.sourceId())
                    .orElseThrow(() -> new IllegalArgumentException("Playlist not found"));
            if (playlist.getType() == PlaylistType.SMART) {
                if (request.startId() != null && request.shuffle()) {
                    // Same reason FILTER queues reject it (see validateCreateRequest).
                    throw new IllegalArgumentException("A shuffled smart playlist cannot start at a specific item");
                }
                MediaFilter playlistFilter = FilterJson.readFilter(playlist.getFilter());
                filterQueryService.validate(playlist.getFilterKind(), playlistFilter);
                pinned = new PinnedFilter(playlist.getFilterKind(), playlistFilter,
                        playlist.getLibraryEntity().getId(), playlist.getSorting(), playlist.getSortingOrder());
            } else if (playlist.getLibraryEntity().getLibraryType() == LibraryType.BOOK && request.startId() != null) {
                // A book playlist stores books but the queue plays chapters: starting at a book
                // means starting at its first chapter.
                request = withStartId(request, resolveBookStart(request.startId()));
            }
        }

        PlayQueueEntity queue = PlayQueueEntity.builder()
                .userEntity(userService.getOrCreateUser(authentication))
                .sourceType(request.sourceType())
                .sourceId(request.sourceId())
                .rankKind(request.rankKind())
                .sourceFilter(pinned != null ? FilterJson.write(pinned) : null)
                .items(new ArrayList<>())
                .build();

        if (request.sourceType() == PlayQueueSourceType.MOVIE) {
            addItem(queue, buildItem(queue, MediaType.MOVIE, request.sourceId(), GAP));
            queue.setSourceExhausted(true);
        } else {
            materializeInitialWindow(queue, request, pinned, authentication);
        }

        playQueueRepository.save(queue);
        queue.setCurrentItem(findStartItem(queue, request.startId()).getId());
        playQueueRepository.save(queue);
        return queue;
    }

    private static void validateCreateRequest(CreatePlayQueueRequest request) {
        if (request.sourceType() == PlayQueueSourceType.ARTIST && request.rankKind() == null && !request.shuffle()) {
            throw new IllegalArgumentException("Artist play queues require a rankKind");
        }
        // A shuffled artist queue plays the artist's whole catalogue in a seeded random order, so a
        // ranking would only pick an order that is then thrown away.
        if (request.sourceType() == PlayQueueSourceType.ARTIST && request.rankKind() != null && request.shuffle()) {
            throw new IllegalArgumentException("Shuffled artist play queues take no rankKind; the shuffle is the order");
        }
        if (request.sourceType() != PlayQueueSourceType.ARTIST && request.rankKind() != null) {
            throw new IllegalArgumentException("rankKind only applies to artist play queues");
        }
        if (request.sourceType() != PlayQueueSourceType.FILTER
                && (request.filter() != null || request.filterKind() != null || request.libraryId() != null || request.sorting() != null || request.sortingOrder() != null)) {
            throw new IllegalArgumentException("filter, filterKind, libraryId and sorting only apply to FILTER play queues");
        }
        if (request.sourceType() != PlayQueueSourceType.FILTER && request.sourceId() == null) {
            throw new IllegalArgumentException("sourceId is required for " + request.sourceType() + " play queues");
        }
        if (request.sourceType() == PlayQueueSourceType.FILTER && request.startId() != null && request.shuffle()) {
            // An ordered filter source locates the start item with a single ranking query
            // (see filterIndexOf). A shuffled one cannot: its membership is "the first N of the
            // seeded permutation", which the pinned sort says nothing about.
            throw new IllegalArgumentException("A shuffled filter play queue cannot start at a specific item");
        }
    }

    /** Materializes the initial window of a non-MOVIE queue and positions its source cursor. */
    private void materializeInitialWindow(PlayQueueEntity queue, CreatePlayQueueRequest request, PinnedFilter pinned, Authentication authentication) {
        UUID startId = request.startId();
        MediaType mediaType = mediaTypeForSource(request.sourceType(), request.sourceId(), request.shuffle(), pinned);
        queue.setShuffle(request.shuffle());
        if (request.sourceType() == PlayQueueSourceType.ARTIST || request.sourceType() == PlayQueueSourceType.FILTER
                || request.sourceType() == PlayQueueSourceType.PLAYLIST) {
            // Persist before fetching the first chunk: dateCreated is the ranking's/filter's
            // freeze point, and the chunk queries (and orderedIndexOf below) need it set.
            playQueueRepository.save(queue);
        }
        if (request.sourceType() == PlayQueueSourceType.PODCAST) {
            // Freeze the user's preferred order onto the queue. The queue materializes its
            // items in chunks as playback goes on, and re-reading the preference per chunk
            // would flip a running queue around the moment the user changes the setting.
            queue.setSourceAscending(podcastPreferenceService.getEpisodeOrder(authentication, request.sourceId())
                    == SortingOrder.ASCENDING);
        }
        if (request.shuffle()) {
            queue.setShuffleSeed(UUID.randomUUID().toString());
            if (startId != null) {
                // Materialize the start item up-front; chunk queries exclude it so the
                // seeded permutation never emits it again.
                queue.setSourceStartId(startId);
                addItem(queue, buildItem(queue, mediaType, startId, GAP));
            }
        } else if (startId != null) {
            // Start the materialized window a bit before the start item so the client
            // still has some back-scroll context. Earlier items are never materialized.
            queue.setSourceOffset(Math.max(0,
                    orderedIndexOf(queue, startId) - BACK_WINDOW));
        }
        appendChunk(queue);
    }

    /**
     * Find the PlayQueue and then update it.
     *
     * @param streamSettings  what the client is currently playing with; stored on the queue
     *                        and used to prefetch the next item in the same format (may be null)
     * @param followerUserIds users currently following (listening along with) this session; their
     *                        watch status is written alongside the owner's, since followers never
     *                        report progress themselves (may be null or empty)
     */
    @Transactional
    public Optional<PlayQueueEntity> updatePlayQueue(UUID id, long progressInMilliseconds, UUID playQueueItemId, StreamSettings streamSettings, Set<UUID> followerUserIds, Authentication authentication) {
        log.debug("Updating play queue for user: {}", authentication.getName());
        // Update the current playing episode
        Optional<PlayQueueEntity> playQueueEntityOptional = playQueueRepository.findById(id);
        playQueueEntityOptional.ifPresent(playQueueEntity -> {
            checkOwnership(playQueueEntity, authentication);
            applyStreamSettings(playQueueEntity, streamSettings);
            updatePlayQueueItemWithProgress(progressInMilliseconds, playQueueItemId, listeners(authentication, followerUserIds), playQueueEntity);
        });
        return playQueueEntityOptional;
    }

    /**
     * The reporting owner plus every currently-following user, deduplicated by user id: a user
     * listening on two devices gets exactly one watch-status write per heartbeat, and the unique
     * constraint on watch-status rows keeps it at one row per user per queue item.
     */
    private List<UserEntity> listeners(Authentication authentication, Set<UUID> followerUserIds) {
        UserEntity owner = userService.getOrCreateUser(authentication);
        Map<UUID, UserEntity> byId = new LinkedHashMap<>();
        byId.put(owner.getId(), owner);
        if (followerUserIds != null) {
            followerUserIds.stream()
                    .filter(userId -> !byId.containsKey(userId))
                    .forEach(userId -> userRepository.findById(userId).ifPresent(user -> byId.put(userId, user)));
        }
        return List.copyOf(byId.values());
    }

    private void applyStreamSettings(PlayQueueEntity queue, StreamSettings streamSettings) {
        if (streamSettings == null) {
            return;
        }
        if (streamSettings.direct() != null) {
            queue.setStreamDirect(streamSettings.direct());
        }
        if (streamSettings.transcode() != null) {
            queue.setStreamTranscode(streamSettings.transcode());
        }
        if (streamSettings.subtitleFormat() != null) {
            queue.setStreamSubtitleFormat(streamSettings.subtitleFormat());
        }
    }

    /**
     * Moves an item to directly after another item, or to the front of the queue when
     * afterItemId is null. Uses the gap-based position column: the new position is the
     * midpoint between the two neighbours; when the gap is exhausted the whole queue is
     * renumbered first.
     */
    @Transactional
    public PlayQueueEntity movePlayQueueItem(UUID playQueueId, UUID playQueueItemId, UUID afterItemId, Authentication authentication) {
        log.debug("Moving play queue item {} in queue {}", playQueueItemId, playQueueId);
        PlayQueueEntity queue = getEditableQueue(playQueueId, authentication);
        if (playQueueItemId.equals(afterItemId)) {
            throw new IllegalArgumentException("Cannot move an item after itself");
        }
        PlayQueueItemEntity moving = itemById(queue, playQueueItemId)
                .orElseThrow(() -> new IllegalArgumentException("Item not in queue"));

        BigDecimal newPosition = targetPosition(queue, afterItemId, playQueueItemId);
        if (newPosition == null) {
            rebalance(queue);
            newPosition = targetPosition(queue, afterItemId, playQueueItemId);
        }
        moving.setPosition(newPosition);
        sortItems(queue);
        playQueueRepository.save(queue);
        return queue;
    }

    /**
     * Removes an item from the queue. When the current item is removed, the next item (or
     * the previous one at the end of the queue) becomes current and progress is reset.
     */
    @Transactional
    public PlayQueueEntity removePlayQueueItem(UUID playQueueId, UUID playQueueItemId, Authentication authentication) {
        log.debug("Removing play queue item {} from queue {}", playQueueItemId, playQueueId);
        PlayQueueEntity queue = getEditableQueue(playQueueId, authentication);
        List<PlayQueueItemEntity> items = queue.getItems();
        int index = -1;
        for (int i = 0; i < items.size(); i++) {
            if (items.get(i).getId().equals(playQueueItemId)) {
                index = i;
                break;
            }
        }
        if (index == -1) {
            throw new IllegalArgumentException("Item not in queue");
        }

        if (playQueueItemId.equals(queue.getCurrentItem())) {
            PlayQueueItemEntity newCurrent;
            if (index + 1 < items.size()) {
                newCurrent = items.get(index + 1);
            } else if (index > 0) {
                newCurrent = items.get(index - 1);
            } else {
                newCurrent = null;
            }
            queue.setCurrentItem(newCurrent != null ? newCurrent.getId() : null);
            queue.setProgressInMilliseconds(0);
        }
        items.remove(index); // orphanRemoval deletes the row
        playQueueRepository.save(queue);
        maybeExtend(queue);
        return queue;
    }

    /**
     * Adds a single media item to the queue, at the end (afterItemId null) or directly
     * after another item. Manually added items are independent of the source cursor, so
     * they may show up a second time when the source later materializes the same media.
     */
    @Transactional
    public PlayQueueEntity addPlayQueueItem(UUID playQueueId, MediaType mediaType, UUID mediaId, UUID afterItemId, Authentication authentication) {
        log.debug("Adding {} {} to queue {}", mediaType, mediaId, playQueueId);
        PlayQueueEntity queue = getEditableQueue(playQueueId, authentication);
        validateMediaExists(mediaType, mediaId);

        BigDecimal position;
        if (afterItemId == null) {
            position = nextPosition(maxPosition(queue));
        } else {
            position = targetPosition(queue, afterItemId, null);
            if (position == null) {
                rebalance(queue);
                position = targetPosition(queue, afterItemId, null);
            }
        }
        addItem(queue, buildItem(queue, mediaType, mediaId, position));
        sortItems(queue);
        playQueueRepository.save(queue);
        return queue;
    }

    /**
     * Appends all tracks of an album to the end of the queue, in natural play order
     * (disc number, track number). Like addPlayQueueItem, the added items are independent
     * of the queue's source cursor.
     */
    @Transactional
    public PlayQueueEntity addPlayQueueAlbum(UUID playQueueId, UUID albumId, Authentication authentication) {
        log.debug("Adding album {} to queue {}", albumId, playQueueId);
        PlayQueueEntity queue = getEditableQueue(playQueueId, authentication);
        if (!canAccessSource(PlayQueueSourceType.ALBUM, albumId, authentication)) {
            throw new IllegalArgumentException("Album not found");
        }
        List<UUID> trackIds = trackRepository.findTrackIdsForAlbumOrdered(albumId, Integer.MAX_VALUE, 0);
        if (trackIds.isEmpty()) {
            // Also covers an unknown album id: both look the same to the track query.
            throw new IllegalArgumentException("Album not found");
        }
        BigDecimal position = maxPosition(queue);
        for (UUID trackId : trackIds) {
            position = nextPosition(position);
            addItem(queue, buildItem(queue, MediaType.TRACK, trackId, position));
        }
        sortItems(queue);
        playQueueRepository.save(queue);
        return queue;
    }

    /**
     * Returns the next position value given the previous one (or null for the first item).
     */
    private BigDecimal nextPosition(BigDecimal previous) {
        return GapPositions.nextPosition(previous);
    }

    /**
     * One fetched chunk of source media: the ids to append, how many <em>source</em> items were
     * consumed for them, and whether the source is done. The two counts differ only for a BOOK
     * playlist, where one consumed source item (a book) appends many chapter ids.
     */
    private record SourceChunk(List<UUID> mediaIds, int sourceItemsConsumed, boolean lastChunk) {
        static SourceChunk of(List<UUID> mediaIds) {
            return new SourceChunk(mediaIds, mediaIds.size(), mediaIds.size() < CHUNK_SIZE);
        }
    }

    /**
     * Appends the next chunk of source items to the queue and advances the source cursor.
     * Marks the source exhausted when the chunk reports itself as the last one.
     */
    private void appendChunk(PlayQueueEntity queue) {
        MediaType mediaType = mediaTypeForSource(queue.getSourceType(), queue.getSourceId(), queue.isShuffle(), pinnedFilterOf(queue));
        if (mediaType == null) {
            // The queue's manual playlist was deleted mid-play: its materialized items keep
            // playing, but there is nothing left to extend from.
            queue.setSourceExhausted(true);
            return;
        }
        SourceChunk chunk = fetchNextChunk(queue, mediaType);
        BigDecimal position = maxPosition(queue);
        for (UUID mediaId : chunk.mediaIds()) {
            position = nextPosition(position);
            addItem(queue, buildItem(queue, mediaType, mediaId, position));
        }
        queue.setSourceOffset(queue.getSourceOffset() + chunk.sourceItemsConsumed());
        if (chunk.lastChunk()) {
            queue.setSourceExhausted(true);
        }
    }

    /**
     * Appends a chunk when the queue has a non-exhausted source and fewer than
     * EXTEND_THRESHOLD materialized items remain after the current item.
     */
    private void maybeExtend(PlayQueueEntity queue) {
        if (queue.getSourceType() == null || queue.isSourceExhausted()) {
            return;
        }
        List<PlayQueueItemEntity> items = queue.getItems();
        int currentIndex = -1;
        for (int i = 0; i < items.size(); i++) {
            if (items.get(i).getId().equals(queue.getCurrentItem())) {
                currentIndex = i;
                break;
            }
        }
        int itemsAfterCurrent = currentIndex == -1 ? 0 : items.size() - 1 - currentIndex;
        if (itemsAfterCurrent < EXTEND_THRESHOLD) {
            appendChunk(queue);
            playQueueRepository.save(queue);
        }
    }

    private SourceChunk fetchNextChunk(PlayQueueEntity queue, MediaType mediaType) {
        UUID sourceId = queue.getSourceId();
        int offset = queue.getSourceOffset();
        if (queue.isShuffle()) {
            String seed = queue.getShuffleSeed();
            UUID excludeId = queue.getSourceStartId() != null ? queue.getSourceStartId() : NIL_UUID;
            return switch (queue.getSourceType()) {
                case SHOW -> SourceChunk.of(episodeRepository.findEpisodeIdsForShowShuffled(sourceId, seed, excludeId, CHUNK_SIZE, offset));
                case ALBUM -> SourceChunk.of(trackRepository.findTrackIdsForAlbumShuffled(sourceId, seed, excludeId, CHUNK_SIZE, offset));
                case LIBRARY -> SourceChunk.of(mediaType == MediaType.MOVIE
                        ? movieRepository.findMovieIdsForLibraryShuffled(sourceId, seed, excludeId, CHUNK_SIZE, offset)
                        : trackRepository.findTrackIdsForLibraryShuffled(sourceId, seed, excludeId, CHUNK_SIZE, offset));
                case FILTER -> SourceChunk.of(filterChunkIds(queue, CHUNK_SIZE, offset));
                case PLAYLIST -> SourceChunk.of(pinnedFilterOf(queue) != null
                        ? filterChunkIds(queue, CHUNK_SIZE, offset)
                        : playlistItemRepository.findMediaIdsForPlaylistShuffled(sourceId, seed, excludeId, CHUNK_SIZE, offset));
                case ARTIST -> SourceChunk.of(shuffledTrackIdsForArtist(queue, seed, excludeId, CHUNK_SIZE, offset));
                case MOVIE, BOOK, PODCAST -> SourceChunk.of(List.of());
            };
        }
        return switch (queue.getSourceType()) {
            case SHOW -> SourceChunk.of(episodeRepository.findEpisodeIdsForShowOrdered(sourceId, CHUNK_SIZE, offset));
            case ALBUM -> SourceChunk.of(trackRepository.findTrackIdsForAlbumOrdered(sourceId, CHUNK_SIZE, offset));
            case BOOK -> SourceChunk.of(chapterRepository.findChapterIdsForBookOrdered(sourceId, CHUNK_SIZE, offset));
            case PODCAST -> SourceChunk.of(queue.isSourceAscending()
                    ? podcastEpisodeRepository.findEpisodeIdsForPodcastOrderedAsc(sourceId, CHUNK_SIZE, offset)
                    : podcastEpisodeRepository.findEpisodeIdsForPodcastOrdered(sourceId, CHUNK_SIZE, offset));
            case ARTIST -> SourceChunk.of(rankedTrackIdsForArtist(queue, CHUNK_SIZE, offset));
            case FILTER -> SourceChunk.of(filterChunkIds(queue, CHUNK_SIZE, offset));
            case PLAYLIST -> orderedPlaylistChunk(queue, mediaType, offset);
            default -> SourceChunk.of(List.of());
        };
    }

    /**
     * The next ordered chunk of a PLAYLIST queue. SMART playlists resolve through their pinned
     * filter; a BOOK library's playlist consumes a chunk of <em>books</em> and appends every
     * chapter of each, so its cursor advances by books rather than by appended ids.
     */
    private SourceChunk orderedPlaylistChunk(PlayQueueEntity queue, MediaType mediaType, int offset) {
        if (pinnedFilterOf(queue) != null) {
            return SourceChunk.of(filterChunkIds(queue, CHUNK_SIZE, offset));
        }
        UUID sourceId = queue.getSourceId();
        if (mediaType == MediaType.CHAPTER) {
            List<UUID> bookIds = playlistItemRepository.findMediaIdsForPlaylistOrdered(sourceId, CHUNK_SIZE, offset);
            List<UUID> chapterIds = new ArrayList<>();
            for (UUID bookId : bookIds) {
                chapterIds.addAll(chapterRepository.findChapterIdsForBookOrdered(bookId, Integer.MAX_VALUE, 0));
            }
            return new SourceChunk(chapterIds, bookIds.size(), bookIds.size() < CHUNK_SIZE);
        }
        return SourceChunk.of(playlistItemRepository.findMediaIdsForPlaylistOrdered(sourceId, CHUNK_SIZE, offset));
    }

    /**
     * A page of matching ids for a FILTER queue, in the pinned sort (or the seeded shuffle
     * order). The filter is evaluated as of queue creation where the data allows (play-derived
     * fields); library access is enforced here per chunk, like ARTIST — a filter can span
     * libraries, so the queue has no single source library to check up-front.
     */
    private List<UUID> filterChunkIds(PlayQueueEntity queue, int limit, int offset) {
        PinnedFilter pinned = pinnedFilterOf(queue);
        if (pinned == null) {
            return List.of();
        }
        Set<UUID> allowed = libraryAccessService.allowedLibraryIdsForUser(queue.getUserEntity()).orElse(null);
        return filterQueryService.chunkIds(pinned.kind(), pinned.filter(),
                pinned.sorting() != null ? pinned.sorting() : SortingEnum.NAME,
                pinned.sortingOrder() != null ? pinned.sortingOrder() : SortingOrder.ASCENDING,
                new FilterQueryService.FilterScope(allowed, pinned.libraryId(),
                        queue.getUserEntity().getExternalId()),
                new FilterQueryService.ChunkPage(queue.isShuffle() ? queue.getShuffleSeed() : null,
                        null, queue.getDateCreated(), limit, offset));
    }

    private PinnedFilter pinnedFilterOf(PlayQueueEntity queue) {
        return queue.getSourceFilter() == null ? null : FilterJson.readPinned(queue.getSourceFilter());
    }

    /**
     * The definition a new FILTER queue pins: the caller's own saved view (sourceId), or an
     * inline ad-hoc filter. A copy is stored on the queue, so later edits to the view leave
     * running queues alone.
     */
    private PinnedFilter resolvePinnedFilter(UUID savedViewId, MediaFilter filter, FilterKind filterKind,
                                             UUID libraryId, SortingEnum sorting, SortingOrder sortingOrder,
                                             Authentication authentication) {
        if (savedViewId != null) {
            if (filter != null || filterKind != null) {
                throw new IllegalArgumentException("Give either a saved view id or an inline filter, not both");
            }
            SavedViewEntity view = savedViewService.ownedView(authentication, savedViewId)
                    .orElseThrow(() -> new IllegalArgumentException("Saved view not found"));
            MediaFilter viewFilter = FilterJson.readFilter(view.getFilter());
            filterQueryService.validate(view.getKind(), viewFilter);
            UUID scope = view.getLibraryEntity() != null ? view.getLibraryEntity().getId() : libraryId;
            return new PinnedFilter(view.getKind(), viewFilter, scope, view.getSorting(), view.getSortingOrder());
        }
        if (filter == null || filterKind == null) {
            throw new IllegalArgumentException("A FILTER play queue needs a saved view id, or a filter with filterKind");
        }
        if (libraryId != null && !libraryAccessService.canAccess(libraryId, authentication)) {
            throw new IllegalArgumentException("Library not found");
        }
        filterQueryService.validate(filterKind, filter);
        return new PinnedFilter(filterKind, filter, libraryId, sorting, sortingOrder);
    }

    /**
     * A page of the owner's ranked track list for an ARTIST queue. The ranking is frozen at the
     * queue's creation time (plays recorded after it don't count), so paging stays deterministic
     * while playing the queue bumps the live ranking. Library access is enforced here per page —
     * an artist spans libraries, so the queue has no single source library to check up-front.
     */
    private List<UUID> rankedTrackIdsForArtist(PlayQueueEntity queue, int limit, int offset) {
        UUID personId = queue.getSourceId();
        String externalId = queue.getUserEntity().getExternalId();
        Instant asOf = queue.getDateCreated();
        Optional<Set<UUID>> allowed = libraryAccessService.allowedLibraryIdsForUser(queue.getUserEntity());
        return switch (queue.getRankKind()) {
            case MOST_PLAYED -> allowed
                    .map(ids -> ids.isEmpty() ? List.<UUID>of()
                            : trackRepository.findTopPlayedTrackIdsForPersonInLibraries(personId, externalId, ids, asOf, limit, offset))
                    .orElseGet(() -> trackRepository.findTopPlayedTrackIdsForPerson(personId, externalId, asOf, limit, offset));
            case RECENTLY_PLAYED -> allowed
                    .map(ids -> ids.isEmpty() ? List.<UUID>of()
                            : trackRepository.findRecentlyPlayedTrackIdsForPersonInLibraries(personId, externalId, ids, asOf, limit, offset))
                    .orElseGet(() -> trackRepository.findRecentlyPlayedTrackIdsForPerson(personId, externalId, asOf, limit, offset));
            // Ratings mutate in place, so this ranking cannot be frozen by date; a mid-playback
            // re-rate can shift later pages (worst case a rare skip or duplicate track).
            case HIGHEST_RATED -> allowed
                    .map(ids -> ids.isEmpty() ? List.<UUID>of()
                            : trackRepository.findTopRatedTrackIdsForPersonInLibraries(personId, externalId, ids, limit, offset))
                    .orElseGet(() -> trackRepository.findTopRatedTrackIdsForPerson(personId, externalId, limit, offset));
            // Not per user; frozen at creation so a scan adding tracks mid-playback can't shift pages.
            case RECENTLY_ADDED -> allowed
                    .map(ids -> ids.isEmpty() ? List.<UUID>of()
                            : trackRepository.findRecentlyAddedTrackIdsForPersonInLibraries(personId, ids, asOf, limit, offset))
                    .orElseGet(() -> trackRepository.findRecentlyAddedTrackIdsForPerson(personId, asOf, limit, offset));
        };
    }

    /**
     * A page of an ARTIST queue's shuffled catalogue: every track the artist is on — their own
     * albums as well as the ones they only guest on — in the seeded random order the queue was
     * created with. Frozen at the queue's creation time like the RECENTLY_ADDED ranking, so a scan
     * adding tracks mid-playback cannot shift later pages, and library access is enforced per page
     * for the same reason as {@link #rankedTrackIdsForArtist}.
     */
    private List<UUID> shuffledTrackIdsForArtist(PlayQueueEntity queue, String seed, UUID excludeId, int limit, int offset) {
        UUID personId = queue.getSourceId();
        Instant asOf = queue.getDateCreated();
        return libraryAccessService.allowedLibraryIdsForUser(queue.getUserEntity())
                .map(ids -> ids.isEmpty() ? List.<UUID>of()
                        : trackRepository.findShuffledTrackIdsForPersonInLibraries(personId, ids, asOf, seed, excludeId, limit, offset))
                .orElseGet(() -> trackRepository.findShuffledTrackIdsForPerson(personId, asOf, seed, excludeId, limit, offset));
    }

    private MediaType mediaTypeForSource(PlayQueueSourceType sourceType, UUID sourceId, boolean shuffle, PinnedFilter pinned) {
        return switch (sourceType) {
            case MOVIE -> MediaType.MOVIE;
            case FILTER -> {
                if (pinned == null) {
                    throw new IllegalArgumentException("Filter play queue without a pinned filter");
                }
                yield mediaTypeForFilterKind(pinned.kind());
            }
            case PLAYLIST -> {
                if (pinned != null) {
                    // SMART: the pinned copy survives edits and deletion of the playlist.
                    yield mediaTypeForFilterKind(pinned.kind());
                }
                PlaylistEntity playlist = playlistRepository.findById(sourceId).orElse(null);
                if (playlist == null) {
                    // Deleted mid-play; appendChunk marks the queue exhausted.
                    yield null;
                }
                MediaType itemType = PlaylistService.itemTypeFor(playlist.getLibraryEntity().getLibraryType());
                if (itemType == MediaType.BOOK) {
                    if (shuffle) {
                        throw new IllegalArgumentException("Book playlists cannot be shuffled; chapters only make sense in order");
                    }
                    // The playlist stores books; the queue plays their chapters.
                    yield MediaType.CHAPTER;
                }
                yield itemType;
            }
            case SHOW -> MediaType.EPISODE;
            case ALBUM -> MediaType.TRACK;
            case BOOK -> {
                if (shuffle) {
                    throw new IllegalArgumentException("Book play queues cannot be shuffled; chapters only make sense in order");
                }
                yield MediaType.CHAPTER;
            }
            case PODCAST -> {
                if (shuffle) {
                    throw new IllegalArgumentException("Podcast play queues cannot be shuffled; episodes play in the user's chosen order");
                }
                yield MediaType.PODCAST_EPISODE;
            }
            case ARTIST -> MediaType.TRACK;
            case LIBRARY -> {
                if (!shuffle) {
                    throw new IllegalArgumentException("Library play queues require shuffle");
                }
                LibraryEntity library = libraryRepository.findById(sourceId)
                        .orElseThrow(() -> new IllegalArgumentException("Library not found"));
                yield switch (library.getLibraryType()) {
                    case MOVIE -> MediaType.MOVIE;
                    case MUSIC -> MediaType.TRACK;
                    case SHOW -> throw new IllegalArgumentException("Show libraries cannot be shuffled; shuffle a single show instead");
                    case BOOK -> throw new IllegalArgumentException("Book libraries cannot be shuffled; play a single book instead");
                    case PODCAST -> throw new IllegalArgumentException("Podcast libraries cannot be shuffled; play a single podcast instead");
                    case COMIC -> throw new IllegalArgumentException("Comic libraries cannot be played; comics are read, not streamed");
                };
            }
        };
    }

    /** The playable media type of a filter kind; only the playable kinds pass. */
    private static MediaType mediaTypeForFilterKind(FilterKind kind) {
        return switch (kind) {
            case TRACK -> MediaType.TRACK;
            case MOVIE -> MediaType.MOVIE;
            case EPISODE -> MediaType.EPISODE;
            case ALBUM, ARTIST, SHOW -> throw new IllegalArgumentException(
                    "A " + kind + " filter cannot be played directly; filter on tracks, movies or episodes instead");
        };
    }

    /** The first chapter of a book, or the given id unchanged when it is not a book. */
    private UUID resolveBookStart(UUID startId) {
        return chapterRepository.findChapterIdsForBookOrdered(startId, 1, 0).stream().findFirst().orElse(startId);
    }

    private static CreatePlayQueueRequest withStartId(CreatePlayQueueRequest request, UUID startId) {
        return new CreatePlayQueueRequest(request.sourceType(), request.sourceId(), startId, request.shuffle(),
                request.rankKind(), request.filter(), request.filterKind(), request.libraryId(),
                request.sorting(), request.sortingOrder());
    }

    /**
     * Index of the start item in the full natural order of an ordered (non-shuffled) source.
     * [ascending] only applies to podcasts, whose order is the user's choice rather than intrinsic.
     */
    private int orderedIndexOf(PlayQueueEntity queue, UUID startId) {
        UUID sourceId = queue.getSourceId();
        PinnedFilter pinned = pinnedFilterOf(queue);
        if (pinned != null) {
            // FILTER queues and SMART playlists alike: the pinned filter is the source.
            return filterIndexOf(queue, pinned, startId);
        }
        if (queue.getSourceType() == PlayQueueSourceType.PLAYLIST) {
            return playlistOrderedIndexOf(queue, startId);
        }
        List<UUID> ids = switch (queue.getSourceType()) {
            case SHOW -> episodeRepository
                    .findIdsOnlyByShowEntityId(
                            sourceId,
                            Sort.by("seasonEntity.number").ascending()
                                    .and(Sort.by(FIELD_NUMBER).ascending()))
                    .stream()
                    .map(EpisodeRepository.IdOnly::getId)
                    .toList();
            case ALBUM -> trackRepository.findByAlbumEntity_Id(
                            sourceId,
                            Sort.by("discNumber").ascending().and(Sort.by(FIELD_NUMBER).ascending()))
                    .stream()
                    .map(TrackEntity::getId)
                    .toList();
            case BOOK -> chapterRepository.findByBookEntity_Id(sourceId, Sort.by(FIELD_NUMBER).ascending())
                    .stream()
                    .map(ChapterEntity::getId)
                    .toList();
            case PODCAST -> queue.isSourceAscending()
                    ? podcastEpisodeRepository.findEpisodeIdsForPodcastOrderedAsc(sourceId, Integer.MAX_VALUE, 0)
                    : podcastEpisodeRepository.findEpisodeIdsForPodcastOrdered(sourceId, Integer.MAX_VALUE, 0);
            case ARTIST -> rankedTrackIdsForArtist(queue, Integer.MAX_VALUE, 0);
            default -> List.of();
        };
        int index = ids.indexOf(startId);
        if (index == -1) {
            throw new IllegalArgumentException("Start item not part of the source");
        }
        return index;
    }

    /**
     * Index of the start item among a MANUAL playlist's entries — the unit of the source cursor.
     * For a BOOK playlist the entries are books while the start item is a chapter, so the index
     * is that of the book the chapter belongs to.
     */
    private int playlistOrderedIndexOf(PlayQueueEntity queue, UUID startId) {
        List<UUID> mediaIds = playlistItemRepository.findMediaIdsForPlaylistOrdered(queue.getSourceId(), Integer.MAX_VALUE, 0);
        boolean bookPlaylist = playlistRepository.findById(queue.getSourceId())
                .map(playlist -> playlist.getLibraryEntity().getLibraryType() == LibraryType.BOOK)
                .orElse(false);
        UUID needle = startId;
        if (bookPlaylist) {
            needle = chapterRepository.findById(startId)
                    .map(chapter -> chapter.getBookEntity().getId())
                    .orElse(startId);
        }
        int index = mediaIds.indexOf(needle);
        if (index == -1) {
            throw new IllegalArgumentException("Start item not part of the source");
        }
        return index;
    }

    /**
     * Index of the start item in a filter-backed source (a FILTER queue or a SMART playlist).
     * The database ranks the matching rows and returns only that one position, so a queue over a
     * ten-thousand-track filter still starts at the tapped item without materializing what
     * precedes it. Access is enforced by the same allowed-library scope the chunks use, so an
     * item the user may not see is "not part of the source".
     */
    private int filterIndexOf(PlayQueueEntity queue, PinnedFilter pinned, UUID startId) {
        Set<UUID> allowed = libraryAccessService.allowedLibraryIdsForUser(queue.getUserEntity()).orElse(null);
        int index = filterQueryService.indexOf(pinned.kind(), pinned.filter(),
                pinned.sorting() != null ? pinned.sorting() : SortingEnum.NAME,
                pinned.sortingOrder() != null ? pinned.sortingOrder() : SortingOrder.ASCENDING,
                new FilterQueryService.FilterScope(allowed, pinned.libraryId(),
                        queue.getUserEntity().getExternalId()),
                queue.getDateCreated(), startId);
        if (index == -1) {
            throw new IllegalArgumentException("Start item not part of the source");
        }
        return index;
    }

    private PlayQueueItemEntity findStartItem(PlayQueueEntity queue, UUID startId) {
        List<PlayQueueItemEntity> items = queue.getItems();
        if (items.isEmpty()) {
            throw new IllegalArgumentException("Source contains no items");
        }
        if (startId == null || queue.getSourceType() == PlayQueueSourceType.MOVIE) {
            return items.getFirst();
        }
        return items.stream()
                .filter(item -> startId.equals(item.getMovieEntityId())
                        || startId.equals(item.getEpisodeEntityId())
                        || startId.equals(item.getTrackEntityId())
                        || startId.equals(item.getChapterEntityId())
                        || startId.equals(item.getPodcastEpisodeEntityId()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Start item not in queue"));
    }

    /**
     * Hibernate association management (bytecode enhancement) may already have inserted the
     * item into the queue's collection when its owning side was set; only add it when absent.
     * Checks by identity: BaseEntity's id-based equals cannot recognize an unsaved (id-less) item.
     */
    private void addItem(PlayQueueEntity queue, PlayQueueItemEntity item) {
        for (PlayQueueItemEntity existing : queue.getItems()) {
            if (existing == item) {
                return;
            }
        }
        queue.getItems().add(item);
    }

    private PlayQueueItemEntity buildItem(PlayQueueEntity queue, MediaType mediaType, UUID mediaId, BigDecimal position) {
        PlayQueueItemEntity item = PlayQueueItemEntity.builder()
                .playQueueEntity(queue)
                .type(mediaType)
                .position(position)
                .build();
        switch (mediaType) {
            case MOVIE -> item.setMovieEntityId(mediaId);
            case EPISODE -> item.setEpisodeEntityId(mediaId);
            case TRACK -> item.setTrackEntityId(mediaId);
            case CHAPTER -> item.setChapterEntityId(mediaId);
            case PODCAST_EPISODE -> item.setPodcastEpisodeEntityId(mediaId);
            case BOOK -> throw new IllegalArgumentException("Books cannot be played; queue their chapters instead");
            default -> throw new IllegalArgumentException("Media type cannot be queued: " + mediaType);
        }
        return item;
    }

    private void validateMediaExists(MediaType mediaType, UUID mediaId) {
        boolean exists = switch (mediaType) {
            case MOVIE -> movieRepository.existsById(mediaId);
            case EPISODE -> episodeRepository.existsById(mediaId);
            case TRACK -> trackRepository.existsById(mediaId);
            case CHAPTER -> chapterRepository.existsById(mediaId);
            case PODCAST_EPISODE -> podcastEpisodeRepository.existsById(mediaId);
            case BOOK -> throw new IllegalArgumentException("Books cannot be added to a play queue; add their chapters instead");
            case COMIC -> throw new IllegalArgumentException("Comics cannot be added to a play queue; they are read, not streamed");
        };
        if (!exists) {
            throw new IllegalArgumentException("Media item not found");
        }
    }

    private BigDecimal maxPosition(PlayQueueEntity queue) {
        return GapPositions.maxPosition(queue.getItems());
    }

    private Optional<PlayQueueItemEntity> itemById(PlayQueueEntity queue, UUID itemId) {
        return queue.getItems().stream().filter(item -> item.getId().equals(itemId)).findFirst();
    }

    private void sortItems(PlayQueueEntity queue) {
        queue.getItems().sort(Comparator.comparing(PlayQueueItemEntity::getPosition));
    }

    /**
     * Position for placing an item directly after afterItemId (or at the front when null),
     * ignoring the item being moved. Returns null when the gap between the neighbours is
     * exhausted and the queue needs a rebalance first.
     */
    private BigDecimal targetPosition(PlayQueueEntity queue, UUID afterItemId, UUID movingItemId) {
        return GapPositions.targetPosition(queue.getItems(), afterItemId, movingItemId);
    }

    /**
     * Renumbers all items of the queue, in their current order, back to whole GAP multiples.
     */
    private void rebalance(PlayQueueEntity queue) {
        log.debug("Rebalancing positions of play queue {}", queue.getId());
        GapPositions.rebalance(queue.getItems());
    }

    /**
     * Queue edits (add/move/remove) are gated by remote-control permission: the owner and any
     * user the owner (or the session's per-session override) allows to control the session may
     * edit its queue. A caller without that permission is treated as if the queue did not exist —
     * the same not-found behaviour {@link #getPlayQueue} gives a denied reader.
     */
    private PlayQueueEntity getEditableQueue(UUID playQueueId, Authentication authentication) {
        PlayQueueEntity queue = playQueueRepository.findById(playQueueId)
                .orElseThrow(() -> new IllegalArgumentException("Play queue not found"));
        if (!canControl(queue, authentication)) {
            throw new IllegalArgumentException("Play queue not found");
        }
        return queue;
    }

    /** Whether the caller may remote-control this session, honouring the per-session override. */
    private boolean canControl(PlayQueueEntity queue, Authentication authentication) {
        UUID viewerId = userService.getOrCreateUser(authentication).getId();
        UUID ownerId = queue.getUserEntity().getId();
        Set<UUID> sessionAllowed = queue.getControlScopeOverride() == RemoteControlScope.ALLOWLIST
                ? new HashSet<>(playQueueControlGrantRepository.findGranteeIdsByPlayQueueId(queue.getId()))
                : Set.of();
        return playbackSharingService.canControl(viewerId, ownerId, queue.getControlScopeOverride(), sessionAllowed);
    }

    private void checkOwnership(PlayQueueEntity queue, Authentication authentication) {
        UserEntity user = userService.getOrCreateUser(authentication);
        if (!queue.getUserEntity().getId().equals(user.getId())) {
            throw new AccessDeniedException("Play queue does not belong to the authenticated user");
        }
    }

    private void updatePlayQueueItemWithProgress(long progressInMilliseconds, UUID playQueueItemId, List<UserEntity> listeners, PlayQueueEntity playQueueEntity) {
        playQueueEntity.getItems().stream().filter(item -> item.getId().equals(playQueueItemId)).findAny().ifPresent(playQueueItemEntity -> {
            playQueueEntity.setCurrentItem(playQueueItemEntity.getId());
            playQueueEntity.setProgressInMilliseconds(progressInMilliseconds);
            playQueueRepository.save(playQueueEntity);
            maybeExtend(playQueueEntity);
            recordWatchStatuses(progressInMilliseconds, playQueueItemId, listeners, playQueueItemEntity);
        });
    }

    /**
     * Update the watch status of an episode if it's played for more then one minute.
     * Audiobook chapters use a lower threshold: their position is shared with the reader,
     * so the first minute of a chapter has to be recoverable when switching to text.
     * The status is written for every listener: the owner plus each following user —
     * only the owner ever reports progress, so followers cannot flip it back themselves.
     */
    private void recordWatchStatuses(long progressInMilliseconds, UUID playQueueItemId, List<UserEntity> listeners, PlayQueueItemEntity playQueueItemEntity) {
        MediaType type = playQueueItemEntity.getType();
        long minimumProgress = type == MediaType.CHAPTER ? CHAPTER_PROGRESS_THRESHOLD_MS : 60000;
        for (UserEntity listener : listeners) {
            if (progressInMilliseconds > minimumProgress) {
                if (type == MediaType.EPISODE) {
                    updateEpisodeWatchStatus(progressInMilliseconds, playQueueItemId, listener, playQueueItemEntity);
                } else if (type == MediaType.MOVIE) {
                    updateMovieWatchStatus(progressInMilliseconds, playQueueItemId, listener, playQueueItemEntity);
                } else if (type == MediaType.CHAPTER) {
                    updateChapterWatchStatus(progressInMilliseconds, listener, playQueueItemEntity);
                } else if (type == MediaType.PODCAST_EPISODE) {
                    updatePodcastEpisodeWatchStatus(progressInMilliseconds, playQueueItemId, listener, playQueueItemEntity);
                }
            }
            // Tracks have their own, shorter threshold (many are barely longer than a minute):
            // a play counts once 30 seconds — or half of a short track — has been heard.
            if (type == MediaType.TRACK) {
                updateTrackWatchStatus(progressInMilliseconds, playQueueItemId, listener, playQueueItemEntity);
            }
        }
    }

    private void updateTrackWatchStatus(long progressInMilliseconds, UUID playQueueItemId, UserEntity listener, PlayQueueItemEntity playQueueItemEntity) {
        trackRepository.findById(playQueueItemEntity.getTrackEntityId()).ifPresent(trackEntity -> {
            if (progressInMilliseconds > trackPlayThreshold(trackEntity)) {
                WatchStatusEntity watchStatusEntity = watchStatusService.getOrCreateForTrack(listener, playQueueItemId, trackEntity);
                updateWatchStatus(progressInMilliseconds, watchStatusEntity, trackEntity.getMediaFileEntities());
            }
        });
    }

    /** Half the track when it is shorter than a minute, {@value #TRACK_PLAY_THRESHOLD_MS} ms otherwise. */
    private long trackPlayThreshold(TrackEntity trackEntity) {
        return trackEntity.getMediaFileEntities().stream().findFirst()
                .map(file -> Math.min(TRACK_PLAY_THRESHOLD_MS, file.getDurationInMilliseconds() / 2))
                .orElse(TRACK_PLAY_THRESHOLD_MS);
    }

    private void updateEpisodeWatchStatus(long progressInMilliseconds, UUID playQueueItemId, UserEntity listener, PlayQueueItemEntity playQueueItemEntity) {
        episodeRepository.findById(playQueueItemEntity.getEpisodeEntityId()).ifPresent(episodeEntity -> {
            WatchStatusEntity watchStatusEntity = watchStatusService.getOrCreate(listener, playQueueItemId, episodeEntity, null);
            // Progress is absolute within the file. For an episode inside a multi-episode file
            // (s04e06-e07.mkv) the episode ends at its slice boundary, not at the file's end —
            // otherwise only the last episode of the file could ever become watched.
            List<MediaFileEntity> files = mediaFileEpisodeService.filesForEpisode(episodeEntity.getId());
            long endOfEpisode = files.stream().findFirst()
                    .map(file -> mediaFileEpisodeService.segmentFor(file.getId(), episodeEntity.getId())
                            .filter(segment -> segment.getDurationInMilliseconds() > 0)
                            .map(segment -> segment.getStartInMilliseconds() + segment.getDurationInMilliseconds())
                            .orElse(file.getDurationInMilliseconds()))
                    .orElse(0L);
            updateWatchStatus(progressInMilliseconds, watchStatusEntity, endOfEpisode);
        });
    }

    private void updateMovieWatchStatus(long progressInMilliseconds, UUID playQueueItemId, UserEntity listener, PlayQueueItemEntity playQueueItemEntity) {
        movieRepository.findById(playQueueItemEntity.getMovieEntityId()).ifPresent(movieEntity -> {
            WatchStatusEntity watchStatusEntity = watchStatusService.getOrCreate(listener, playQueueItemId, null, movieEntity);
            updateWatchStatus(progressInMilliseconds, watchStatusEntity, movieEntity.getMediaFileEntities());
        });
    }

    private void updateChapterWatchStatus(long progressInMilliseconds, UserEntity listener, PlayQueueItemEntity playQueueItemEntity) {
        chapterRepository.findById(playQueueItemEntity.getChapterEntityId()).ifPresent(chapterEntity -> {
            WatchStatusEntity watchStatusEntity = watchStatusService.getOrCreateForChapter(listener, chapterEntity);
            updateWatchStatus(progressInMilliseconds, watchStatusEntity, chapterEntity.getMediaFileEntities());
        });
    }

    private void updatePodcastEpisodeWatchStatus(long progressInMilliseconds, UUID playQueueItemId, UserEntity listener, PlayQueueItemEntity playQueueItemEntity) {
        podcastEpisodeRepository.findById(playQueueItemEntity.getPodcastEpisodeEntityId()).ifPresent(episodeEntity -> {
            WatchStatusEntity watchStatusEntity = watchStatusService.getOrCreateForPodcastEpisode(listener, playQueueItemId, episodeEntity);
            updateWatchStatus(progressInMilliseconds, watchStatusEntity, episodeEntity.getMediaFileEntities());
        });
    }

    private void updateWatchStatus(long progressInMilliseconds, WatchStatusEntity watchStatusEntity, List<MediaFileEntity> mediaFileEntities) {
        long endOfItem = mediaFileEntities.isEmpty() ? 0 : mediaFileEntities.get(0).getDurationInMilliseconds();
        updateWatchStatus(progressInMilliseconds, watchStatusEntity, endOfItem);
    }

    /** endOfItemInMilliseconds 0 means unknown: progress is recorded but watched is left untouched. */
    private void updateWatchStatus(long progressInMilliseconds, WatchStatusEntity watchStatusEntity, long endOfItemInMilliseconds) {
        watchStatusEntity.setProgressInMilliseconds(progressInMilliseconds);
        if (endOfItemInMilliseconds > 0) {
            // Within a minute of — or past — the end of the item counts as watched. "Past" happens
            // when playback of a multi-episode file runs across an episode boundary.
            watchStatusEntity.setWatched(endOfItemInMilliseconds - progressInMilliseconds < 60000);
        }
        watchStatusRepository.save(watchStatusEntity);
        // Keep the user's continue-watching entry in step with the heartbeat: finishing an episode
        // here is what makes the next one show up in their list.
        continueWatchingService.onWatchStatusChanged(watchStatusEntity);
    }

}
