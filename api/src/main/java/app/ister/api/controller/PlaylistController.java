package app.ister.api.controller;

import app.ister.api.dto.PlaylistInput;
import app.ister.core.entity.BookEntity;
import app.ister.core.entity.EpisodeEntity;
import app.ister.core.entity.ImageEntity;
import app.ister.core.entity.LibraryEntity;
import app.ister.core.entity.MovieEntity;
import app.ister.core.entity.PlaylistEntity;
import app.ister.core.entity.PlaylistItemEntity;
import app.ister.core.entity.PodcastEpisodeEntity;
import app.ister.core.entity.TrackEntity;
import app.ister.core.enums.LibraryType;
import app.ister.core.enums.PlaylistType;
import app.ister.core.filter.FilterJson;
import app.ister.core.filter.MediaFilter;
import app.ister.core.repository.BookRepository;
import app.ister.core.repository.EpisodeRepository;
import app.ister.core.repository.MovieRepository;
import app.ister.core.repository.PlaylistItemRepository;
import app.ister.core.repository.PodcastEpisodeRepository;
import app.ister.core.repository.TrackRepository;
import app.ister.core.service.PlaylistService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.graphql.data.method.annotation.Argument;
import org.springframework.graphql.data.method.annotation.BatchMapping;
import org.springframework.graphql.data.method.annotation.MutationMapping;
import org.springframework.graphql.data.method.annotation.QueryMapping;
import org.springframework.graphql.data.method.annotation.SchemaMapping;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

@Slf4j
@Controller
@RequiredArgsConstructor
public class PlaylistController {

    private final PlaylistService playlistService;
    private final PlaylistItemRepository playlistItemRepository;
    private final MovieRepository movieRepository;
    private final EpisodeRepository episodeRepository;
    private final TrackRepository trackRepository;
    private final BookRepository bookRepository;
    private final PodcastEpisodeRepository podcastEpisodeRepository;

    @PreAuthorize("hasRole('user')")
    @QueryMapping
    public List<PlaylistEntity> playlists(@Argument UUID libraryId, Authentication authentication) {
        return playlistService.playlists(authentication, libraryId);
    }

    @PreAuthorize("hasRole('user')")
    @QueryMapping
    public Optional<PlaylistEntity> playlistById(@Argument UUID id, Authentication authentication) {
        return playlistService.ownedPlaylist(authentication, id);
    }

    @PreAuthorize("hasRole('user')")
    @MutationMapping
    public PlaylistEntity createPlaylist(@Argument PlaylistInput input, Authentication authentication) {
        return playlistService.create(authentication, toSpec(input));
    }

    @PreAuthorize("hasRole('user')")
    @MutationMapping
    public PlaylistEntity updatePlaylist(@Argument UUID id, @Argument PlaylistInput input,
                                         Authentication authentication) {
        return playlistService.update(authentication, id, toSpec(input));
    }

    private PlaylistService.PlaylistSpec toSpec(PlaylistInput input) {
        return new PlaylistService.PlaylistSpec(input.name(), input.libraryId(), input.type(),
                input.filterKind(), input.filter(), input.sorting(), input.sortingOrder());
    }

    @PreAuthorize("hasRole('user')")
    @MutationMapping
    public boolean deletePlaylist(@Argument UUID id, Authentication authentication) {
        return playlistService.delete(authentication, id);
    }

    @PreAuthorize("hasRole('user')")
    @MutationMapping
    public PlaylistEntity addPlaylistItem(@Argument UUID playlistId, @Argument UUID mediaId,
                                          @Argument UUID afterPlaylistItemId, Authentication authentication) {
        return playlistService.addItem(authentication, playlistId, mediaId, afterPlaylistItemId);
    }

    @PreAuthorize("hasRole('user')")
    @MutationMapping
    public PlaylistEntity movePlaylistItem(@Argument UUID playlistId, @Argument UUID playlistItemId,
                                           @Argument UUID afterPlaylistItemId, Authentication authentication) {
        return playlistService.moveItem(authentication, playlistId, playlistItemId, afterPlaylistItemId);
    }

    @PreAuthorize("hasRole('user')")
    @MutationMapping
    public PlaylistEntity removePlaylistItem(@Argument UUID playlistId, @Argument UUID playlistItemId,
                                             Authentication authentication) {
        return playlistService.removeItem(authentication, playlistId, playlistItemId);
    }

