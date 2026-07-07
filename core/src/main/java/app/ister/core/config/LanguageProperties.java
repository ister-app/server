package app.ister.core.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.List;
import java.util.Locale;

/**
 * App-wide list of supported languages, configured as ISO-639-1 / BCP-47 tags
 * (e.g. {@code en,nl}) via {@code app.ister.languages}. This single list drives both
 * which languages TMDB metadata is fetched in (worker) and which languages the search
 * index stores/searches (search). The first entry is treated as the primary language.
 *
 * <p>Note the two code systems in play: TMDB and the Typesense {@code locale}/field-suffix
 * use the ISO-639-1 tag ({@code en}); {@code MetadataEntity.language} is stored as ISO-639-3
 * ({@code eng}). Use {@link #iso3(String)} to bridge between them.
 */
@Getter
@Setter
@Configuration
@ConfigurationProperties(prefix = "app.ister")
public class LanguageProperties {
    /** ISO-639-1 / BCP-47 tags, comma-separated in configuration. First = primary. */
    private List<String> languages = List.of("en", "nl");

    /** The configured ISO-639-1 / BCP-47 tags (TMDB + Typesense locale/field suffix). */
    public List<String> tags() {
        return languages;
    }

    /** The primary (first) language tag; used as fallback when a specific language is absent. */
    public String primaryTag() {
        return languages.getFirst();
    }

    /** ISO-639-3 code for a tag, matching the convention stored in {@code MetadataEntity.language}. */
    public String iso3(String tag) {
        return Locale.forLanguageTag(tag).getISO3Language();
    }
}
