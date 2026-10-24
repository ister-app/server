package app.ister.api.controller;

import app.ister.core.entity.BookEntity;
import app.ister.core.entity.GameEntity;
import app.ister.core.entity.ImageEntity;
import app.ister.core.entity.UserEntity;
import app.ister.core.enums.ActivityKind;
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
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.graphql.data.method.annotation.Argument;
import org.springframework.graphql.data.method.annotation.MutationMapping;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;

/**
 * Now-playing presence for activities that have no play queue: reading a book or comic and
 * playing a game. The client reports a heartbeat per activity session under a client-generated
 * session id, which lands in the same cluster-wide registry (and nowPlaying list) as the playback
 * heartbeats, with {@link ActivityKind} telling the cards apart. Title and cover are resolved
 * here on the request thread so, like the playback path, only plain values cross into the message.
 */
@Controller
@Slf4j
@RequiredArgsConstructor
public class ActivitySessionController {

    /** Labels are rendered by the client; keep a stray long one from bloating every snapshot. */
    static final int MAX_LABEL_LENGTH = 80;

    private final UserService userService;
    private final BookRepository bookRepository;
    private final GameRepository gameRepository;
    private final ImageRepository imageRepository;
    private final LibraryAccessService libraryAccessService;
    private final PlaybackSessionRegistry playbackSessionRegistry;
    private final PlaybackStatusService playbackStatusService;
    private final DeviceNameResolver deviceNameResolver;

    private record Presentation(String title, UUID artworkImageId) {
    }

    @PreAuthorize("hasRole('user')")
    @MutationMapping
    @Transactional(readOnly = true)
    @SuppressWarnings("java:S107") // the heartbeat carries exactly these fields
    public boolean reportActivity(@Argument UUID sessionId, @Argument MediaType mediaType, @Argument UUID mediaId,
                                  @Argument UUID mediaFileId, @Argument Double progressFraction,
                                  @Argument String progressLabel, @Argument PlayState playState,
                                  @Argument UUID deviceId, Authentication authentication) {
        ActivityKind kind = kindOf(mediaType);
        UserEntity user = userService.getOrCreateUser(authentication);
        requireOwnership(sessionId, user.getId());
        Presentation presentation = presentationOf(mediaType, mediaId, authentication);
        playbackStatusService.publishActivity(PlaybackStatusData.builder()
                .playQueueId(sessionId)
                .userId(user.getId())
                .userExternalId(user.getExternalId())
                .userName(user.getName())
                .mediaType(mediaType)
                .mediaId(mediaId)
                .mediaFileId(mediaFileId)
                .title(presentation.title())
                .artworkImageId(presentation.artworkImageId())
                .progressInMilliseconds(0)
                .playState(playState == null ? PlayState.PLAYING : playState)
                .controlAllowedUserIds(List.of())
                .deviceId(deviceId)
                .deviceName(deviceNameResolver.nameOf(user.getId(), deviceId))
                .activityKind(kind)
                .progressFraction(kind == ActivityKind.GAME || progressFraction == null
                        ? null : Math.clamp(progressFraction, 0.0, 1.0))
                .progressLabel(kind == ActivityKind.GAME ? null : trimLabel(progressLabel))
                .build());
        return true;
    }

    @PreAuthorize("hasRole('user')")
    @MutationMapping
    public boolean endActivity(@Argument UUID sessionId, Authentication authentication) {
        Optional<PlaybackStatusData> current = playbackSessionRegistry.find(sessionId);
        if (current.isEmpty()) {
            return true; // already expired or never seen on this node: nothing to drop
        }
        UUID userId = userService.getOrCreateUser(authentication).getId();
        if (!userId.equals(current.get().getUserId())) {
            throw new AccessDeniedException("Activity session does not belong to the authenticated user");
        }
        playbackStatusService.publishActivityEnded(sessionId, userId);
        return true;
    }

    private static ActivityKind kindOf(MediaType mediaType) {
        return switch (mediaType) {
            case BOOK, COMIC -> ActivityKind.READING;
            case GAME -> ActivityKind.GAME;
            case MOVIE, EPISODE, TRACK, CHAPTER, PODCAST_EPISODE ->
                    throw new IllegalArgumentException("Not an activity media type: " + mediaType
                            + " (playback sessions report through updatePlayQueue)");
        };
    }

    /** A session id is claimed by its first heartbeat; another user may neither update nor end it. */
    private void requireOwnership(UUID sessionId, UUID userId) {
        playbackSessionRegistry.find(sessionId)
                .filter(existing -> !userId.equals(existing.getUserId()))
                .ifPresent(_ -> {
                    throw new AccessDeniedException("Activity session does not belong to the authenticated user");
                });
    }

    /** Deny-as-not-found, like every other library-scoped lookup. */
    private Presentation presentationOf(MediaType mediaType, UUID mediaId, Authentication authentication) {
        if (mediaType == MediaType.GAME) {
            GameEntity game = gameRepository.findById(mediaId)
                    .filter(found -> libraryAccessService.canAccess(found.getLibraryEntity(), authentication))
                    .orElseThrow(() -> new NoSuchElementException("Game not found: " + mediaId));
            return new Presentation(firstNonBlank(game.getTitle(), game.getName()),
                    SessionArtworkPicker.pick(imageRepository.findByGameEntityId(game.getId())));
        }
        BookEntity book = bookRepository.findById(mediaId)
                .filter(found -> libraryAccessService.canAccess(found.getLibraryEntity(), authentication))
                .orElseThrow(() -> new NoSuchElementException("Book not found: " + mediaId));
        List<ImageEntity> images = imageRepository.findByBookEntityId(book.getId());
        if (images.isEmpty() && book.getSeriesEntity() != null) {
            // A comic volume usually has no cover of its own; the series cover stands in.
            images = imageRepository.findBySeriesEntityId(book.getSeriesEntity().getId());
        }
        return new Presentation(firstNonBlank(book.getTitle(), book.getName()), SessionArtworkPicker.pick(images));
    }

    private static String firstNonBlank(String preferred, String fallback) {
        return preferred == null || preferred.isBlank() ? fallback : preferred;
    }

    static String trimLabel(String label) {
        if (label == null) {
            return null;
        }
        String trimmed = label.strip();
        if (trimmed.isEmpty()) {
            return null;
        }
        return trimmed.length() <= MAX_LABEL_LENGTH ? trimmed : trimmed.substring(0, MAX_LABEL_LENGTH);
    }
}
