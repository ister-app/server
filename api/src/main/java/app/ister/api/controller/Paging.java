package app.ister.api.controller;

import app.ister.core.enums.SortingEnum;
import app.ister.core.enums.SortingOrder;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

import java.util.Optional;

/** Shared page/sort argument resolution for the paginated GraphQL queries. */
final class Paging {

    /** Upper bound on client-requested page sizes, so a single query cannot materialize a whole table. */
    static final int MAX_PAGE_SIZE = 200;

    private Paging() {
    }

    /**
     * Page/size resolution without a sort, for queries whose ORDER BY is baked into the repository
     * method (the metadata-based sorts of {@code tracks}/{@code episodes}).
     */
    static Pageable unsorted(Optional<Integer> page, Optional<Integer> size, int defaultSize) {
        int pageSize = Math.clamp(size.orElse(defaultSize), 1, MAX_PAGE_SIZE);
        return PageRequest.of(Math.max(page.orElse(0), 0), pageSize);
    }

    static Pageable pageable(Optional<Integer> page, Optional<Integer> size, int defaultSize,
                             Optional<SortingEnum> sorting, SortingEnum defaultSorting,
                             Optional<SortingOrder> sortingOrder, SortingOrder defaultOrder) {
        Sort sort = Sort.by(sorting.orElse(defaultSorting).getDatabaseString());
        sort = sortingOrder.orElse(defaultOrder) == SortingOrder.ASCENDING ? sort.ascending() : sort.descending();
        // Tie-break on id: without a unique column in the sort, rows with equal sort values may
        // shift between the queries for consecutive pages, duplicating or skipping items.
        sort = sort.and(Sort.by("id"));
        int pageSize = Math.clamp(size.orElse(defaultSize), 1, MAX_PAGE_SIZE);
        return PageRequest.of(Math.max(page.orElse(0), 0), pageSize, sort);
    }
}
