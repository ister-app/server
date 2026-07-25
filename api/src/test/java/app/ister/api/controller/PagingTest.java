package app.ister.api.controller;

import app.ister.core.enums.SortingEnum;
import app.ister.core.enums.SortingOrder;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
}
