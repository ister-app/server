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

    /** Everything that defines a saved view; shared by create and update. */
    public record SavedViewSpec(String name, FilterKind kind, UUID libraryId, MediaFilter filter,
                                SortingEnum sorting, SortingOrder sortingOrder) {
    }

    /** The caller's own view, or empty for an unknown id and for someone else's view. */
    @Transactional(readOnly = true)
    public Optional<SavedViewEntity> ownedView(Authentication authentication, UUID id) {
        return findOwnedView(authentication, id);
    }

    private Optional<SavedViewEntity> findOwnedView(Authentication authentication, UUID id) {
        UserEntity user = userService.getOrCreateUser(authentication);
        return savedViewRepository.findById(id)
                .filter(view -> view.getUserEntity().getId().equals(user.getId()));
    }

    // Sonar FP: Lombok @SuperBuilder declares builder() on the subclass itself
    @SuppressWarnings("java:S3252")
    @Transactional
    public SavedViewEntity create(Authentication authentication, SavedViewSpec spec) {
        SavedViewEntity view = SavedViewEntity.builder()
                .userEntity(userService.getOrCreateUser(authentication))
                .build();
        apply(view, spec, authentication);
        return savedViewRepository.save(view);
    }

    @Transactional
    public SavedViewEntity update(Authentication authentication, UUID id, SavedViewSpec spec) {
        SavedViewEntity view = findOwnedView(authentication, id)
                .orElseThrow(() -> new IllegalArgumentException("Saved view not found"));
        apply(view, spec, authentication);
        return savedViewRepository.save(view);
    }

    /** Deleting never touches play queues created from the view: they carry their own pinned copy. */
    @Transactional
    public boolean delete(Authentication authentication, UUID id) {
        SavedViewEntity view = findOwnedView(authentication, id)
                .orElseThrow(() -> new IllegalArgumentException("Saved view not found"));
        savedViewRepository.delete(view);
        return true;
    }

    private void apply(SavedViewEntity view, SavedViewSpec spec, Authentication authentication) {
        if (spec.name() == null || spec.name().isBlank()) {
            throw new IllegalArgumentException("A saved view needs a name");
        }
        filterQueryService.validate(spec.kind(), spec.filter());
        LibraryEntity library = null;
        if (spec.libraryId() != null) {
            library = libraryRepository.findById(spec.libraryId())
                    .filter(lib -> libraryAccessService.canAccess(lib, authentication))
                    .orElseThrow(() -> new IllegalArgumentException("Library not found"));
        }
        view.setName(spec.name().trim());
        view.setKind(spec.kind());
        view.setLibraryEntity(library);
        view.setFilter(FilterJson.writeFilter(spec.filter()));
        view.setSorting(spec.sorting());
        view.setSortingOrder(spec.sortingOrder());
    }
}
