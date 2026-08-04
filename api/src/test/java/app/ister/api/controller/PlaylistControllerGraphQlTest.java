package app.ister.api.controller;

import app.ister.core.entity.LibraryEntity;
import app.ister.core.entity.PlaylistEntity;
import app.ister.core.entity.PlaylistItemEntity;
import app.ister.core.entity.TrackEntity;
import app.ister.core.enums.LibraryType;
import app.ister.core.enums.MediaType;
import app.ister.core.enums.PlaylistType;
import app.ister.core.filter.FilterJson;
import app.ister.core.filter.FilterKind;
import app.ister.core.filter.FilterMatch;
import app.ister.core.filter.MediaFilter;
import app.ister.core.repository.BookRepository;
import app.ister.core.repository.EpisodeRepository;
import app.ister.core.repository.MovieRepository;
import app.ister.core.repository.PlaylistItemRepository;
import app.ister.core.repository.PodcastEpisodeRepository;
import app.ister.core.repository.TrackRepository;
import app.ister.core.service.PlaylistService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.graphql.test.autoconfigure.GraphQlTest;
import org.springframework.graphql.test.tester.GraphQlTester;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Schema-wiring test for playlists: the queries/mutations resolve, the Playlist field resolvers
 * (libraryId/libraryType/itemCount/filter/items) map the entity, and PlaylistItem resolves its
 * media. Business rules live in PlaylistServiceTest; this proves the GraphQL surface.
 */
@GraphQlTest({PlaylistController.class, LibraryDiscoverController.class})
class PlaylistControllerGraphQlTest {

    @Autowired
    private GraphQlTester graphQlTester;

    @MockitoBean
    private PlaylistService playlistService;

    @MockitoBean
    private PlaylistItemRepository playlistItemRepository;

    @MockitoBean
    private MovieRepository movieRepository;

    @MockitoBean
    private EpisodeRepository episodeRepository;

    @MockitoBean
    private TrackRepository trackRepository;

    @MockitoBean
    private BookRepository bookRepository;

    @MockitoBean
    private PodcastEpisodeRepository podcastEpisodeRepository;

    // Dependencies of LibraryDiscoverController, included for the Library.playlists batch test.
    @MockitoBean
    private app.ister.core.repository.LibraryRepository libraryRepository;

    @MockitoBean
    private app.ister.core.service.LibraryAccessService libraryAccessService;

    @MockitoBean
    private app.ister.core.repository.ShowRepository showRepository;

    @MockitoBean
    private app.ister.core.repository.AlbumRepository albumRepository;

    @MockitoBean
    private app.ister.core.repository.SeriesRepository seriesRepository;

    @MockitoBean
    private app.ister.core.repository.PodcastRepository podcastRepository;

    private final LibraryEntity library = LibraryEntity.builder()
            .id(UUID.randomUUID()).libraryType(LibraryType.MUSIC).name("Music").build();

