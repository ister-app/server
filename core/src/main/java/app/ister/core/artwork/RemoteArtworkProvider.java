package app.ister.core.artwork;

import app.ister.core.enums.ImageType;

import java.util.List;
import java.util.Optional;

/**
 * Lists the alternative artwork an external metadata provider has for an item (TMDB offers dozens
 * of posters and backdrops where the scan stored one). Implementations live in the worker module
 * next to the provider's client; the admin API in the api module only knows this interface.
 */
public interface RemoteArtworkProvider {

    /** Alternatives for the item and type; empty when the item is not linked to this provider. */
    List<RemoteImageCandidate> candidates(ImageTarget target, ImageType type);

    /**
     * The URL to download a candidate this provider handed out, derived from its {@code sourceUri};
     * empty for a URI that is not this provider's. Keeps the select mutation from fetching an
     * arbitrary URL on the admin's say-so.
     */
    Optional<String> downloadUrl(String sourceUri);
}
