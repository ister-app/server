package app.ister.api.controller;

import app.ister.core.enums.MetadataSource;
import app.ister.core.repository.ImageRepository;
import app.ister.core.repository.MetadataRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.graphql.data.method.annotation.QueryMapping;
import org.springframework.stereotype.Controller;

import java.util.EnumSet;
import java.util.List;

/**
 * The external providers whose metadata/images are actually present on this server, with the
 * display name/URL from {@link MetadataSource} plus provider-mandated notice/license text.
 * Local files and podcast feeds are not external providers to credit, so they are left out.
 */
@Controller
@RequiredArgsConstructor
public class AttributionController {

    private static final String TMDB_NOTICE =
            "This product uses the TMDB API but is not endorsed or certified by TMDB.";

    private final MetadataRepository metadataRepository;
    private final ImageRepository imageRepository;

    @QueryMapping
    public List<Attribution> attributions() {
        EnumSet<MetadataSource> inUse = EnumSet.noneOf(MetadataSource.class);
        inUse.addAll(metadataRepository.findDistinctSources());
        inUse.addAll(imageRepository.findDistinctSources());
        inUse.remove(MetadataSource.LOCAL_FILE);
        inUse.remove(MetadataSource.PODCAST_FEED);
        return inUse.stream().map(AttributionController::toAttribution).toList();
    }

    private static Attribution toAttribution(MetadataSource source) {
        return new Attribution(source, source.getDisplayName(), source.getUrl(),
                source == MetadataSource.TMDB ? TMDB_NOTICE : null,
                switch (source) {
                    case WIKIPEDIA -> "CC BY-SA 4.0";
                    case WIKIMEDIA_COMMONS -> "Varies per file (see Wikimedia Commons)";
                    default -> null;
                });
    }

    public record Attribution(MetadataSource source, String name, String url, String notice, String license) {}
}
