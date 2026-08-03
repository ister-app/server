package app.ister.core.service;

import app.ister.core.entity.LibraryEntity;
import app.ister.core.entity.PlaylistEntity;
import app.ister.core.entity.PlaylistItemEntity;
import app.ister.core.entity.UserEntity;
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
import app.ister.core.repository.LibraryRepository;
import app.ister.core.repository.MovieRepository;
import app.ister.core.repository.PlaylistRepository;
import app.ister.core.repository.PodcastEpisodeRepository;
import app.ister.core.repository.TrackRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

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

    private final PlaylistRepository playlistRepository;
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
                .orElseThrow(() -> new IllegalArgumentException("Playlist not found"));
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
                .orElseThrow(() -> new IllegalArgumentException("Playlist not found"));
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
                .orElseThrow(() -> new IllegalArgumentException("Playlist not found"));
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
            case MOVIE -> movieRepository.findById(mediaId).map(movie -> movie.getLibraryEntity());
            case EPISODE -> episodeRepository.findById(mediaId).map(episode -> episode.getShowEntity().getLibraryEntity());
            case TRACK -> trackRepository.findById(mediaId).map(track -> track.getAlbumEntity().getLibraryEntity());
            case BOOK -> bookRepository.findById(mediaId).map(book -> book.getLibraryEntity());
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
