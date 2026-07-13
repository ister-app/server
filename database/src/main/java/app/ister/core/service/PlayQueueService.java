package app.ister.core.service;

import app.ister.core.entity.LibraryEntity;
import app.ister.core.entity.MediaFileEntity;
import app.ister.core.entity.PlayQueueEntity;
import app.ister.core.entity.PlayQueueItemEntity;
import app.ister.core.entity.TrackEntity;
import app.ister.core.entity.UserEntity;
import app.ister.core.entity.WatchStatusEntity;
import app.ister.core.enums.MediaType;
import app.ister.core.enums.PlayQueueSourceType;
import app.ister.core.enums.SortingOrder;
import app.ister.core.enums.SubtitleFormat;
import app.ister.core.entity.ChapterEntity;
import app.ister.core.repository.ChapterRepository;
import app.ister.core.repository.PodcastEpisodeRepository;
import app.ister.core.repository.EpisodeRepository;
import app.ister.core.repository.LibraryRepository;
import app.ister.core.repository.MovieRepository;
import app.ister.core.repository.PlayQueueRepository;
import app.ister.core.repository.TrackRepository;
import app.ister.core.repository.WatchStatusRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Sort;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Slf4j
@Service
public class PlayQueueService {
    /** Audiobook chapters record progress from here on; see updatePlayQueueItemWithProgress. */
    private static final long CHAPTER_PROGRESS_THRESHOLD_MS = 5000;
    private static final String FIELD_NUMBER = "number";

    private final PlayQueueRepository playQueueRepository;

    private final EpisodeRepository episodeRepository;

    private final MovieRepository movieRepository;

    private final TrackRepository trackRepository;

    private final ChapterRepository chapterRepository;

    private final PodcastEpisodeRepository podcastEpisodeRepository;

    private final LibraryRepository libraryRepository;

    private final UserService userService;

    private final WatchStatusRepository watchStatusRepository;

    private final WatchStatusService watchStatusService;

    private final PodcastPreferenceService podcastPreferenceService;

    /** Stream settings a client reports via updatePlayQueue; used to prefetch the next item in the same format. */
    public record StreamSettings(Boolean direct, Boolean transcode, SubtitleFormat subtitleFormat) {
    }

    private static final BigDecimal GAP = new BigDecimal("1000");
    private static final BigDecimal TWO = new BigDecimal("2");
    private static final int POSITION_SCALE = 10;
    // Number of source items materialized per append.
    private static final int CHUNK_SIZE = 50;
    // Append a new chunk when fewer than this many items remain after the current item.
    private static final int EXTEND_THRESHOLD = 15;
    // How many already-played items to keep before the start item when creating a queue mid-source.
    private static final int BACK_WINDOW = 10;
    // Bound for the shuffle exclusion parameter when there is no start item; matches no row.
    private static final UUID NIL_UUID = new UUID(0, 0);

    public PlayQueueService(PlayQueueRepository playQueueRepository, EpisodeRepository episodeRepository, MovieRepository movieRepository, TrackRepository trackRepository, ChapterRepository chapterRepository, PodcastEpisodeRepository podcastEpisodeRepository, LibraryRepository libraryRepository, UserService userService, WatchStatusRepository watchStatusRepository, WatchStatusService watchStatusService, PodcastPreferenceService podcastPreferenceService) {
        this.playQueueRepository = playQueueRepository;
        this.episodeRepository = episodeRepository;
        this.movieRepository = movieRepository;
        this.trackRepository = trackRepository;
        this.chapterRepository = chapterRepository;
        this.podcastEpisodeRepository = podcastEpisodeRepository;
        this.libraryRepository = libraryRepository;
        this.userService = userService;
        this.watchStatusRepository = watchStatusRepository;
        this.watchStatusService = watchStatusService;
        this.podcastPreferenceService = podcastPreferenceService;
    }

    /**
     * Readable by any authenticated user (not just the owner): remote-control ("party
     * mode") clients render another user's queue from this.
     */
    @Transactional
    public Optional<PlayQueueEntity> getPlayQueue(UUID id, Authentication authentication) {
        Optional<PlayQueueEntity> playQueueEntityOptional = playQueueRepository.findById(id);
        playQueueEntityOptional.ifPresent(this::maybeExtend);
        return playQueueEntityOptional;
    }

