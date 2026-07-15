package app.ister.api.controller;

import app.ister.core.entity.LibraryEntity;
import app.ister.core.entity.UserLibraryPreferenceEntity;
import app.ister.core.enums.SortingEnum;
import app.ister.core.enums.SortingOrder;
import app.ister.core.repository.LibraryRepository;
import app.ister.core.repository.UserLibraryPreferenceRepository;
import app.ister.core.service.LibraryPreferenceService;
import lombok.RequiredArgsConstructor;
import org.springframework.graphql.data.method.annotation.Argument;
import org.springframework.graphql.data.method.annotation.BatchMapping;
import org.springframework.graphql.data.method.annotation.MutationMapping;
import org.springframework.graphql.data.method.annotation.QueryMapping;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Controller
@RequiredArgsConstructor
public class LibraryController {
    private final LibraryRepository libraryRepository;
    private final UserLibraryPreferenceRepository userLibraryPreferenceRepository;
    private final LibraryPreferenceService libraryPreferenceService;

    @PreAuthorize("hasRole('user')")
    @QueryMapping
    public List<LibraryEntity> libraries() {
        return libraryRepository.findAll();
    }

    @PreAuthorize("hasRole('user')")
    @MutationMapping
    public Boolean setLibrarySorting(
            @Argument UUID libraryId,
            @Argument SortingEnum sorting,
            @Argument SortingOrder sortingOrder,
            Authentication authentication) {
        libraryPreferenceService.setSorting(authentication, libraryId, sorting, sortingOrder);
        return true;
    }

    /**
     * Libraries without a preference row map to the default, so the non-null GraphQL fields always
     * resolve.
     */
    @BatchMapping(typeName = "Library", field = "sorting")
    public Map<LibraryEntity, SortingEnum> sorting(
            List<LibraryEntity> libraries, Authentication authentication) {
        Map<UUID, SortingEnum> byId = preferences(libraries, authentication).stream()
                .collect(Collectors.toMap(p -> p.getLibraryEntity().getId(),
                        UserLibraryPreferenceEntity::getSorting));
        return libraries.stream().collect(Collectors.toMap(
                library -> library,
                library -> byId.getOrDefault(library.getId(), LibraryPreferenceService.DEFAULT_SORTING)));
    }

    @BatchMapping(typeName = "Library", field = "sortingOrder")
    public Map<LibraryEntity, SortingOrder> sortingOrder(
            List<LibraryEntity> libraries, Authentication authentication) {
        Map<UUID, SortingOrder> byId = preferences(libraries, authentication).stream()
                .collect(Collectors.toMap(p -> p.getLibraryEntity().getId(),
                        UserLibraryPreferenceEntity::getSortingOrder));
        return libraries.stream().collect(Collectors.toMap(
                library -> library,
                library -> byId.getOrDefault(library.getId(), LibraryPreferenceService.DEFAULT_SORTING_ORDER)));
    }

    private List<UserLibraryPreferenceEntity> preferences(
            List<LibraryEntity> libraries, Authentication authentication) {
        return userLibraryPreferenceRepository
                .findByUserEntityExternalIdAndLibraryEntityIn(authentication.getName(), libraries);
    }
}
