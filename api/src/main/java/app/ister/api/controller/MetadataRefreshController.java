package app.ister.api.controller;

import app.ister.core.enums.DirectoryType;
import app.ister.core.enums.EventType;
import app.ister.core.eventdata.AnalyzeData;
import app.ister.core.eventdata.MetadataBackfillRequestedData;
import app.ister.core.eventdata.UpdateImagesRequestedData;
import app.ister.core.repository.DirectoryRepository;
import app.ister.core.service.MessageSender;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.graphql.data.method.annotation.Argument;
import org.springframework.graphql.data.method.annotation.MutationMapping;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;

import java.util.UUID;

/**
 * The metadata refresh mutations. Two flavors:
 * <ul>
 *   <li>{@code refreshMetadata(MISSING)} — backfill: one global METADATA_BACKFILL_REQUESTED event
 *       (consumed once cluster-wide) plus UPDATE_IMAGES_REQUESTED per directory for the blur-hash
 *       sweep (those queues are directory-scoped, so the work lands on the owning node).</li>
 *   <li>{@code refreshMetadata(FORCE, libraryId)} and the per-item {@code refresh*} mutations —
 *       the destructive ANALYZE_DATA flow: wipe stored metadata/artwork, re-fetch everything.</li>
 * </ul>
 */
@Slf4j
@Controller
@RequiredArgsConstructor
public class MetadataRefreshController {

    /** Mirrors the GraphQL MetadataRefreshMode enum. */
    public enum MetadataRefreshMode {
        MISSING,
        FORCE,
    }

    private final MessageSender messageSender;
    private final DirectoryRepository directoryRepository;

    @MutationMapping
    @PreAuthorize("hasRole('admin')")
    public Boolean refreshMetadata(@Argument MetadataRefreshMode mode, @Argument UUID libraryId) {
        if (mode == MetadataRefreshMode.FORCE) {
            if (libraryId == null) {
                throw new IllegalArgumentException("FORCE requires libraryId: a full-cluster wipe must be requested per library");
            }
            log.debug("Force metadata refresh for library {}", libraryId);
            messageSender.sendAnalyzeData(
                    AnalyzeData.builder()
                            .eventType(EventType.ANALYZE_DATA)
                            .libraryId(libraryId)
                            .build());
            return true;
        }
        log.debug("Metadata backfill, libraryId: {}", libraryId);
        // Blur-hash sweep: every directory when unscoped (CACHE included — downloaded artwork
        // lives there and makes up the bulk of the images), the library's directories when scoped.
        var directories = libraryId == null
                ? directoryRepository.findAll()
                : directoryRepository.findByDirectoryTypeAndLibraryEntityId(DirectoryType.LIBRARY, libraryId);
        directories.forEach(dir -> messageSender.sendUpdateImagesRequested(
                UpdateImagesRequestedData.builder()
                        .eventType(EventType.UPDATE_IMAGES_REQUESTED)
                        .directoryEntityId(dir.getId())
                        .directoryName(dir.getName())
                        .build(),
                dir.getName()));
        messageSender.sendMetadataBackfillRequested(
                MetadataBackfillRequestedData.builder()
                        .eventType(EventType.METADATA_BACKFILL_REQUESTED)
                        .libraryId(libraryId)
                        .build());
        return true;
    }

    @MutationMapping
    @PreAuthorize("hasRole('admin')")
    public Boolean refreshEpisode(@Argument UUID episodeId) {
        messageSender.sendAnalyzeData(
                AnalyzeData.builder()
                        .eventType(EventType.ANALYZE_DATA)
                        .episodeId(episodeId)
                        .build());
        return true;
    }

    @MutationMapping
    @PreAuthorize("hasRole('admin')")
    public Boolean refreshMovie(@Argument UUID movieId) {
        messageSender.sendAnalyzeData(
                AnalyzeData.builder()
                        .eventType(EventType.ANALYZE_DATA)
                        .movieId(movieId)
                        .build());
        return true;
    }

    @MutationMapping
    @PreAuthorize("hasRole('admin')")
    public Boolean refreshShow(@Argument UUID showId) {
        messageSender.sendAnalyzeData(
                AnalyzeData.builder()
                        .eventType(EventType.ANALYZE_DATA)
                        .showId(showId)
                        .build());
        return true;
    }

    @MutationMapping
    @PreAuthorize("hasRole('admin')")
    public Boolean refreshPerson(@Argument UUID personId) {
        messageSender.sendAnalyzeData(
                AnalyzeData.builder()
                        .eventType(EventType.ANALYZE_DATA)
                        .personId(personId)
                        .build());
        return true;
    }

    @MutationMapping
    @PreAuthorize("hasRole('admin')")
    public Boolean refreshAlbum(@Argument UUID albumId) {
        messageSender.sendAnalyzeData(
                AnalyzeData.builder()
                        .eventType(EventType.ANALYZE_DATA)
                        .albumId(albumId)
                        .build());
        return true;
    }

    @MutationMapping
    @PreAuthorize("hasRole('admin')")
    public Boolean refreshTrack(@Argument UUID trackId) {
        messageSender.sendAnalyzeData(
                AnalyzeData.builder()
                        .eventType(EventType.ANALYZE_DATA)
                        .trackId(trackId)
                        .build());
        return true;
    }
}
