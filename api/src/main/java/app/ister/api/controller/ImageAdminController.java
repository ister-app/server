package app.ister.api.controller;

import app.ister.core.artwork.ArtworkAdminService;
import app.ister.core.artwork.ImageTarget;
import app.ister.core.artwork.RemoteArtworkProvider;
import app.ister.core.artwork.RemoteImageCandidate;
import app.ister.core.entity.ImageEntity;
import app.ister.core.enums.ImageType;
import app.ister.core.repository.ImageRepository;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.graphql.data.method.annotation.Argument;
import org.springframework.graphql.data.method.annotation.MutationMapping;
import org.springframework.graphql.data.method.annotation.QueryMapping;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.util.List;
import java.util.UUID;

/**
 * The admin's say over artwork: list what an item's cover/background could be and pick one — a
 * stored image, a TMDB alternative, a frame from the video — or undo the choice. Uploads are the
 * REST twin ({@code POST /artwork/upload}, disk module). The work is {@link ArtworkAdminService}
 * (disk module); alternatives come from every {@link RemoteArtworkProvider} on the classpath.
 */
@Controller
@RequiredArgsConstructor
public class ImageAdminController {

    private final ArtworkAdminService artworkAdminService;
    private final ImageRepository imageRepository;
    private final List<RemoteArtworkProvider> providers;

    /** GraphQL input {@code ImageTargetInput}: exactly one id set. */
    public record ImageTargetInput(@Nullable UUID movieId, @Nullable UUID showId, @Nullable UUID episodeId,
                                   @Nullable UUID albumId, @Nullable UUID personId, @Nullable UUID bookId,
                                   @Nullable UUID podcastId, @Nullable UUID gameId, @Nullable UUID seriesId) {
        ImageTarget toTarget() {
            return ImageTarget.of(movieId, showId, episodeId, albumId, personId, bookId, podcastId, gameId, seriesId);
        }
    }

    public record ImageCandidates(List<ImageEntity> current, List<RemoteImageCandidate> remote) {
    }

    @PreAuthorize("hasRole('admin')")
    @QueryMapping
    @Transactional(readOnly = true)
    public ImageCandidates imageCandidates(@Argument ImageTargetInput target, @Argument ImageType type) {
        ImageTarget imageTarget = target.toTarget();
        List<ImageEntity> current = imageTarget.imagesOf(imageRepository).stream()
                .filter(image -> image.getType() == type)
                .toList();
        List<RemoteImageCandidate> remote = providers.stream()
                .flatMap(provider -> provider.candidates(imageTarget, type).stream())
                .toList();
        return new ImageCandidates(current, remote);
    }

    @PreAuthorize("hasRole('admin')")
    @MutationMapping
    public ImageEntity selectImage(@Argument UUID imageId) {
        return artworkAdminService.select(imageId);
    }

    @PreAuthorize("hasRole('admin')")
    @MutationMapping
    public ImageEntity selectRemoteImage(@Argument ImageTargetInput target, @Argument ImageType type,
                                         @Argument String sourceUri) throws IOException {
        return artworkAdminService.selectRemote(target.toTarget(), type, sourceUri);
    }

    @PreAuthorize("hasRole('admin')")
    @MutationMapping
    public ImageEntity selectVideoFrame(@Argument ImageTargetInput target, @Argument ImageType type,
                                        @Argument UUID mediaFileId, @Argument int positionMs) throws IOException {
        return artworkAdminService.selectVideoFrame(target.toTarget(), type, mediaFileId, positionMs);
    }

    @PreAuthorize("hasRole('admin')")
    @MutationMapping
    public boolean clearSelectedImage(@Argument ImageTargetInput target, @Argument ImageType type) {
        return artworkAdminService.clearSelected(target.toTarget(), type);
    }

    @PreAuthorize("hasRole('admin')")
    @MutationMapping
    public boolean deleteImage(@Argument UUID imageId) {
        return artworkAdminService.deleteUserImage(imageId);
    }
}
