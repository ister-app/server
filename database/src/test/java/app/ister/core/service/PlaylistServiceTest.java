package app.ister.core.service;

import app.ister.core.entity.AlbumEntity;
import app.ister.core.entity.LibraryEntity;
import app.ister.core.entity.MovieEntity;
import app.ister.core.entity.PlaylistEntity;
import app.ister.core.entity.PlaylistItemEntity;
import app.ister.core.entity.TrackEntity;
import app.ister.core.entity.UserEntity;
import app.ister.core.enums.LibraryType;
import app.ister.core.enums.MediaType;
import app.ister.core.enums.PlaylistType;
import app.ister.core.filter.FilterKind;
import app.ister.core.filter.FilterMatch;
import app.ister.core.filter.MediaFilter;
import app.ister.core.repository.BookRepository;
import app.ister.core.repository.EpisodeRepository;
import app.ister.core.repository.LibraryRepository;
import app.ister.core.repository.MovieRepository;
import app.ister.core.repository.PlaylistRepository;
import app.ister.core.repository.PodcastEpisodeRepository;
import app.ister.core.repository.TrackRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.Authentication;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PlaylistServiceTest {

    @InjectMocks
    private PlaylistService subject;

    @Mock
    private PlaylistRepository playlistRepository;

    @Mock
    private LibraryRepository libraryRepository;

    @Mock
    private LibraryAccessService libraryAccessService;

    @Mock
    private FilterQueryService filterQueryService;

    @Mock
    private UserService userService;

    @Mock
    private MovieRepository movieRepository;

    @Mock
    private EpisodeRepository episodeRepository;

    @Mock
    private TrackRepository trackRepository;

    @Mock
    private BookRepository bookRepository;

    @Mock
    private PodcastEpisodeRepository podcastEpisodeRepository;

    @Mock
    private Authentication authentication;

    private UserEntity user;
    private LibraryEntity musicLibrary;

    @BeforeEach
    void setUp() {
        user = UserEntity.builder().id(UUID.randomUUID()).build();
        musicLibrary = LibraryEntity.builder().id(UUID.randomUUID()).libraryType(LibraryType.MUSIC).build();
        lenient().when(userService.getOrCreateUser(authentication)).thenReturn(user);
        lenient().when(playlistRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    private void mockAccessibleLibrary(LibraryEntity library) {
        when(libraryRepository.findById(library.getId())).thenReturn(Optional.of(library));
        when(libraryAccessService.canAccess(library, authentication)).thenReturn(true);
    }

    private PlaylistEntity ownedManualPlaylist(LibraryEntity library) {
        PlaylistEntity playlist = PlaylistEntity.builder()
                .userEntity(user)
                .libraryEntity(library)
                .name("Mine")
                .type(PlaylistType.MANUAL)
                .build();
        playlist.setId(UUID.randomUUID());
        when(playlistRepository.findById(playlist.getId())).thenReturn(Optional.of(playlist));
        return playlist;
    }

    private static MediaFilter emptyFilter() {
        return new MediaFilter(FilterMatch.ALL, List.of(), List.of(), null);
    }

    private static PlaylistService.PlaylistSpec manualSpec(String name, UUID libraryId) {
        return new PlaylistService.PlaylistSpec(name, libraryId, PlaylistType.MANUAL, null, null, null, null);
    }

    @Test
    void createManualPlaylist() {
        mockAccessibleLibrary(musicLibrary);

        PlaylistEntity result = subject.create(authentication, manualSpec("Roadtrip", musicLibrary.getId()));

        assertEquals("Roadtrip", result.getName());
        assertEquals(PlaylistType.MANUAL, result.getType());
        assertEquals(musicLibrary, result.getLibraryEntity());
        assertEquals(user, result.getUserEntity());
    }

    @Test
    void createRejectsABlankName() {
        mockAccessibleLibrary(musicLibrary);
        PlaylistService.PlaylistSpec spec = manualSpec("  ", musicLibrary.getId());
        assertThrows(IllegalArgumentException.class, () -> subject.create(authentication, spec));
    }

    @Test
    void createRejectsAMissingLibrary() {
        PlaylistService.PlaylistSpec spec = manualSpec("Roadtrip", null);
        assertThrows(IllegalArgumentException.class, () -> subject.create(authentication, spec));
    }

    @Test
    void createRejectsAnInaccessibleLibraryAsNotFound() {
        when(libraryRepository.findById(musicLibrary.getId())).thenReturn(Optional.of(musicLibrary));
        when(libraryAccessService.canAccess(musicLibrary, authentication)).thenReturn(false);
        PlaylistService.PlaylistSpec spec = manualSpec("Roadtrip", musicLibrary.getId());
        assertThrows(IllegalArgumentException.class, () -> subject.create(authentication, spec));
    }

    @Test
    void createRejectsAComicLibrary() {
        LibraryEntity comics = LibraryEntity.builder().id(UUID.randomUUID()).libraryType(LibraryType.COMIC).build();
        mockAccessibleLibrary(comics);
        PlaylistService.PlaylistSpec spec = manualSpec("Comics", comics.getId());
        assertThrows(IllegalArgumentException.class, () -> subject.create(authentication, spec));
    }

    @Test
    void createRejectsFilterFieldsOnAManualPlaylist() {
        mockAccessibleLibrary(musicLibrary);
        PlaylistService.PlaylistSpec spec = new PlaylistService.PlaylistSpec("Roadtrip", musicLibrary.getId(),
                PlaylistType.MANUAL, FilterKind.TRACK, emptyFilter(), null, null);
        assertThrows(IllegalArgumentException.class, () -> subject.create(authentication, spec));
    }

    @Test
    void createSmartPlaylistValidatesItsFilter() {
        mockAccessibleLibrary(musicLibrary);

        PlaylistEntity result = subject.create(authentication, new PlaylistService.PlaylistSpec(
                "Never played", musicLibrary.getId(), PlaylistType.SMART, FilterKind.TRACK, emptyFilter(), null, null));

        assertEquals(PlaylistType.SMART, result.getType());
        assertEquals(FilterKind.TRACK, result.getFilterKind());
        verify(filterQueryService).validate(eq(FilterKind.TRACK), any());
    }

    @Test
    void createSmartPlaylistRejectsAKindNotMatchingTheLibraryType() {
        mockAccessibleLibrary(musicLibrary);
        PlaylistService.PlaylistSpec spec = new PlaylistService.PlaylistSpec("Movies in music", musicLibrary.getId(),
                PlaylistType.SMART, FilterKind.MOVIE, emptyFilter(), null, null);
        assertThrows(IllegalArgumentException.class, () -> subject.create(authentication, spec));
    }

    @Test
    void createSmartPlaylistRejectsABookLibrary() {
        LibraryEntity books = LibraryEntity.builder().id(UUID.randomUUID()).libraryType(LibraryType.BOOK).build();
        mockAccessibleLibrary(books);
        PlaylistService.PlaylistSpec spec = new PlaylistService.PlaylistSpec("Smart books", books.getId(),
                PlaylistType.SMART, FilterKind.TRACK, emptyFilter(), null, null);
        assertThrows(IllegalArgumentException.class, () -> subject.create(authentication, spec));
    }

    @Test
    void ownedPlaylistDeniesSomeoneElsesAsNotFound() {
        PlaylistEntity other = PlaylistEntity.builder()
                .userEntity(UserEntity.builder().id(UUID.randomUUID()).build())
                .libraryEntity(musicLibrary)
                .name("Not mine")
                .type(PlaylistType.MANUAL)
                .build();
        other.setId(UUID.randomUUID());
        when(playlistRepository.findById(other.getId())).thenReturn(Optional.of(other));

        assertTrue(subject.ownedPlaylist(authentication, other.getId()).isEmpty());
        assertThrows(IllegalArgumentException.class, () -> subject.delete(authentication, other.getId()));
        assertThrows(IllegalArgumentException.class,
                () -> subject.addItem(authentication, other.getId(), UUID.randomUUID(), null));
    }

    @Test
    void updateRejectsChangingTheLibraryOrType() {
        PlaylistEntity playlist = ownedManualPlaylist(musicLibrary);
        PlaylistService.PlaylistSpec otherLibrary = manualSpec("Mine", UUID.randomUUID());
        assertThrows(IllegalArgumentException.class, () -> subject.update(authentication, playlist.getId(), otherLibrary));

        PlaylistService.PlaylistSpec otherType = new PlaylistService.PlaylistSpec("Mine", musicLibrary.getId(),
                PlaylistType.SMART, FilterKind.TRACK, emptyFilter(), null, null);
        assertThrows(IllegalArgumentException.class, () -> subject.update(authentication, playlist.getId(), otherType));
    }

    @Test
    void updateRenamesAManualPlaylist() {
        PlaylistEntity playlist = ownedManualPlaylist(musicLibrary);

        PlaylistEntity result = subject.update(authentication, playlist.getId(), manualSpec("Renamed", null));

        assertEquals("Renamed", result.getName());
    }

    private TrackEntity trackIn(LibraryEntity library, UUID trackId) {
        TrackEntity track = TrackEntity.builder()
                .albumEntity(AlbumEntity.builder().libraryEntity(library).build())
                .build();
        track.setId(trackId);
        when(trackRepository.findById(trackId)).thenReturn(Optional.of(track));
        return track;
    }

    @Test
    void addItemAppendsATrackOfTheSameLibrary() {
        PlaylistEntity playlist = ownedManualPlaylist(musicLibrary);
        UUID trackId = UUID.randomUUID();
        trackIn(musicLibrary, trackId);

        PlaylistEntity result = subject.addItem(authentication, playlist.getId(), trackId, null);

        assertEquals(1, result.getItems().size());
        PlaylistItemEntity item = result.getItems().getFirst();
        assertEquals(MediaType.TRACK, item.getType());
        assertEquals(trackId, item.getTrackEntityId());
        assertEquals(new BigDecimal("1000"), item.getPosition());
    }

    @Test
    void addItemRejectsAnItemFromAnotherLibrary() {
        PlaylistEntity playlist = ownedManualPlaylist(musicLibrary);
        LibraryEntity otherLibrary = LibraryEntity.builder().id(UUID.randomUUID()).libraryType(LibraryType.MUSIC).build();
        UUID trackId = UUID.randomUUID();
        trackIn(otherLibrary, trackId);

        assertThrows(IllegalArgumentException.class,
                () -> subject.addItem(authentication, playlist.getId(), trackId, null));
    }

    @Test
    void addItemRejectsAMissingMediaItem() {
        PlaylistEntity playlist = ownedManualPlaylist(musicLibrary);
        UUID unknown = UUID.randomUUID();
        when(trackRepository.findById(unknown)).thenReturn(Optional.empty());

        assertThrows(IllegalArgumentException.class,
                () -> subject.addItem(authentication, playlist.getId(), unknown, null));
    }

    @Test
    void addItemRejectsASmartPlaylist() {
        PlaylistEntity playlist = PlaylistEntity.builder()
                .userEntity(user)
                .libraryEntity(musicLibrary)
                .name("Smart")
                .type(PlaylistType.SMART)
                .build();
        playlist.setId(UUID.randomUUID());
        when(playlistRepository.findById(playlist.getId())).thenReturn(Optional.of(playlist));

        assertThrows(IllegalArgumentException.class,
                () -> subject.addItem(authentication, playlist.getId(), UUID.randomUUID(), null));
    }

    @Test
    void addItemUsesTheLibraryTypesItemKind() {
        LibraryEntity movieLibrary = LibraryEntity.builder().id(UUID.randomUUID()).libraryType(LibraryType.MOVIE).build();
        PlaylistEntity playlist = ownedManualPlaylist(movieLibrary);
        UUID movieId = UUID.randomUUID();
        MovieEntity movie = MovieEntity.builder().libraryEntity(movieLibrary).build();
        movie.setId(movieId);
        when(movieRepository.findById(movieId)).thenReturn(Optional.of(movie));

        PlaylistEntity result = subject.addItem(authentication, playlist.getId(), movieId, null);

        assertEquals(MediaType.MOVIE, result.getItems().getFirst().getType());
        assertEquals(movieId, result.getItems().getFirst().getMovieEntityId());
    }

    @Test
    void addItemInsertsAfterAnExistingEntry() {
        PlaylistEntity playlist = ownedManualPlaylist(musicLibrary);
        PlaylistItemEntity first = playlistItem(playlist, "1000");
        PlaylistItemEntity second = playlistItem(playlist, "2000");
        playlist.getItems().add(first);
        playlist.getItems().add(second);
        UUID trackId = UUID.randomUUID();
        trackIn(musicLibrary, trackId);

        PlaylistEntity result = subject.addItem(authentication, playlist.getId(), trackId, first.getId());

        assertEquals(3, result.getItems().size());
        assertEquals(trackId, result.getItems().get(1).getTrackEntityId());
        assertEquals(new BigDecimal("1500.0000000000"), result.getItems().get(1).getPosition());
    }

    @Test
    void moveItemReordersEntries() {
        PlaylistEntity playlist = ownedManualPlaylist(musicLibrary);
        PlaylistItemEntity first = playlistItem(playlist, "1000");
        PlaylistItemEntity second = playlistItem(playlist, "2000");
        playlist.getItems().add(first);
        playlist.getItems().add(second);

        PlaylistEntity result = subject.moveItem(authentication, playlist.getId(), second.getId(), null);

        assertEquals(second.getId(), result.getItems().getFirst().getId());
    }

    @Test
    void moveItemRejectsMovingAfterItself() {
        PlaylistEntity playlist = ownedManualPlaylist(musicLibrary);
        PlaylistItemEntity first = playlistItem(playlist, "1000");
        playlist.getItems().add(first);
        UUID itemId = first.getId();

        assertThrows(IllegalArgumentException.class,
                () -> subject.moveItem(authentication, playlist.getId(), itemId, itemId));
    }

    @Test
    void removeItemDeletesTheEntry() {
        PlaylistEntity playlist = ownedManualPlaylist(musicLibrary);
        PlaylistItemEntity first = playlistItem(playlist, "1000");
        playlist.getItems().add(first);

        PlaylistEntity result = subject.removeItem(authentication, playlist.getId(), first.getId());

        assertTrue(result.getItems().isEmpty());
    }

    @Test
    void removeItemRejectsAnUnknownEntry() {
        PlaylistEntity playlist = ownedManualPlaylist(musicLibrary);
        UUID unknown = UUID.randomUUID();
        assertThrows(IllegalArgumentException.class,
                () -> subject.removeItem(authentication, playlist.getId(), unknown));
    }

    private PlaylistItemEntity playlistItem(PlaylistEntity playlist, String position) {
        PlaylistItemEntity item = PlaylistItemEntity.builder()
                .playlistEntity(playlist)
                .type(MediaType.TRACK)
                .position(new BigDecimal(position))
                .build();
        item.setId(UUID.randomUUID());
        item.setTrackEntityId(UUID.randomUUID());
        return item;
    }
}
