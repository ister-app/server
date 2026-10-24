package app.ister.core.artwork;

import app.ister.core.enums.MetadataSource;
import org.jspecify.annotations.Nullable;

/**
 * An image a metadata provider offers for an item but that the server has not stored (yet).
 * {@code previewUrl} is what a client shows in the picker (a reduced size where the provider has
 * one); {@code sourceUri} is the provenance key the stored row would get, so a client can tell
 * which candidates are already present and the server can find the download again.
 */
public record RemoteImageCandidate(String previewUrl,
                                   String sourceUri,
                                   MetadataSource source,
                                   @Nullable Integer width,
                                   @Nullable Integer height,
                                   @Nullable String language,
                                   @Nullable Double score) {
}