    /** The calling user's playlists per library, in one query for all selected libraries. */
    @PreAuthorize("hasRole('user')")
    @BatchMapping(typeName = "Library", field = "playlists")
    public Map<LibraryEntity, List<PlaylistEntity>> libraryPlaylists(
            List<LibraryEntity> libraries, Authentication authentication) {
        Map<UUID, List<PlaylistEntity>> byLibraryId = playlistService
                .playlistsInLibraries(authentication, libraries.stream().map(LibraryEntity::getId).toList())
                .stream()
                .collect(Collectors.groupingBy(playlist -> playlist.getLibraryEntity().getId()));
        return libraries.stream().collect(Collectors.toMap(
                library -> library,
                library -> byLibraryId.getOrDefault(library.getId(), List.of())));
    }

    @SchemaMapping(typeName = "Playlist", field = "libraryId")
    public UUID libraryId(PlaylistEntity playlist) {
        return playlist.getLibraryEntity().getId();
    }

    @SchemaMapping(typeName = "Playlist", field = "libraryType")
    public LibraryType libraryType(PlaylistEntity playlist) {
        return playlist.getLibraryEntity().getLibraryType();
    }

    /** MANUAL only; a SMART playlist's size follows from its filter, so it resolves null. */
    @SchemaMapping(typeName = "Playlist", field = "itemCount")
    public Integer itemCount(PlaylistEntity playlist) {
        if (playlist.getType() != PlaylistType.MANUAL) {
            return null;
        }
        return (int) playlistItemRepository.countByPlaylistEntityId(playlist.getId());
    }

    /** Up to four distinct covers of the first entries, for the client's mosaic tile. */
    @PreAuthorize("hasRole('user')")
    @SchemaMapping(typeName = "Playlist", field = "coverImages")
    public List<ImageEntity> coverImages(PlaylistEntity playlist, Authentication authentication) {
        return playlistService.coverImages(authentication, playlist);
    }

    /** The stored JSON, back as the typed FilterGroup tree with lists normalized to non-null. */
    @SchemaMapping(typeName = "Playlist", field = "filter")
    public MediaFilter filter(PlaylistEntity playlist) {
        if (playlist.getFilter() == null) {
            return null;
        }
        return normalize(FilterJson.readFilter(playlist.getFilter()));
    }

    private MediaFilter normalize(MediaFilter filter) {
        return new MediaFilter(filter.match(), filter.conditionsOrEmpty(),
                filter.groupsOrEmpty().stream().map(this::normalize).toList(), filter.limit());
    }

    @SchemaMapping(typeName = "PlaylistItem", field = "movie")
    public Optional<MovieEntity> playlistItemMovie(PlaylistItemEntity item) {
        return item.getMovieEntityId() == null ? Optional.empty()
                : movieRepository.findById(item.getMovieEntityId());
    }

    @SchemaMapping(typeName = "PlaylistItem", field = "episode")
    public Optional<EpisodeEntity> playlistItemEpisode(PlaylistItemEntity item) {
        return item.getEpisodeEntityId() == null ? Optional.empty()
                : episodeRepository.findById(item.getEpisodeEntityId());
    }

    @SchemaMapping(typeName = "PlaylistItem", field = "track")
    public Optional<TrackEntity> playlistItemTrack(PlaylistItemEntity item) {
        return item.getTrackEntityId() == null ? Optional.empty()
                : trackRepository.findById(item.getTrackEntityId());
    }

    @SchemaMapping(typeName = "PlaylistItem", field = "book")
    public Optional<BookEntity> playlistItemBook(PlaylistItemEntity item) {
        return item.getBookEntityId() == null ? Optional.empty()
                : bookRepository.findById(item.getBookEntityId());
    }

    @SchemaMapping(typeName = "PlaylistItem", field = "podcastEpisode")
    public Optional<PodcastEpisodeEntity> playlistItemPodcastEpisode(PlaylistItemEntity item) {
        return item.getPodcastEpisodeEntityId() == null ? Optional.empty()
                : podcastEpisodeRepository.findById(item.getPodcastEpisodeEntityId());
    }
}
