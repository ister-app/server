package app.ister.core.service;

import app.ister.core.entity.LibraryEntity;
import app.ister.core.entity.SavedViewEntity;
import app.ister.core.entity.UserEntity;
import app.ister.core.enums.SortingEnum;
import app.ister.core.enums.SortingOrder;
import app.ister.core.filter.FilterJson;
import app.ister.core.filter.FilterKind;
import app.ister.core.filter.MediaFilter;
import app.ister.core.repository.LibraryRepository;
import app.ister.core.repository.SavedViewRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The calling user's custom views ("smart playlists"). Views are strictly personal: every lookup
 * goes through the owner, and someone else's view id behaves like a missing one — the same
 * not-found semantics the play-queue and library-access paths use.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SavedViewService {

    private final SavedViewRepository savedViewRepository;
    private final LibraryRepository libraryRepository;
    private final LibraryAccessService libraryAccessService;
    private final FilterQueryService filterQueryService;
    private final UserService userService;

    /** The caller's views, by name; optionally narrowed to one library and/or one kind. */
    @Transactional(readOnly = true)
    public List<SavedViewEntity> savedViews(Authentication authentication, UUID libraryId, FilterKind kind) {
        UserEntity user = userService.getOrCreateUser(authentication);
        return savedViewRepository.findByUserEntityOrderByNameAsc(user).stream()
                .filter(view -> libraryId == null
                        || (view.getLibraryEntity() != null && libraryId.equals(view.getLibraryEntity().getId())))
                .filter(view -> kind == null || view.getKind() == kind)
                .toList();
    }

    /** The caller's own view, or empty for an unknown id and for someone else's view. */
    @Transactional(readOnly = true)
    public Optional<SavedViewEntity> ownedView(Authentication authentication, UUID id) {
        UserEntity user = userService.getOrCreateUser(authentication);
        return savedViewRepository.findById(id)
                .filter(view -> view.getUserEntity().getId().equals(user.getId()));
    }

    // Sonar FP: Lombok @SuperBuilder declares builder() on the subclass itself
    @SuppressWarnings("java:S3252")
    @Transactional
    public SavedViewEntity create(Authentication authentication, String name, FilterKind kind, UUID libraryId,
                                  MediaFilter filter, SortingEnum sorting, SortingOrder sortingOrder) {
        SavedViewEntity view = SavedViewEntity.builder()
                .userEntity(userService.getOrCreateUser(authentication))
                .build();
        apply(view, name, kind, libraryId, filter, sorting, sortingOrder, authentication);
        return savedViewRepository.save(view);
    }

    @Transactional
    public SavedViewEntity update(Authentication authentication, UUID id, String name, FilterKind kind,
                                  UUID libraryId, MediaFilter filter, SortingEnum sorting,
                                  SortingOrder sortingOrder) {
        SavedViewEntity view = ownedView(authentication, id)
                .orElseThrow(() -> new IllegalArgumentException("Saved view not found"));
        apply(view, name, kind, libraryId, filter, sorting, sortingOrder, authentication);
        return savedViewRepository.save(view);
    }

    /** Deleting never touches play queues created from the view: they carry their own pinned copy. */
    @Transactional
    public boolean delete(Authentication authentication, UUID id) {
        SavedViewEntity view = ownedView(authentication, id)
                .orElseThrow(() -> new IllegalArgumentException("Saved view not found"));
        savedViewRepository.delete(view);
        return true;
    }

    private void apply(SavedViewEntity view, String name, FilterKind kind, UUID libraryId, MediaFilter filter,
                       SortingEnum sorting, SortingOrder sortingOrder, Authentication authentication) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("A saved view needs a name");
        }
        filterQueryService.validate(kind, filter);
        LibraryEntity library = null;
        if (libraryId != null) {
            library = libraryRepository.findById(libraryId)
                    .filter(lib -> libraryAccessService.canAccess(lib, authentication))
                    .orElseThrow(() -> new IllegalArgumentException("Library not found"));
        }
        view.setName(name.trim());
        view.setKind(kind);
        view.setLibraryEntity(library);
        view.setFilter(FilterJson.writeFilter(filter));
        view.setSorting(sorting);
        view.setSortingOrder(sortingOrder);
    }
}