    /**
     * Creates a play queue from a source. Only an initial window of items is materialized;
     * more items are appended lazily while the user plays through the queue.
     *
     * @param startId the episode/track to start at (ignored for MOVIE, optional otherwise)
     * @param shuffle play the source in a stable seeded random order; required for LIBRARY sources
     */
    @Transactional
    public PlayQueueEntity createPlayQueue(PlayQueueSourceType sourceType, UUID sourceId, UUID startId, boolean shuffle, Authentication authentication) {
        log.debug("Creating play queue for user: {}, source type: {}, source: {}, shuffle: {}", authentication.getName(), sourceType, sourceId, shuffle);

        PlayQueueEntity queue = PlayQueueEntity.builder()
                .userEntity(userService.getOrCreateUser(authentication))
                .sourceType(sourceType)
                .sourceId(sourceId)
                .items(new ArrayList<>())
                .build();

        if (sourceType == PlayQueueSourceType.MOVIE) {
            addItem(queue, buildItem(queue, MediaType.MOVIE, sourceId, GAP));
            queue.setSourceExhausted(true);
        } else {
            MediaType mediaType = mediaTypeForSource(sourceType, sourceId, shuffle);
            queue.setShuffle(shuffle);
            if (sourceType == PlayQueueSourceType.PODCAST) {
                // Freeze the user's preferred order onto the queue. The queue materializes its
                // items in chunks as playback goes on, and re-reading the preference per chunk
                // would flip a running queue around the moment the user changes the setting.
                queue.setSourceAscending(podcastPreferenceService.getEpisodeOrder(authentication, sourceId)
                        == SortingOrder.ASCENDING);
            }
            if (shuffle) {
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
                        orderedIndexOf(sourceType, sourceId, startId, queue.isSourceAscending()) - BACK_WINDOW));
            }
            appendChunk(queue);
        }

        playQueueRepository.save(queue);
        queue.setCurrentItem(findStartItem(queue, startId).getId());
        playQueueRepository.save(queue);
        return queue;
    }

    /**
     * Find the PlayQueue and then update it.
     *
     * @param streamSettings what the client is currently playing with; stored on the queue
     *                       and used to prefetch the next item in the same format (may be null)
     */
    @Transactional
    public Optional<PlayQueueEntity> updatePlayQueue(UUID id, long progressInMilliseconds, UUID playQueueItemId, StreamSettings streamSettings, Authentication authentication) {
        log.debug("Updating play queue for user: {}", authentication.getName());
        // Update the current playing episode
        Optional<PlayQueueEntity> playQueueEntityOptional = playQueueRepository.findById(id);
        playQueueEntityOptional.ifPresent(playQueueEntity -> {
            checkOwnership(playQueueEntity, authentication);
            applyStreamSettings(playQueueEntity, streamSettings);
            updatePlayQueueItemWithProgress(progressInMilliseconds, playQueueItemId, authentication, playQueueEntity);
        });
        return playQueueEntityOptional;
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
        PlayQueueEntity queue = getEditableQueue(playQueueId);
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
        PlayQueueEntity queue = getEditableQueue(playQueueId);
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
        PlayQueueEntity queue = getEditableQueue(playQueueId);
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
     * Returns the next position value given the previous one (or null for the first item).
     */
    private BigDecimal nextPosition(BigDecimal previous) {
        return (previous == null) ? GAP : previous.add(GAP);
    }

    /**
     * Appends the next chunk of source items to the queue and advances the source cursor.
     * Marks the source exhausted when it returns fewer items than a full chunk.
     */
    private void appendChunk(PlayQueueEntity queue) {
        MediaType mediaType = mediaTypeForSource(queue.getSourceType(), queue.getSourceId(), queue.isShuffle());
        List<UUID> mediaIds = fetchNextChunk(queue, mediaType);
        BigDecimal position = maxPosition(queue);
        for (UUID mediaId : mediaIds) {
            position = nextPosition(position);
            addItem(queue, buildItem(queue, mediaType, mediaId, position));
        }
        queue.setSourceOffset(queue.getSourceOffset() + mediaIds.size());
        if (mediaIds.size() < CHUNK_SIZE) {
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

    private List<UUID> fetchNextChunk(PlayQueueEntity queue, MediaType mediaType) {
        UUID sourceId = queue.getSourceId();
        int offset = queue.getSourceOffset();
        if (queue.isShuffle()) {
            String seed = queue.getShuffleSeed();
            UUID excludeId = queue.getSourceStartId() != null ? queue.getSourceStartId() : NIL_UUID;
            return switch (queue.getSourceType()) {
                case SHOW -> episodeRepository.findEpisodeIdsForShowShuffled(sourceId, seed, excludeId, CHUNK_SIZE, offset);
                case ALBUM -> trackRepository.findTrackIdsForAlbumShuffled(sourceId, seed, excludeId, CHUNK_SIZE, offset);
                case LIBRARY -> mediaType == MediaType.MOVIE
                        ? movieRepository.findMovieIdsForLibraryShuffled(sourceId, seed, excludeId, CHUNK_SIZE, offset)
                        : trackRepository.findTrackIdsForLibraryShuffled(sourceId, seed, excludeId, CHUNK_SIZE, offset);
                case MOVIE, BOOK, PODCAST -> List.of();
            };
        }
        return switch (queue.getSourceType()) {
            case SHOW -> episodeRepository.findEpisodeIdsForShowOrdered(sourceId, CHUNK_SIZE, offset);
            case ALBUM -> trackRepository.findTrackIdsForAlbumOrdered(sourceId, CHUNK_SIZE, offset);
            case BOOK -> chapterRepository.findChapterIdsForBookOrdered(sourceId, CHUNK_SIZE, offset);
            case PODCAST -> queue.isSourceAscending()
                    ? podcastEpisodeRepository.findEpisodeIdsForPodcastOrderedAsc(sourceId, CHUNK_SIZE, offset)
                    : podcastEpisodeRepository.findEpisodeIdsForPodcastOrdered(sourceId, CHUNK_SIZE, offset);
            default -> List.of();
        };
    }

    private MediaType mediaTypeForSource(PlayQueueSourceType sourceType, UUID sourceId, boolean shuffle) {
        return switch (sourceType) {
            case MOVIE -> MediaType.MOVIE;
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
                };
            }
        };
    }

    /**
     * Index of the start item in the full natural order of an ordered (non-shuffled) source.
     * [ascending] only applies to podcasts, whose order is the user's choice rather than intrinsic.
     */
    private int orderedIndexOf(PlayQueueSourceType sourceType, UUID sourceId, UUID startId, boolean ascending) {
        List<UUID> ids = switch (sourceType) {
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
            case PODCAST -> ascending
                    ? podcastEpisodeRepository.findEpisodeIdsForPodcastOrderedAsc(sourceId, Integer.MAX_VALUE, 0)
                    : podcastEpisodeRepository.findEpisodeIdsForPodcastOrdered(sourceId, Integer.MAX_VALUE, 0);
            default -> List.of();
        };
        int index = ids.indexOf(startId);
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
     * Checks by identity: BaseEntity's Lombok equals treats all unsaved (id-less) items as equal.
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
        };
        if (!exists) {
            throw new IllegalArgumentException("Media item not found");
        }
    }

    private BigDecimal maxPosition(PlayQueueEntity queue) {
        return queue.getItems().stream()
                .map(PlayQueueItemEntity::getPosition)
                .max(Comparator.naturalOrder())
                .orElse(null);
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
        List<PlayQueueItemEntity> others = queue.getItems().stream()
                .filter(item -> !item.getId().equals(movingItemId))
                .sorted(Comparator.comparing(PlayQueueItemEntity::getPosition))
                .toList();
        if (others.isEmpty()) {
            return GAP;
        }
        if (afterItemId == null) {
            BigDecimal first = others.getFirst().getPosition();
            BigDecimal candidate = first.divide(TWO, POSITION_SCALE, RoundingMode.HALF_UP);
            return (candidate.signum() > 0 && candidate.compareTo(first) < 0) ? candidate : null;
        }
        int afterIndex = -1;
        for (int i = 0; i < others.size(); i++) {
            if (others.get(i).getId().equals(afterItemId)) {
                afterIndex = i;
                break;
            }
        }
        if (afterIndex == -1) {
            throw new IllegalArgumentException("After-item not in queue");
        }
        BigDecimal previous = others.get(afterIndex).getPosition();
        if (afterIndex == others.size() - 1) {
            return previous.add(GAP);
        }
        BigDecimal next = others.get(afterIndex + 1).getPosition();
        BigDecimal candidate = previous.add(next).divide(TWO, POSITION_SCALE, RoundingMode.HALF_UP);
        return (candidate.compareTo(previous) > 0 && candidate.compareTo(next) < 0) ? candidate : null;
    }

    /**
     * Renumbers all items of the queue, in their current order, back to whole GAP multiples.
     */
    private void rebalance(PlayQueueEntity queue) {
        log.debug("Rebalancing positions of play queue {}", queue.getId());
        List<PlayQueueItemEntity> sorted = queue.getItems().stream()
                .sorted(Comparator.comparing(PlayQueueItemEntity::getPosition))
                .toList();
        BigDecimal position = null;
        for (PlayQueueItemEntity item : sorted) {
            position = nextPosition(position);
            item.setPosition(position);
        }
    }

    /**
     * Queue edits (add/move/remove) are deliberately not ownership-checked: remote
     * control ("party mode") lets any authenticated user edit any queue. The heartbeat
     * (updatePlayQueue) stays owner-only — it writes the caller's watch status and
     * defines the session identity.
     */
    private PlayQueueEntity getEditableQueue(UUID playQueueId) {
        return playQueueRepository.findById(playQueueId)
                .orElseThrow(() -> new IllegalArgumentException("Play queue not found"));
    }

    private void checkOwnership(PlayQueueEntity queue, Authentication authentication) {
        UserEntity user = userService.getOrCreateUser(authentication);
        if (!queue.getUserEntity().getId().equals(user.getId())) {
            throw new AccessDeniedException("Play queue does not belong to the authenticated user");
        }
    }

    private void updatePlayQueueItemWithProgress(long progressInMilliseconds, UUID playQueueItemId, Authentication authentication, PlayQueueEntity playQueueEntity) {
        playQueueEntity.getItems().stream().filter(item -> item.getId().equals(playQueueItemId)).findAny().ifPresent(playQueueItemEntity -> {
            playQueueEntity.setCurrentItem(playQueueItemEntity.getId());
            playQueueEntity.setProgressInMilliseconds(progressInMilliseconds);
            playQueueRepository.save(playQueueEntity);
            maybeExtend(playQueueEntity);
            // Update the watch status of an episode if it's played for more then one minute.
            // Audiobook chapters use a lower threshold: their position is shared with the reader,
            // so the first minute of a chapter has to be recoverable when switching to text.
            MediaType type = playQueueItemEntity.getType();
            long minimumProgress = type == MediaType.CHAPTER ? CHAPTER_PROGRESS_THRESHOLD_MS : 60000;
            if (progressInMilliseconds > minimumProgress) {
                if (type == MediaType.EPISODE) {
                    updateEpisodeWatchStatus(progressInMilliseconds, playQueueItemId, authentication, playQueueItemEntity);
                } else if (type == MediaType.MOVIE) {
                    updateMovieWatchStatus(progressInMilliseconds, playQueueItemId, authentication, playQueueItemEntity);
                } else if (type == MediaType.CHAPTER) {
                    updateChapterWatchStatus(progressInMilliseconds, authentication, playQueueItemEntity);
                } else if (type == MediaType.PODCAST_EPISODE) {
                    updatePodcastEpisodeWatchStatus(progressInMilliseconds, playQueueItemId, authentication, playQueueItemEntity);
                }
            }
        });
    }

    private void updateEpisodeWatchStatus(long progressInMilliseconds, UUID playQueueItemId, Authentication authentication, PlayQueueItemEntity playQueueItemEntity) {
        episodeRepository.findById(playQueueItemEntity.getEpisodeEntityId()).ifPresent(episodeEntity -> {
            WatchStatusEntity watchStatusEntity = watchStatusService.getOrCreate(authentication, playQueueItemId, episodeEntity, null);
            updateWatchStatus(progressInMilliseconds, watchStatusEntity, episodeEntity.getMediaFileEntities());
        });
    }

    private void updateMovieWatchStatus(long progressInMilliseconds, UUID playQueueItemId, Authentication authentication, PlayQueueItemEntity playQueueItemEntity) {
        movieRepository.findById(playQueueItemEntity.getMovieEntityId()).ifPresent(movieEntity -> {
            WatchStatusEntity watchStatusEntity = watchStatusService.getOrCreate(authentication, playQueueItemId, null, movieEntity);
            updateWatchStatus(progressInMilliseconds, watchStatusEntity, movieEntity.getMediaFileEntities());
        });
    }

    private void updateChapterWatchStatus(long progressInMilliseconds, Authentication authentication, PlayQueueItemEntity playQueueItemEntity) {
        chapterRepository.findById(playQueueItemEntity.getChapterEntityId()).ifPresent(chapterEntity -> {
            WatchStatusEntity watchStatusEntity = watchStatusService.getOrCreateForChapter(authentication, chapterEntity);
            updateWatchStatus(progressInMilliseconds, watchStatusEntity, chapterEntity.getMediaFileEntities());
        });
    }

    private void updatePodcastEpisodeWatchStatus(long progressInMilliseconds, UUID playQueueItemId, Authentication authentication, PlayQueueItemEntity playQueueItemEntity) {
        podcastEpisodeRepository.findById(playQueueItemEntity.getPodcastEpisodeEntityId()).ifPresent(episodeEntity -> {
            WatchStatusEntity watchStatusEntity = watchStatusService.getOrCreateForPodcastEpisode(authentication, playQueueItemId, episodeEntity);
            updateWatchStatus(progressInMilliseconds, watchStatusEntity, episodeEntity.getMediaFileEntities());
        });
    }

    private void updateWatchStatus(long progressInMilliseconds, WatchStatusEntity watchStatusEntity, List<MediaFileEntity> mediaFileEntities) {
        watchStatusEntity.setProgressInMilliseconds(progressInMilliseconds);
        if (!mediaFileEntities.isEmpty()) {
            long durationOfMediaFile = mediaFileEntities.get(0).getDurationInMilliseconds();
            boolean durationIsLessThenOneMinute = durationOfMediaFile - progressInMilliseconds < 60000;
            watchStatusEntity.setWatched(durationIsLessThenOneMinute);
        }
        watchStatusRepository.save(watchStatusEntity);
    }

}