    @BeforeEach
    void authenticateAsUser() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("test-user", null,
                        List.of(new SimpleGrantedAuthority("ROLE_user"))));
    }

    @AfterEach
    void clearAuthentication() {
        SecurityContextHolder.clearContext();
    }

    private PlaylistEntity manualPlaylist() {
        PlaylistEntity playlist = PlaylistEntity.builder()
                .libraryEntity(library)
                .name("Roadtrip")
                .type(PlaylistType.MANUAL)
                .build();
        playlist.setId(UUID.randomUUID());
        return playlist;
    }

    @Test
    void playlistsResolveWithLibraryFieldsAndItemCount() {
        PlaylistEntity playlist = manualPlaylist();
        when(playlistService.playlists(any(), isNull())).thenReturn(List.of(playlist));
        when(playlistItemRepository.countByPlaylistEntityId(playlist.getId())).thenReturn(3L);

        graphQlTester.document("""
                        query { playlists { id name type libraryId libraryType itemCount filterKind filter { match } } }""")
                .execute()
                .path("playlists[0].name").entity(String.class).isEqualTo("Roadtrip")
                .path("playlists[0].type").entity(String.class).isEqualTo("MANUAL")
                .path("playlists[0].libraryId").entity(String.class).isEqualTo(library.getId().toString())
                .path("playlists[0].libraryType").entity(String.class).isEqualTo("MUSIC")
                .path("playlists[0].itemCount").entity(Integer.class).isEqualTo(3)
                .path("playlists[0].filterKind").valueIsNull()
                .path("playlists[0].filter").valueIsNull();

        verify(playlistItemRepository).countByPlaylistEntityId(playlist.getId());
    }

    @Test
    void smartPlaylistResolvesItsFilterAndNullItemCount() {
        PlaylistEntity playlist = manualPlaylist();
        playlist.setType(PlaylistType.SMART);
        playlist.setFilterKind(FilterKind.TRACK);
        playlist.setFilter(FilterJson.writeFilter(new MediaFilter(FilterMatch.ALL, null, null, 25)));
        when(playlistService.ownedPlaylist(any(), eq(playlist.getId()))).thenReturn(Optional.of(playlist));

        graphQlTester.document("""
                        query($id: ID!) { playlistById(id: $id) {
                            itemCount filterKind filter { match limit conditions { field } groups { match } } } }""")
                .variable("id", playlist.getId())
                .execute()
                .path("playlistById.itemCount").valueIsNull()
                .path("playlistById.filterKind").entity(String.class).isEqualTo("TRACK")
                .path("playlistById.filter.match").entity(String.class).isEqualTo("ALL")
                .path("playlistById.filter.limit").entity(Integer.class).isEqualTo(25);

        verify(playlistService).ownedPlaylist(any(), eq(playlist.getId()));
    }

    @Test
    void playlistByIdResolvesNullForUnknownOrForeignIds() {
        UUID unknown = UUID.randomUUID();
        when(playlistService.ownedPlaylist(any(), eq(unknown))).thenReturn(Optional.empty());

        graphQlTester.document("query($id: ID!) { playlistById(id: $id) { id } }")
                .variable("id", unknown)
                .execute()
                .path("playlistById").valueIsNull();

        verify(playlistService).ownedPlaylist(any(), eq(unknown));
    }

    @Test
    void playlistItemsResolveTheirMedia() {
        PlaylistEntity playlist = manualPlaylist();
        TrackEntity track = TrackEntity.builder().build();
        track.setId(UUID.randomUUID());
        PlaylistItemEntity item = PlaylistItemEntity.builder()
                .playlistEntity(playlist)
                .type(MediaType.TRACK)
                .position(new BigDecimal("1000"))
                .build();
        item.setId(UUID.randomUUID());
        item.setTrackEntityId(track.getId());
        playlist.getItems().add(item);
        when(playlistService.ownedPlaylist(any(), eq(playlist.getId()))).thenReturn(Optional.of(playlist));
        when(trackRepository.findById(track.getId())).thenReturn(Optional.of(track));

        graphQlTester.document("""
                        query($id: ID!) { playlistById(id: $id) {
                            items { id position type track { id } movie { id } } } }""")
                .variable("id", playlist.getId())
                .execute()
                .path("playlistById.items[0].type").entity(String.class).isEqualTo("TRACK")
                .path("playlistById.items[0].track.id").entity(String.class).isEqualTo(track.getId().toString())
                .path("playlistById.items[0].movie").valueIsNull();

        verify(trackRepository).findById(track.getId());
    }

    @Test
    void createPlaylistMapsTheInputToASpec() {
        PlaylistEntity playlist = manualPlaylist();
        when(playlistService.create(any(), any())).thenReturn(playlist);

        graphQlTester.document("""
                        mutation($input: PlaylistInput!) { createPlaylist(input: $input) { id name } }""")
                .variable("input", java.util.Map.of(
                        "name", "Roadtrip", "libraryId", library.getId().toString(), "type", "MANUAL"))
                .execute()
                .path("createPlaylist.name").entity(String.class).isEqualTo("Roadtrip");

        verify(playlistService).create(any(), eq(new PlaylistService.PlaylistSpec(
                "Roadtrip", library.getId(), PlaylistType.MANUAL, null, null, null, null)));
    }

    @Test
    void itemMutationsDelegateToTheService() {
        PlaylistEntity playlist = manualPlaylist();
        UUID mediaId = UUID.randomUUID();
        UUID itemId = UUID.randomUUID();
        when(playlistService.addItem(any(), eq(playlist.getId()), eq(mediaId), isNull())).thenReturn(playlist);
        when(playlistService.moveItem(any(), eq(playlist.getId()), eq(itemId), isNull())).thenReturn(playlist);
        when(playlistService.removeItem(any(), eq(playlist.getId()), eq(itemId))).thenReturn(playlist);
        when(playlistService.delete(any(), eq(playlist.getId()))).thenReturn(true);

        graphQlTester.document("mutation($p: ID!, $m: ID!) { addPlaylistItem(playlistId: $p, mediaId: $m) { id } }")
                .variable("p", playlist.getId()).variable("m", mediaId)
                .execute().path("addPlaylistItem.id").hasValue();
        graphQlTester.document("mutation($p: ID!, $i: ID!) { movePlaylistItem(playlistId: $p, playlistItemId: $i) { id } }")
                .variable("p", playlist.getId()).variable("i", itemId)
                .execute().path("movePlaylistItem.id").hasValue();
        graphQlTester.document("mutation($p: ID!, $i: ID!) { removePlaylistItem(playlistId: $p, playlistItemId: $i) { id } }")
                .variable("p", playlist.getId()).variable("i", itemId)
                .execute().path("removePlaylistItem.id").hasValue();
        graphQlTester.document("mutation($p: ID!) { deletePlaylist(id: $p) }")
                .variable("p", playlist.getId())
                .execute().path("deletePlaylist").entity(Boolean.class).isEqualTo(true);

        verify(playlistService).addItem(any(), eq(playlist.getId()), eq(mediaId), isNull());
        verify(playlistService).moveItem(any(), eq(playlist.getId()), eq(itemId), isNull());
        verify(playlistService).removeItem(any(), eq(playlist.getId()), eq(itemId));
        verify(playlistService).delete(any(), eq(playlist.getId()));
    }

    @Test
    void libraryPlaylistsBatchResolvesPerLibrary() {
        PlaylistEntity playlist = manualPlaylist();
        when(libraryRepository.findById(library.getId())).thenReturn(Optional.of(library));
        when(libraryAccessService.canAccess(eq(library), any())).thenReturn(true);
        when(playlistService.playlistsInLibraries(any(), eq(List.of(library.getId()))))
                .thenReturn(List.of(playlist));

        graphQlTester.document("""
                        query($id: ID!) { libraryById(id: $id) { id playlists { id name } } }""")
                .variable("id", library.getId())
                .execute()
                .path("libraryById.playlists[0].name").entity(String.class).isEqualTo("Roadtrip");

        verify(playlistService).playlistsInLibraries(any(), eq(List.of(library.getId())));
    }
}
