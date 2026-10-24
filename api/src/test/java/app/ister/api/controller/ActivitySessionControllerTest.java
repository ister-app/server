package app.ister.api.controller;

import app.ister.core.entity.BookEntity;
import app.ister.core.entity.GameEntity;
import app.ister.core.entity.ImageEntity;
import app.ister.core.entity.LibraryEntity;
import app.ister.core.entity.SeriesEntity;
import app.ister.core.entity.UserEntity;
import app.ister.core.enums.ActivityKind;
import app.ister.core.enums.ImageType;
import app.ister.core.enums.MediaType;
import app.ister.core.enums.PlayState;
import app.ister.core.eventdata.PlaybackStatusData;
import app.ister.core.repository.BookRepository;
import app.ister.core.repository.GameRepository;
import app.ister.core.repository.ImageRepository;
import app.ister.core.service.LibraryAccessService;
import app.ister.core.service.UserService;
import app.ister.core.status.PlaybackSessionRegistry;
import app.ister.core.status.PlaybackStatusService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;

import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ActivitySessionControllerTest {

    @InjectMocks
    private ActivitySessionController subject;

    @Mock
    private UserService userService;
    @Mock
    private BookRepository bookRepository;
    @Mock
    private GameRepository gameRepository;
    @Mock
    private ImageRepository imageRepository;
    @Mock
    private LibraryAccessService libraryAccessService;
    @Mock
    private PlaybackSessionRegistry playbackSessionRegistry;
    @Mock
    private PlaybackStatusService playbackStatusService;
    @Mock
    private DeviceNameResolver deviceNameResolver;
    @Mock
    private Authentication authentication;

    private final UUID userId = UUID.randomUUID();
    private final UUID sessionId = UUID.randomUUID();
    private final UUID deviceId = UUID.randomUUID();
    private final LibraryEntity library = LibraryEntity.builder().id(UUID.randomUUID()).build();

    @BeforeEach
    void setUp() {
        lenient().when(userService.getOrCreateUser(authentication))
                .thenReturn(UserEntity.builder().id(userId).externalId("sub").name("Gerben").build());
        lenient().when(playbackSessionRegistry.find(any())).thenReturn(Optional.empty());
        lenient().when(libraryAccessService.canAccess(library, authentication)).thenReturn(true);
        lenient().when(deviceNameResolver.nameOf(userId, deviceId)).thenReturn("Woonkamer");
    }

    private PlaybackStatusData published() {
        ArgumentCaptor<PlaybackStatusData> captor = ArgumentCaptor.forClass(PlaybackStatusData.class);
        verify(playbackStatusService).publishActivity(captor.capture());
        return captor.getValue();
    }

    private static ImageEntity cover(UUID id) {
        ImageEntity image = ImageEntity.builder().type(ImageType.COVER).build();
        image.setId(id);
        return image;
    }

    @Test
    void readingABookPublishesAReadingSessionWithTitleCoverAndProgress() {
        UUID bookId = UUID.randomUUID();
        UUID coverId = UUID.randomUUID();
        BookEntity book = BookEntity.builder().libraryEntity(library).name("names").title("Dit zijn de namen").build();
        book.setId(bookId);
        when(bookRepository.findById(bookId)).thenReturn(Optional.of(book));
        when(imageRepository.findByBookEntityId(bookId)).thenReturn(List.of(cover(coverId)));

        assertTrue(subject.reportActivity(sessionId, MediaType.BOOK, bookId, null, 0.4, "  Hoofdstuk 3 ",
                PlayState.PLAYING, deviceId, authentication));

        PlaybackStatusData data = published();
        assertEquals(sessionId, data.getPlayQueueId());
        assertEquals(userId, data.getUserId());
        assertEquals("sub", data.getUserExternalId());
        assertEquals("Gerben", data.getUserName());
        assertEquals(MediaType.BOOK, data.getMediaType());
        assertEquals(bookId, data.getMediaId());
        assertEquals("Dit zijn de namen", data.getTitle());
        assertEquals(coverId, data.getArtworkImageId());
        assertEquals(ActivityKind.READING, data.getActivityKind());
        assertEquals(0.4, data.getProgressFraction());
        assertEquals("Hoofdstuk 3", data.getProgressLabel());
        assertEquals(PlayState.PLAYING, data.getPlayState());
        assertEquals(deviceId, data.getDeviceId());
        assertEquals("Woonkamer", data.getDeviceName());
        assertEquals(List.of(), data.getControlAllowedUserIds());
    }

    @Test
    void aComicWithoutItsOwnCoverUsesTheSeriesCover() {
        UUID bookId = UUID.randomUUID();
        UUID seriesCover = UUID.randomUUID();
        SeriesEntity series = SeriesEntity.builder().build();
        series.setId(UUID.randomUUID());
        BookEntity volume = BookEntity.builder().libraryEntity(library).name("Vol 3").seriesEntity(series).build();
        volume.setId(bookId);
        when(bookRepository.findById(bookId)).thenReturn(Optional.of(volume));
        when(imageRepository.findByBookEntityId(bookId)).thenReturn(List.of());
        when(imageRepository.findBySeriesEntityId(series.getId())).thenReturn(List.of(cover(seriesCover)));

        subject.reportActivity(sessionId, MediaType.COMIC, bookId, null, 0.1, "Pagina 3 van 30",
                PlayState.PLAYING, deviceId, authentication);

        PlaybackStatusData data = published();
        assertEquals("Vol 3", data.getTitle(), "a blank title falls back to the name");
        assertEquals(seriesCover, data.getArtworkImageId());
        assertEquals(ActivityKind.READING, data.getActivityKind());
    }

    @Test
    void playingAGamePublishesAGameSessionWithoutProgress() {
        UUID gameId = UUID.randomUUID();
        UUID coverId = UUID.randomUUID();
        GameEntity game = GameEntity.builder().libraryEntity(library).name("Celeste (2018)").title("Celeste").build();
        game.setId(gameId);
        when(gameRepository.findById(gameId)).thenReturn(Optional.of(game));
        when(imageRepository.findByGameEntityId(gameId)).thenReturn(List.of(cover(coverId)));

        subject.reportActivity(sessionId, MediaType.GAME, gameId, null, 0.5, "ignored",
                PlayState.PLAYING, deviceId, authentication);

        PlaybackStatusData data = published();
        assertEquals("Celeste", data.getTitle());
        assertEquals(coverId, data.getArtworkImageId());
        assertEquals(ActivityKind.GAME, data.getActivityKind());
        assertNull(data.getProgressFraction(), "games have no reading progress");
        assertNull(data.getProgressLabel());
    }

    @Test
    void progressFractionIsClampedAndLongLabelsTruncated() {
        UUID bookId = UUID.randomUUID();
        BookEntity book = BookEntity.builder().libraryEntity(library).name("b").build();
        book.setId(bookId);
        when(bookRepository.findById(bookId)).thenReturn(Optional.of(book));
        when(imageRepository.findByBookEntityId(bookId)).thenReturn(List.of());

        subject.reportActivity(sessionId, MediaType.BOOK, bookId, null, 1.7, "x".repeat(200),
                PlayState.PAUSED, deviceId, authentication);

        PlaybackStatusData data = published();
        assertEquals(1.0, data.getProgressFraction());
        assertEquals(ActivitySessionController.MAX_LABEL_LENGTH, data.getProgressLabel().length());
        assertEquals(PlayState.PAUSED, data.getPlayState());
        assertNull(data.getArtworkImageId());
    }

    @Test
    void aBookOutsideTheCallersLibrariesIsNotFound() {
        UUID bookId = UUID.randomUUID();
        LibraryEntity other = LibraryEntity.builder().id(UUID.randomUUID()).build();
        BookEntity book = BookEntity.builder().libraryEntity(other).name("b").build();
        book.setId(bookId);
        when(bookRepository.findById(bookId)).thenReturn(Optional.of(book));
        when(libraryAccessService.canAccess(other, authentication)).thenReturn(false);

        assertThrows(NoSuchElementException.class, () -> subject.reportActivity(sessionId, MediaType.BOOK,
                bookId, null, 0.0, null, PlayState.PLAYING, deviceId, authentication));
        verify(playbackStatusService, never()).publishActivity(any());
    }

    @Test
    void playbackMediaTypesAreRejected() {
        UUID movieId = UUID.randomUUID();
        assertThrows(IllegalArgumentException.class, () -> subject.reportActivity(sessionId, MediaType.MOVIE,
                movieId, null, null, null, PlayState.PLAYING, deviceId, authentication));
        verify(playbackStatusService, never()).publishActivity(any());
    }

    @Test
    void anotherUsersSessionIdCannotBeTakenOver() {
        when(playbackSessionRegistry.find(sessionId)).thenReturn(Optional.of(
                PlaybackStatusData.builder().playQueueId(sessionId).userId(UUID.randomUUID()).build()));

        UUID gameId = UUID.randomUUID();
        assertThrows(AccessDeniedException.class, () -> subject.reportActivity(sessionId, MediaType.GAME,
                gameId, null, null, null, PlayState.PLAYING, deviceId, authentication));
        verify(playbackStatusService, never()).publishActivity(any());
    }

    @Test
    void endActivityPublishesTheEndedMarkerForAnOwnSession() {
        when(playbackSessionRegistry.find(sessionId)).thenReturn(Optional.of(
                PlaybackStatusData.builder().playQueueId(sessionId).userId(userId).build()));

        assertTrue(subject.endActivity(sessionId, authentication));

        verify(playbackStatusService).publishActivityEnded(sessionId, userId);
    }

    @Test
    void endActivityIsIdempotentForAnUnknownSession() {
        assertTrue(subject.endActivity(sessionId, authentication));

        verify(playbackStatusService, never()).publishActivityEnded(any(), any());
    }

    @Test
    void endActivityRefusesAnotherUsersSession() {
        when(playbackSessionRegistry.find(sessionId)).thenReturn(Optional.of(
                PlaybackStatusData.builder().playQueueId(sessionId).userId(UUID.randomUUID()).build()));

        assertThrows(AccessDeniedException.class, () -> subject.endActivity(sessionId, authentication));
        verify(playbackStatusService, never()).publishActivityEnded(any(), any());
    }
}
