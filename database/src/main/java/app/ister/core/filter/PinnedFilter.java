package app.ister.core.filter;

import app.ister.core.enums.SortingEnum;
import app.ister.core.enums.SortingOrder;

import java.util.UUID;

/**
 * The filter definition a FILTER play queue was created from, frozen onto the queue as JSON.
 * A copy rather than a reference to the saved view: editing a view must not reshape a queue
 * that is already playing (the same principle as the pinned podcast episode order).
 */
public record PinnedFilter(FilterKind kind, MediaFilter filter, UUID libraryId, SortingEnum sorting,
                           SortingOrder sortingOrder) {
}
