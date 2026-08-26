package app.ister.api.controller;

import app.ister.core.entity.LibraryEntity;
import app.ister.core.entity.WatchStatusEntity;
import app.ister.core.enums.MediaType;
import app.ister.core.enums.TrackHistoryScope;
import app.ister.core.service.LibraryAccessService;
import app.ister.core.service.PlaybackHistoryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.graphql.data.method.annotation.Argument;
import org.springframework.graphql.data.method.annotation.MutationMapping;
import org.springframework.graphql.data.method.annotation.QueryMapping;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;

import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * The calling user's playback history of one media item, with manual edits: record a play as of
 * now, or delete an entry. Items in inaccessible libraries behave as not found.
 */
@Slf4j
@Controller
@RequiredArgsConstructor
public class PlaybackHistoryController {
    private final PlaybackHistoryService playbackHistoryService;
    private final LibraryAccessService libraryAccessService;

    @PreAuthorize("hasRole('user')")
    @QueryMapping
    public List<WatchStatusEntity> playbackHistory(@Argument MediaType mediaType, @Argument UUID mediaId,
                                                   Authentication authentication) {
        if (!canAccess(mediaType, mediaId, authentication)) {
            return List.of();
        }
        return playbackHistoryService.history(authentication, mediaType, mediaId);
    }

    /**
     * The plays of the tracks of one container. An album is gated on its own library; an artist can
     * span libraries, so the caller's whitelist is handed to the query instead. Either way an
     * inaccessible container is indistinguishable from an empty history.
     */
    @PreAuthorize("hasRole('user')")
    @QueryMapping
    public List<WatchStatusEntity> trackPlaybackHistory(@Argument TrackHistoryScope scope, @Argument UUID id,
                                                        @Argument Integer limit, Authentication authentication) {
        Optional<Set<UUID>> allowed = libraryAccessService.allowedLibraryIds(authentication);
        if (scope == TrackHistoryScope.ALBUM) {
            Optional<LibraryEntity> library = playbackHistoryService.libraryOfAlbum(id);
            if (library.isEmpty() || !libraryAccessService.canAccess(library.get(), authentication)) {
                return List.of();
            }
        }
        return playbackHistoryService.trackHistory(authentication, scope, id, limit, allowed);
    }

    @PreAuthorize("hasRole('user')")
    @MutationMapping
    public WatchStatusEntity markPlayed(@Argument MediaType mediaType, @Argument UUID mediaId,
                                        Authentication authentication) {
        if (!canAccess(mediaType, mediaId, authentication)) {
            throw new NoSuchElementException(mediaType + " not found: " + mediaId);
        }
        return playbackHistoryService.markPlayed(authentication, mediaType, mediaId);
    }

    /** Ownership is the gate here: only the caller's own rows delete, so no library check. */
    @PreAuthorize("hasRole('user')")
    @MutationMapping
    public Boolean deleteWatchStatus(@Argument UUID id, Authentication authentication) {
        return playbackHistoryService.deleteWatchStatus(authentication, id);
    }

    private boolean canAccess(MediaType mediaType, UUID mediaId, Authentication authentication) {
        Optional<LibraryEntity> library = playbackHistoryService.libraryOf(mediaType, mediaId);
        return library.isPresent() && libraryAccessService.canAccess(library.get(), authentication);
    }
}
