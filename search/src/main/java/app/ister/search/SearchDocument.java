package app.ister.search;

import com.fasterxml.jackson.annotation.JsonValue;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Document stored in the unified Typesense collection. {@code id} is the entity UUID,
 * {@code type} a {@link app.ister.core.enums.SearchEntityType} name.
 *
 * <p>Fields are held in a map rather than fixed record components because the localized
 * fields ({@code title_<lang>}, {@code description_<lang>}, {@code genre_<lang>}) are driven
 * by the configured {@link app.ister.core.config.LanguageProperties} language list. Null
 * values are simply not added, so they are absent from the serialized JSON. The map is
 * serialized directly via {@link JsonValue}.
 */
public class SearchDocument {

    private final Map<String, Object> fields;

    private SearchDocument(Map<String, Object> fields) {
        this.fields = fields;
    }

    @JsonValue
    public Map<String, Object> fields() {
        return fields;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private final Map<String, Object> fields = new LinkedHashMap<>();

        private Builder put(String key, Object value) {
            if (value != null) {
                fields.put(key, value);
            }
            return this;
        }

        public Builder id(String id) {
            return put("id", id);
        }

        public Builder type(String type) {
            return put("type", type);
        }

        public Builder title(String title) {
            return put("title", title);
        }

        public Builder context(String context) {
            return put("context", context);
        }

        public Builder year(Integer year) {
            return put("year", year);
        }

        public Builder number(Integer number) {
            return put("number", number);
        }

        public Builder seasonNumber(Integer seasonNumber) {
            return put("seasonNumber", seasonNumber);
        }

        public Builder libraryId(String libraryId) {
            return put("libraryId", libraryId);
        }

        /** Adds a per-language field, e.g. {@code title_en}; skipped when {@code value} is null/blank. */
        public Builder localized(String base, String languageTag, String value) {
            if (value != null && !value.isBlank()) {
                fields.put(base + "_" + languageTag, value);
            }
            return this;
        }

        public SearchDocument build() {
            return new SearchDocument(fields);
        }
    }
}
