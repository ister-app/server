package app.ister.core.service;

import app.ister.core.entity.BookEntity;
import app.ister.core.entity.EpisodeEntity;
import app.ister.core.entity.ImageEntity;
import app.ister.core.entity.LibraryEntity;
import app.ister.core.entity.MovieEntity;
import app.ister.core.entity.PlaylistEntity;
import app.ister.core.entity.PlaylistItemEntity;
import app.ister.core.entity.PodcastEpisodeEntity;
import app.ister.core.entity.TrackEntity;
import app.ister.core.entity.UserEntity;
import app.ister.core.enums.ImageType;
import app.ister.core.enums.LibraryType;
import app.ister.core.enums.MediaType;
import app.ister.core.enums.PlaylistType;
import app.ister.core.enums.SortingEnum;
import app.ister.core.enums.SortingOrder;
import app.ister.core.filter.FilterJson;
import app.ister.core.filter.FilterKind;
import app.ister.core.filter.MediaFilter;
import app.ister.core.repository.BookRepository;
import app.ister.core.repository.EpisodeRepository;
import app.ister.core.repository.ImageRepository;
import app.ister.core.repository.LibraryRepository;
import app.ister.core.repository.MovieRepository;
import app.ister.core.repository.PlaylistItemRepository;
import app.ister.core.repository.PlaylistRepository;
import app.ister.core.repository.PodcastEpisodeRepository;
import app.ister.core.repository.TrackRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * The calling user's playlists. Strictly personal, like {@link SavedViewService}: every lookup
 * goes through the owner, and someone else's playlist id behaves like a missing one. A playlist
 * belongs to exactly one library and only holds that library's playable items — MANUAL entries
 * are validated against it, and a SMART playlist's filter is scoped to it.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PlaylistService {

    private static final String PLAYLIST_NOT_FOUND = "Playlist not found";

    private final PlaylistRepository playlistRepository;
    private final PlaylistItemRepository playlistItemRepository;
    private final ImageRepository imageRepository;
    private final LibraryRepository libraryRepository;
    private final LibraryAccessService libraryAccessService;
    private final FilterQueryService filterQueryService;
    private final UserService userService;
    private final MovieRepository movieRepository;
    private final EpisodeRepository episodeRepository;
    private final TrackRepository trackRepository;
    private final BookRepository bookRepository;
    private final PodcastEpisodeRepository podcastEpisodeRepository;

    /** The caller's playlists, by name; optionally narrowed to one library. */
    @Transactional(readOnly = true)
    public List<PlaylistEntity> playlists(Authentication authentication, UUID libraryId) {
        UserEntity user = userService.getOrCreateUser(authentication);
        return playlistRepository.findByUserEntityOrderByNameAsc(user).stream()
                .filter(playlist -> libraryId == null || libraryId.equals(playlist.getLibraryEntity().getId()))
                .toList();
    }

    /** The caller's playlists in the given libraries, for batch-resolving Library.playlists. */
    @Transactional(readOnly = true)
    public List<PlaylistEntity> playlistsInLibraries(Authentication authentication, List<UUID> libraryIds) {
        UserEntity user = userService.getOrCreateUser(authentication);
        return playlistRepository.findByUserEntityAndLibraryEntityIdInOrderByNameAsc(user, libraryIds);
    }

    /** Everything that defines a playlist; shared by create and update. */
    public record PlaylistSpec(String name, UUID libraryId, PlaylistType type, FilterKind filterKind,
                               MediaFilter filter, SortingEnum sorting, SortingOrder sortingOrder) {
    }

    /** How many entries are looked at while collecting distinct covers. */
    private static final int COVER_SCAN = 40;
    /** The mosaic a client draws from them; fewer distinct covers are repeated client-side. */
    private static final int COVER_COUNT = 4;

    /**
     * Up to four <em>distinct</em> cover images taken from the playlist's first entries, so a
     * playlist can be shown as a cover mosaic instead of a bare icon. Manual playlists read their
     * own order; smart ones resolve their filter live (scoped to the caller, like browsing it).
     * Only the first {@value #COVER_SCAN} entries are scanned: a playlist whose opening stretch
     * shares one cover gets one cover, not a table scan.
     */
    @Transactional(readOnly = true)
    public List<ImageEntity> coverImages(Authentication authentication, PlaylistEntity playlist) {
        List<UUID> mediaIds = firstMediaIds(authentication, playlist);
        if (mediaIds.isEmpty()) {
            return List.of();
        }
        LibraryType libraryType = playlist.getLibraryEntity().getLibraryType();
        List<UUID> ownerIds = coverOwnerIds(libraryType, mediaIds);
        Map<UUID, ImageEntity> covers = coversByOwner(libraryType, ownerIds);
        List<ImageEntity> result = new ArrayList<>();
        Set<UUID> seen = new HashSet<>();
        for (UUID ownerId : ownerIds) {
            ImageEntity cover = covers.get(ownerId);
            // Distinct by image: a playlist of one album is one cover, and the client repeats it.
            if (cover != null && seen.add(cover.getId())) {
                result.add(cover);
                if (result.size() == COVER_COUNT) {
                    break;
                }
            }
        }
        return result;
    }

    /** The opening entries of the playlist, in play order. */
    private List<UUID> firstMediaIds(Authentication authentication, PlaylistEntity playlist) {
        if (playlist.getType() == PlaylistType.MANUAL) {
            return playlistItemRepository.findMediaIdsForPlaylistOrdered(playlist.getId(), COVER_SCAN, 0);
        }
        if (playlist.getFilter() == null || playlist.getFilterKind() == null) {
            return List.of();
        }
        UserEntity user = userService.getOrCreateUser(authentication);
        Set<UUID> allowed = libraryAccessService.allowedLibraryIdsForUser(user).orElse(null);
        return filterQueryService.chunkIds(playlist.getFilterKind(), FilterJson.readFilter(playlist.getFilter()),
                playlist.getSorting() != null ? playlist.getSorting() : SortingEnum.NAME,
                playlist.getSortingOrder() != null ? playlist.getSortingOrder() : SortingOrder.ASCENDING,
                new FilterQueryService.FilterScope(allowed, playlist.getLibraryEntity().getId(),
                        user.getExternalId()),
                // Live, like browsing the playlist: no freeze point to respect here.
                new FilterQueryService.ChunkPage(null, null, null, COVER_SCAN, 0));
    }

    /**
     * Whose artwork represents each entry, in entry order and deduplicated: an album for a track,
     * a podcast for its episode, the item itself for movies, episodes and books.
     */
    private List<UUID> coverOwnerIds(LibraryType libraryType, List<UUID> mediaIds) {
        List<UUID> owners = switch (libraryType) {
            case MUSIC -> ordered(mediaIds, trackRepository.findAllById(mediaIds).stream()
                    .collect(Collectors.toMap(TrackEntity::getId, track -> track.getAlbumEntity().getId())));
            case PODCAST -> ordered(mediaIds, podcastEpisodeRepository.findAllById(mediaIds).stream()
                    .collect(Collectors.toMap(PodcastEpisodeEntity::getId,
                            episode -> episode.getPodcastEntity().getId())));
            case MOVIE, SHOW, BOOK -> mediaIds;
            case COMIC -> List.of();
        };
        return owners.stream().distinct().toList();
    }

    /** The owner ids in the entries' own order; entries whose owner vanished are dropped. */
    private static List<UUID> ordered(List<UUID> mediaIds, Map<UUID, UUID> ownerByMediaId) {
        return mediaIds.stream().map(ownerByMediaId::get).filter(Objects::nonNull).toList();
    }

    /**
     * The cover of each owner. Episodes fall back to their show's cover, the same order of
     * preference the clients use for an episode still.
     */
    private Map<UUID, ImageEntity> coversByOwner(LibraryType libraryType, List<UUID> ownerIds) {
        if (ownerIds.isEmpty()) {
            return Map.of();
        }
        List<ImageEntity> images = switch (libraryType) {
            case MUSIC -> imageRepository.findByAlbumEntityIdIn(ownerIds);
            case MOVIE -> imageRepository.findByMovieEntityIdIn(ownerIds);
            case SHOW -> imageRepository.findByEpisodeEntityIdIn(ownerIds);
            case BOOK -> imageRepository.findByBookEntityIdIn(ownerIds);
            case PODCAST -> imageRepository.findByPodcastEntityIdIn(ownerIds);
            case COMIC -> List.of();
        };
        Map<UUID, ImageEntity> byOwner = new HashMap<>();
        for (ImageEntity image : images) {
            byOwner.merge(ownerFor(libraryType, image), image, PlaylistService::preferCover);
        }
        if (libraryType == LibraryType.SHOW) {
            addShowFallbacks(ownerIds, byOwner);
        }
        return byOwner;
    }

    /** An episode without artwork of its own shows its series' cover. */
    private void addShowFallbacks(List<UUID> episodeIds, Map<UUID, ImageEntity> byOwner) {
        List<EpisodeEntity> missing = episodeRepository.findAllById(episodeIds).stream()
                .filter(episode -> !byOwner.containsKey(episode.getId()))
                .toList();
        if (missing.isEmpty()) {
            return;
        }
        Map<UUID, ImageEntity> byShow = new HashMap<>();
        for (ImageEntity image : imageRepository.findByShowEntityIdIn(
                missing.stream().map(episode -> episode.getShowEntity().getId()).distinct().toList())) {
            byShow.merge(image.getShowEntityId(), image, PlaylistService::preferCover);
        }
        for (EpisodeEntity episode : missing) {
            ImageEntity showCover = byShow.get(episode.getShowEntity().getId());
            if (showCover != null) {
                byOwner.put(episode.getId(), showCover);
            }
        }
    }

    private static UUID ownerFor(LibraryType libraryType, ImageEntity image) {
        return switch (libraryType) {
            case MUSIC -> image.getAlbumEntityId();
            case MOVIE -> image.getMovieEntityId();
            case SHOW -> image.getEpisodeEntityId();
            case BOOK -> image.getBookEntityId();
            case PODCAST -> image.getPodcastEntityId();
            case COMIC -> null;
        };
    }

    /** A COVER wins over any other type; between equals the first one found stays. */
    private static ImageEntity preferCover(ImageEntity current, ImageEntity candidate) {
        if (current.getType() == ImageType.COVER || candidate.getType() != ImageType.COVER) {
            return current;
        }
        return candidate;
    }

    /** The caller's own playlist, or empty for an unknown id and for someone else's playlist. */
    @Transactional(readOnly = true)
    public Optional<PlaylistEntity> ownedPlaylist(Authentication authentication, UUID id) {
        return findOwnedPlaylist(authentication, id);
    }

    private Optional<PlaylistEntity> findOwnedPlaylist(Authentication authentication, UUID id) {
        UserEntity user = userService.getOrCreateUser(authentication);
        return playlistRepository.findById(id)
                .filter(playlist -> playlist.getUserEntity().getId().equals(user.getId()));
    }

    // Sonar FP: Lombok @SuperBuilder declares builder() on the subclass itself
    @SuppressWarnings("java:S3252")
    @Transactional
    public PlaylistEntity create(Authentication authentication, PlaylistSpec spec) {
        if (spec.type() == null) {
            throw new IllegalArgumentException("A playlist needs a type");
        }
        if (spec.libraryId() == null) {
            throw new IllegalArgumentException("A playlist needs a library");
        }
        LibraryEntity library = libraryRepository.findById(spec.libraryId())
                .filter(lib -> libraryAccessService.canAccess(lib, authentication))
                .orElseThrow(() -> new IllegalArgumentException("Library not found"));
        if (library.getLibraryType() == LibraryType.COMIC) {
            throw new IllegalArgumentException("Comic libraries cannot have playlists; comics are read, not played");
        }
        PlaylistEntity playlist = PlaylistEntity.builder()
                .userEntity(userService.getOrCreateUser(authentication))
                .libraryEntity(library)
                .type(spec.type())
                .build();
        apply(playlist, spec);
        return playlistRepository.save(playlist);
    }

    /** The library and type are immutable: items belong to the library, and the filter to the type. */
    @Transactional
    public PlaylistEntity update(Authentication authentication, UUID id, PlaylistSpec spec) {
        PlaylistEntity playlist = findOwnedPlaylist(authentication, id)
                .orElseThrow(() -> new IllegalArgumentException(PLAYLIST_NOT_FOUND));
        if (spec.libraryId() != null && !spec.libraryId().equals(playlist.getLibraryEntity().getId())) {
            throw new IllegalArgumentException("A playlist cannot move to another library");
        }
        if (spec.type() != null && spec.type() != playlist.getType()) {
            throw new IllegalArgumentException("A playlist cannot change type");
        }
        apply(playlist, spec);
        return playlistRepository.save(playlist);
    }

    /** Deleting never touches play queues created from the playlist: they carry their own items or pinned filter. */
    @Transactional
    public boolean delete(Authentication authentication, UUID id) {
        PlaylistEntity playlist = findOwnedPlaylist(authentication, id)
                .orElseThrow(() -> new IllegalArgumentException(PLAYLIST_NOT_FOUND));
        playlistRepository.delete(playlist);
        return true;
    }

    private void apply(PlaylistEntity playlist, PlaylistSpec spec) {
        if (spec.name() == null || spec.name().isBlank()) {
            throw new IllegalArgumentException("A playlist needs a name");
        }
        if (playlist.getType() == PlaylistType.MANUAL) {
            if (spec.filter() != null || spec.filterKind() != null || spec.sorting() != null || spec.sortingOrder() != null) {
                throw new IllegalArgumentException("filter, filterKind and sorting only apply to smart playlists");
            }
            playlist.setName(spec.name().trim());
            return;
        }
        FilterKind kind = spec.filterKind();
        if (kind == null || spec.filter() == null) {
            throw new IllegalArgumentException("A smart playlist needs a filter with filterKind");
        }
        FilterKind expected = smartKindFor(playlist.getLibraryEntity().getLibraryType());
        if (kind != expected) {
            throw new IllegalArgumentException("A smart playlist over a " + playlist.getLibraryEntity().getLibraryType()
                    + " library filters on " + expected + " items");
        }
        filterQueryService.validate(kind, spec.filter());
        playlist.setName(spec.name().trim());
        playlist.setFilterKind(kind);
        playlist.setFilter(FilterJson.writeFilter(spec.filter()));
        playlist.setSorting(spec.sorting());
        playlist.setSortingOrder(spec.sortingOrder());
    }

    /**
     * The one playable filter kind of a library type. Smart playlists only exist for the kinds the
     * filter engine can play (tracks, movies, episodes) — BOOK and PODCAST libraries are manual-only.
     */
    private static FilterKind smartKindFor(LibraryType libraryType) {
        return switch (libraryType) {
            case MUSIC -> FilterKind.TRACK;
            case MOVIE -> FilterKind.MOVIE;
            case SHOW -> FilterKind.EPISODE;
            case BOOK, PODCAST, COMIC -> throw new IllegalArgumentException(
                    "Smart playlists are not supported for " + libraryType + " libraries");
        };
    }

    /** The playable item type a MANUAL playlist of this library type holds. */
    public static MediaType itemTypeFor(LibraryType libraryType) {
        return switch (libraryType) {
            case MUSIC -> MediaType.TRACK;
            case MOVIE -> MediaType.MOVIE;
            case SHOW -> MediaType.EPISODE;
            case PODCAST -> MediaType.PODCAST_EPISODE;
            case BOOK -> MediaType.BOOK;
            case COMIC -> throw new IllegalArgumentException("Comic libraries cannot have playlists");
        };
    }

    /**
     * Adds a media item of the playlist's library, at the end (afterItemId null) or directly
     * after another entry. The item kind is fully determined by the library type; an item from
     * another library is rejected as not-found. Duplicates are allowed.
     */
    // Sonar FP: Lombok @SuperBuilder declares builder() on the subclass itself
    @SuppressWarnings("java:S3252")
    @Transactional
    public PlaylistEntity addItem(Authentication authentication, UUID playlistId, UUID mediaId, UUID afterItemId) {
        PlaylistEntity playlist = editableManualPlaylist(authentication, playlistId);
        MediaType mediaType = itemTypeFor(playlist.getLibraryEntity().getLibraryType());
        LibraryEntity mediaLibrary = libraryOfMedia(mediaType, mediaId)
                .orElseThrow(() -> new IllegalArgumentException("Media item not found"));
        if (!mediaLibrary.getId().equals(playlist.getLibraryEntity().getId())) {
            // Deny-as-not-found: the caller learns nothing about other libraries' contents.
            throw new IllegalArgumentException("Media item not found");
        }

        BigDecimal position;
        if (afterItemId == null) {
            position = GapPositions.nextPosition(GapPositions.maxPosition(playlist.getItems()));
        } else {
            position = GapPositions.targetPosition(playlist.getItems(), afterItemId, null);
            if (position == null) {
                GapPositions.rebalance(playlist.getItems());
                position = GapPositions.targetPosition(playlist.getItems(), afterItemId, null);
            }
        }
        PlaylistItemEntity item = PlaylistItemEntity.builder()
                .playlistEntity(playlist)
                .type(mediaType)
                .position(position)
                .build();
        switch (mediaType) {
            case MOVIE -> item.setMovieEntityId(mediaId);
            case EPISODE -> item.setEpisodeEntityId(mediaId);
            case TRACK -> item.setTrackEntityId(mediaId);
            case BOOK -> item.setBookEntityId(mediaId);
            case PODCAST_EPISODE -> item.setPodcastEpisodeEntityId(mediaId);
            default -> throw new IllegalArgumentException("Media type cannot be playlisted: " + mediaType);
        }
        addItemOnce(playlist, item);
        sortItems(playlist);
        return playlistRepository.save(playlist);
    }

    /**
     * Moves an entry to directly after another entry, or to the front when afterItemId is null.
     * Same gap-based mechanics as moving a play-queue item.
     */
    @Transactional
    public PlaylistEntity moveItem(Authentication authentication, UUID playlistId, UUID playlistItemId, UUID afterItemId) {
        PlaylistEntity playlist = editableManualPlaylist(authentication, playlistId);
        if (playlistItemId.equals(afterItemId)) {
            throw new IllegalArgumentException("Cannot move an item after itself");
        }
        PlaylistItemEntity moving = playlist.getItems().stream()
                .filter(item -> item.getId().equals(playlistItemId))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Item not in playlist"));

        BigDecimal newPosition = GapPositions.targetPosition(playlist.getItems(), afterItemId, playlistItemId);
        if (newPosition == null) {
            GapPositions.rebalance(playlist.getItems());
            newPosition = GapPositions.targetPosition(playlist.getItems(), afterItemId, playlistItemId);
        }
        moving.setPosition(newPosition);
        sortItems(playlist);
        return playlistRepository.save(playlist);
    }

    @Transactional
    public PlaylistEntity removeItem(Authentication authentication, UUID playlistId, UUID playlistItemId) {
        PlaylistEntity playlist = editableManualPlaylist(authentication, playlistId);
        boolean removed = playlist.getItems().removeIf(item -> item.getId().equals(playlistItemId));
        if (!removed) {
            throw new IllegalArgumentException("Item not in playlist");
        }
        return playlistRepository.save(playlist);
    }

    private PlaylistEntity editableManualPlaylist(Authentication authentication, UUID playlistId) {
        PlaylistEntity playlist = findOwnedPlaylist(authentication, playlistId)
                .orElseThrow(() -> new IllegalArgumentException(PLAYLIST_NOT_FOUND));
        if (playlist.getType() != PlaylistType.MANUAL) {
            throw new IllegalArgumentException("A smart playlist's items come from its filter");
        }
        return playlist;
    }

    /**
     * The library a candidate playlist item belongs to. BOOK is looked up directly:
     * {@link MediaLibraryResolver#ofPlayQueueItem} has no book case (books never sit in a
     * play queue), but a BOOK library's playlist stores whole books.
     */
    private Optional<LibraryEntity> libraryOfMedia(MediaType mediaType, UUID mediaId) {
        return switch (mediaType) {
            case MOVIE -> movieRepository.findById(mediaId).map(MovieEntity::getLibraryEntity);
            case EPISODE -> episodeRepository.findById(mediaId).map(episode -> episode.getShowEntity().getLibraryEntity());
            case TRACK -> trackRepository.findById(mediaId).map(track -> track.getAlbumEntity().getLibraryEntity());
            case BOOK -> bookRepository.findById(mediaId).map(BookEntity::getLibraryEntity);
            case PODCAST_EPISODE -> podcastEpisodeRepository.findById(mediaId).map(episode -> episode.getPodcastEntity().getLibraryEntity());
            default -> Optional.empty();
        };
    }

    /**
     * Hibernate association management may already have inserted the item into the playlist's
     * collection when its owning side was set; only add it when absent. Checks by identity:
     * BaseEntity's id-based equals cannot recognize an unsaved (id-less) item.
     */
    private void addItemOnce(PlaylistEntity playlist, PlaylistItemEntity item) {
        for (PlaylistItemEntity existing : playlist.getItems()) {
            if (existing == item) {
                return;
            }
        }
        playlist.getItems().add(item);
    }

    private void sortItems(PlaylistEntity playlist) {
        playlist.getItems().sort(Comparator.comparing(PlaylistItemEntity::getPosition));
    }
}
