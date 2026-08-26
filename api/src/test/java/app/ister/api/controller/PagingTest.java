package app.ister.api.controller;

import app.ister.core.entity.ShowEntity;
import app.ister.core.enums.SortingEnum;
import app.ister.core.enums.SortingOrder;
import app.ister.core.repository.ShowRepository;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyIterable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PagingTest {

    @Test
    void usesDefaultsWhenArgumentsAbsent() {
        Pageable pageable = Paging.pageable(Optional.empty(), Optional.empty(), 10,
                Optional.empty(), SortingEnum.NAME, Optional.empty(), SortingOrder.ASCENDING);

        assertEquals(0, pageable.getPageNumber());
        assertEquals(10, pageable.getPageSize());
        assertEquals(Sort.Direction.ASC, pageable.getSort().getOrderFor(SortingEnum.NAME.getDatabaseString()).getDirection());
    }

    @Test
    void appliesRequestedPageSizeAndDescendingOrder() {
        Pageable pageable = Paging.pageable(Optional.of(3), Optional.of(50), 10,
                Optional.of(SortingEnum.DATE_CREATED), SortingEnum.NAME,
                Optional.of(SortingOrder.DESCENDING), SortingOrder.ASCENDING);

        assertEquals(3, pageable.getPageNumber());
        assertEquals(50, pageable.getPageSize());
        assertEquals(Sort.Direction.DESC, pageable.getSort().getOrderFor(SortingEnum.DATE_CREATED.getDatabaseString()).getDirection());
    }

    @Test
    void addsIdTieBreakerToSort() {
        Pageable pageable = Paging.pageable(Optional.empty(), Optional.empty(), 10,
                Optional.of(SortingEnum.RELEASE_YEAR), SortingEnum.NAME,
                Optional.of(SortingOrder.DESCENDING), SortingOrder.ASCENDING);

        var orders = pageable.getSort().stream().toList();
        assertEquals(2, orders.size());
        assertEquals(SortingEnum.RELEASE_YEAR.getDatabaseString(), orders.get(0).getProperty());
        assertEquals("id", orders.get(1).getProperty());
    }

    @Test
    void clampsPageSizeToMaximum() {
        Pageable pageable = Paging.pageable(Optional.empty(), Optional.of(1_000_000), 10,
                Optional.empty(), SortingEnum.NAME, Optional.empty(), SortingOrder.ASCENDING);

        assertEquals(Paging.MAX_PAGE_SIZE, pageable.getPageSize());
    }

    @Test
    void clampsNegativePageAndSize() {
        Pageable pageable = Paging.pageable(Optional.of(-1), Optional.of(0), 10,
                Optional.empty(), SortingEnum.NAME, Optional.empty(), SortingOrder.ASCENDING);

        assertEquals(0, pageable.getPageNumber());
        assertEquals(1, pageable.getPageSize());
    }

    @Test
    void inOrderRestoresTheRankedOrderAndDropsVanishedIds() {
        ShowEntity first = show();
        ShowEntity second = show();
        UUID deleted = UUID.randomUUID();
        ShowRepository repository = mock(ShowRepository.class);
        // findAllById gives no order guarantee and silently omits ids that no longer exist.
        when(repository.findAllById(anyIterable())).thenReturn(List.of(second, first));

        assertEquals(List.of(first, second),
                Paging.inOrder(repository, List.of(first.getId(), deleted, second.getId())));
    }

    private static ShowEntity show() {
        ShowEntity show = ShowEntity.builder().name("Show").releaseYear(2020).build();
        show.setId(UUID.randomUUID());
        return show;
    }
}
