package app.ister.core.filter;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * (De)serialization of filter definitions for persistence (saved views, pinned queue filters).
 * A dedicated mapper rather than the Spring one: this format lives in database rows, so it must
 * not shift with application-level Jackson customizations. Unknown properties are ignored so a
 * row written by a newer server version still parses.
 */
public final class FilterJson {
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private FilterJson() {
    }

    public static String write(PinnedFilter pinned) {
        try {
            return MAPPER.writeValueAsString(pinned);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Filter cannot be serialized", e);
        }
    }

    public static PinnedFilter readPinned(String json) {
        try {
            return MAPPER.readValue(json, PinnedFilter.class);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Stored filter cannot be parsed", e);
        }
    }

    public static String writeFilter(MediaFilter filter) {
        try {
            return MAPPER.writeValueAsString(filter);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Filter cannot be serialized", e);
        }
    }

    public static MediaFilter readFilter(String json) {
        try {
            return MAPPER.readValue(json, MediaFilter.class);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Stored filter cannot be parsed", e);
        }
    }
}
