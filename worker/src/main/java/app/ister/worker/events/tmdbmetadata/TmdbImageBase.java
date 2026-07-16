package app.ister.worker.events.tmdbmetadata;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Base URL for TMDB image downloads (poster/backdrop/still/profile paths).
 * Configurable so CI can point it at a mock server.
 */
@Component
public class TmdbImageBase {

    private final String base;

    public TmdbImageBase(
            @Value("${app.ister.worker.tmdb.image-base:https://image.tmdb.org/t/p/original}") String base) {
        this.base = base;
    }

    /** The absolute download URL for a TMDB image path like {@code /abc.jpg}. */
    public String url(String imagePath) {
        return base + imagePath;
    }
}
