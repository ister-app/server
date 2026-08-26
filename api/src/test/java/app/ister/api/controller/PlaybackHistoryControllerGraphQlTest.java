package app.ister.api.controller;

import app.ister.core.entity.LibraryEntity;
import app.ister.core.entity.TrackEntity;
import app.ister.core.entity.WatchStatusEntity;
import app.ister.core.enums.MediaType;
import app.ister.core.enums.TrackHistoryScope;
import app.ister.core.service.LibraryAccessService;
import app.ister.core.service.PlaybackHistoryService;
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

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * Schema-wiring test for the playbackHistory query and the markPlayed/deleteWatchStatus
 * mutations, including the createdAt/updatedAt field mappings on WatchStatus.
 */
@GraphQlTest({PlaybackHistoryController.class, WatchStatusController.class})
class PlaybackHistoryControllerGraphQlTest {

    @Autowired
    private GraphQlTester graphQlTester;

    @MockitoBean
    private PlaybackHistoryService playbackHistoryService;

    @MockitoBean
    private LibraryAccessService libraryAccessService;

    @BeforeEach
    void authenticateAsUser() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(
                        "test-user", null,
                        List.of(new SimpleGrantedAuthority("ROLE_user"))));
    }

    @AfterEach
    void clearAuthentication() {
        SecurityContextHolder.clearContext();
    }

    private static WatchStatusEntity watchStatus(UUID id, Instant created, Instant updated) {
        WatchStatusEntity entity = WatchStatusEntity.builder().watched(true).progressInMilliseconds(1000).build();
        entity.setId(id);
        entity.setDateCreated(created);
        entity.setDateUpdated(updated);
        return entity;
    }

    private void grantAccess(MediaType mediaType, UUID mediaId) {
        LibraryEntity library = LibraryEntity.builder().build();
        when(playbackHistoryService.libraryOf(mediaType, mediaId)).thenReturn(Optional.of(library));
        when(libraryAccessService.canAccess(eq(library), any())).thenReturn(true);
    }

    @Test
    void playbackHistoryExposesTheTimestamps() {
        UUID mediaId = UUID.randomUUID();
        UUID rowId = UUID.randomUUID();
        grantAccess(MediaType.MOVIE, mediaId);
        when(playbackHistoryService.history(any(), eq(MediaType.MOVIE), eq(mediaId)))
                .thenReturn(List.of(watchStatus(rowId,
                        Instant.parse("2026-08-20T19:00:00Z"), Instant.parse("2026-08-20T21:05:00Z"))));

        graphQlTester.document("""
                        query {
                            playbackHistory(mediaType: MOVIE, mediaId: "%s") {
                                id watched progressInMilliseconds createdAt updatedAt
                            }
                        }
                        """.formatted(mediaId))
                .execute()
                .path("playbackHistory[0].id").entity(String.class).isEqualTo(rowId.toString())
                .path("playbackHistory[0].watched").entity(Boolean.class).isEqualTo(true)
                .path("playbackHistory[0].createdAt").entity(String.class).isEqualTo("2026-08-20T19:00:00Z")
                .path("playbackHistory[0].updatedAt").entity(String.class).isEqualTo("2026-08-20T21:05:00Z");
    }

    @Test
    void albumTrackHistoryIsGatedOnTheAlbumsLibraryAndNamesThePlayedTrack() {
        UUID albumId = UUID.randomUUID();
        UUID rowId = UUID.randomUUID();
        UUID trackId = UUID.randomUUID();
        LibraryEntity library = LibraryEntity.builder().build();
        when(playbackHistoryService.libraryOfAlbum(albumId)).thenReturn(Optional.of(library));
        when(libraryAccessService.canAccess(eq(library), any())).thenReturn(true);
        when(libraryAccessService.allowedLibraryIds(any())).thenReturn(Optional.empty());
        WatchStatusEntity row = watchStatus(rowId, Instant.parse("2026-08-20T19:00:00Z"), Instant.parse("2026-08-20T19:04:00Z"));
        TrackEntity track = TrackEntity.builder().number(3).build();
        track.setId(trackId);
        row.setTrackEntity(track);
        when(playbackHistoryService.trackHistory(any(), eq(TrackHistoryScope.ALBUM), eq(albumId), eq(null), any()))
                .thenReturn(List.of(row));

        graphQlTester.document("""
                        query {
                            trackPlaybackHistory(scope: ALBUM, id: "%s") {
                                id updatedAt track { id number }
                            }
                        }
                        """.formatted(albumId))
                .execute()
                .path("trackPlaybackHistory[0].id").entity(String.class).isEqualTo(rowId.toString())
                .path("trackPlaybackHistory[0].updatedAt").entity(String.class).isEqualTo("2026-08-20T19:04:00Z")
                .path("trackPlaybackHistory[0].track.id").entity(String.class).isEqualTo(trackId.toString())
                .path("trackPlaybackHistory[0].track.number").entity(Integer.class).isEqualTo(3);
    }

    @Test
    void anInaccessibleAlbumHasAnEmptyHistoryRatherThanAnError() {
        UUID albumId = UUID.randomUUID();
        when(libraryAccessService.allowedLibraryIds(any())).thenReturn(Optional.of(Set.of(UUID.randomUUID())));
        when(playbackHistoryService.libraryOfAlbum(albumId)).thenReturn(Optional.empty());

        graphQlTester.document("""
                        query {
                            trackPlaybackHistory(scope: ALBUM, id: "%s") { id }
                        }
                        """.formatted(albumId))
                .execute()
                .path("trackPlaybackHistory").entityList(Object.class).hasSize(0);
    }

    @Test
    void artistTrackHistoryHandsTheCallersLibrariesToTheQuery() {
        UUID artistId = UUID.randomUUID();
        UUID libraryId = UUID.randomUUID();
        UUID rowId = UUID.randomUUID();
        when(libraryAccessService.allowedLibraryIds(any())).thenReturn(Optional.of(Set.of(libraryId)));
        when(playbackHistoryService.trackHistory(any(), eq(TrackHistoryScope.ARTIST), eq(artistId), eq(25),
                eq(Optional.of(Set.of(libraryId)))))
                .thenReturn(List.of(watchStatus(rowId, Instant.now(), Instant.now())));

        graphQlTester.document("""
                        query {
                            trackPlaybackHistory(scope: ARTIST, id: "%s", limit: 25) { id }
                        }
                        """.formatted(artistId))
                .execute()
                .path("trackPlaybackHistory[0].id").entity(String.class).isEqualTo(rowId.toString());
    }

    @Test
    void markPlayedReturnsTheNewEntry() {
        UUID mediaId = UUID.randomUUID();
        UUID rowId = UUID.randomUUID();
        grantAccess(MediaType.TRACK, mediaId);
        when(playbackHistoryService.markPlayed(any(), eq(MediaType.TRACK), eq(mediaId)))
                .thenReturn(watchStatus(rowId, Instant.now(), Instant.now()));

        graphQlTester.document("""
                        mutation {
                            markPlayed(mediaType: TRACK, mediaId: "%s") { id watched }
                        }
                        """.formatted(mediaId))
                .execute()
                .path("markPlayed.id").entity(String.class).isEqualTo(rowId.toString())
                .path("markPlayed.watched").entity(Boolean.class).isEqualTo(true);
    }

    @Test
    void deleteWatchStatusReturnsTheServiceResult() {
        UUID id = UUID.randomUUID();
        when(playbackHistoryService.deleteWatchStatus(any(), eq(id))).thenReturn(true);

        graphQlTester.document("""
                        mutation {
                            deleteWatchStatus(id: "%s")
                        }
                        """.formatted(id))
                .execute()
                .path("deleteWatchStatus").entity(Boolean.class).isEqualTo(true);
    }
}
