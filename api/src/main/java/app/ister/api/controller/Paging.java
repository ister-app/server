package app.ister.api.controller;

import app.ister.core.entity.BaseEntity;
import app.ister.core.enums.SortingEnum;
import app.ister.core.enums.SortingOrder;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

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

    /**
     * Loads the entities of a repository-ranked id list, preserving the list's order.
     * {@code findAllById} returns them in an arbitrary order, and ids that no longer resolve
     * (deleted between the ranking query and this load) are dropped rather than yielding nulls.
     */
    static <T extends BaseEntity> List<T> inOrder(JpaRepository<T, UUID> repository, List<UUID> ids) {
        Map<UUID, T> byId = repository.findAllById(ids).stream()
                .collect(Collectors.toMap(BaseEntity::getId, Function.identity()));
        return ids.stream().map(byId::get).filter(Objects::nonNull).toList();
    }
}
