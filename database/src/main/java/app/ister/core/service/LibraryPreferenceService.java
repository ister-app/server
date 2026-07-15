package app.ister.core.service;

import app.ister.core.entity.LibraryEntity;
import app.ister.core.entity.UserEntity;
import app.ister.core.entity.UserLibraryPreferenceEntity;
import app.ister.core.enums.SortingEnum;
import app.ister.core.enums.SortingOrder;
import app.ister.core.repository.LibraryRepository;
import app.ister.core.repository.UserLibraryPreferenceRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;

/**
 * Stores each user's per-library grid sort preference (key + direction). Kept server-side so the
 * choice applies to every client the user has. A library the user never touched has no row and
 * sorts by name ascending — the order the grids have always shown.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class LibraryPreferenceService {
    /** What a library grid has always shown, and what an unset preference means. */
    public static final SortingEnum DEFAULT_SORTING = SortingEnum.NAME;
    public static final SortingOrder DEFAULT_SORTING_ORDER = SortingOrder.ASCENDING;

    private final UserService userService;
    private final UserLibraryPreferenceRepository userLibraryPreferenceRepository;
    private final LibraryRepository libraryRepository;

    /** The caller's sort key for this library, or the default when they never set one. */
    @Transactional(readOnly = true)
    public SortingEnum getSorting(Authentication authentication, UUID libraryId) {
        return find(authentication, libraryId)
                .map(UserLibraryPreferenceEntity::getSorting)
                .orElse(DEFAULT_SORTING);
    }

    /** The caller's sort direction for this library, or the default when they never set one. */
    @Transactional(readOnly = true)
    public SortingOrder getSortingOrder(Authentication authentication, UUID libraryId) {
        return find(authentication, libraryId)
                .map(UserLibraryPreferenceEntity::getSortingOrder)
                .orElse(DEFAULT_SORTING_ORDER);
    }

    private Optional<UserLibraryPreferenceEntity> find(Authentication authentication, UUID libraryId) {
        UserEntity userEntity = userService.getOrCreateUser(authentication);
        return userLibraryPreferenceRepository.findByUserEntityAndLibraryEntity(userEntity, library(libraryId));
    }

    /**
     * Sets the caller's sort key and direction for this library.
     *
     * @throws NoSuchElementException if the library does not exist
     */
    @Transactional
    public void setSorting(Authentication authentication, UUID libraryId, SortingEnum sorting, SortingOrder sortingOrder) {
        UserEntity userEntity = userService.getOrCreateUser(authentication);
        LibraryEntity libraryEntity = library(libraryId);
        Optional<UserLibraryPreferenceEntity> existing =
                userLibraryPreferenceRepository.findByUserEntityAndLibraryEntity(userEntity, libraryEntity);

        if (existing.isPresent()) {
            UserLibraryPreferenceEntity preference = existing.get();
            preference.setSorting(sorting);
            preference.setSortingOrder(sortingOrder);
            userLibraryPreferenceRepository.save(preference);
        } else {
            userLibraryPreferenceRepository.save(UserLibraryPreferenceEntity.builder()
                    .userEntity(userEntity)
                    .libraryEntity(libraryEntity)
                    .sorting(sorting)
                    .sortingOrder(sortingOrder)
                    .build());
        }
    }

    private LibraryEntity library(UUID id) {
        return libraryRepository.findById(id)
                .orElseThrow(() -> new NoSuchElementException("Library not found: " + id));
    }
}
