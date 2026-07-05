package app.ister.search;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;

/**
 * Document stored in the unified Typesense collection. {@code id} is the entity UUID,
 * {@code type} a {@link app.ister.core.enums.SearchEntityType} name.
 */
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public record SearchDocument(
        String id,
        String type,
        String title,
        String context,
        String description,
        String genre,
        Integer year,
        Integer number,
        Integer seasonNumber,
        String libraryId) {
}
