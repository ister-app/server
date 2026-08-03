package app.ister.api.controller;

import app.ister.core.filter.FilterKind;
import app.ister.core.filter.MediaFilter;
import app.ister.core.enums.SortingEnum;
import app.ister.core.enums.SortingOrder;
import app.ister.core.service.FilterQueryService;
import app.ister.core.service.LibraryAccessService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Shared filtered-path resolution for the browse queries: when a custom filter is present the
 * page comes from {@link FilterQueryService} instead of the per-kind repository, with the same
 * library-access semantics as the unfiltered path (explicit libraryId checked up-front, otherwise
 * the caller's allowed set; admins pass null = all).
 */
@Component
@RequiredArgsConstructor
class FilteredBrowse {

    private final FilterQueryService filterQueryService;
    private final LibraryAccessService libraryAccessService;

    /** Empty when no filter was given: the caller continues on its unfiltered path. */
    <T> Optional<Page<T>> page(FilterKind kind, Optional<MediaFilter> filter,
                               SortingEnum sorting, SortingOrder sortingOrder,
                               Optional<UUID> libraryId, Pageable pageable, Authentication authentication) {
        if (filter.isEmpty()) {
            return Optional.empty();
        }
        UUID scope = libraryId.orElse(null);
        if (scope != null && !libraryAccessService.canAccess(scope, authentication)) {
            return Optional.of(Page.empty(pageable));
        }
        Set<UUID> allowed = libraryAccessService.allowedLibraryIds(authentication).orElse(null);
        return Optional.of(filterQueryService.page(kind, filter.get(), sorting, sortingOrder,
                new FilterQueryService.FilterScope(allowed, scope, authentication.getName()), pageable));
    }
}
