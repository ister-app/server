package app.ister.api.controller;

import app.ister.core.entity.LibraryEntity;
import app.ister.core.entity.UserEntity;
import app.ister.core.entity.UserLibraryAccessEntity;
import app.ister.core.repository.LibraryRepository;
import app.ister.core.repository.UserLibraryAccessRepository;
import app.ister.core.repository.UserRepository;
import app.ister.core.service.LibraryAccessService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.graphql.data.method.annotation.Argument;
import org.springframework.graphql.data.method.annotation.MutationMapping;
import org.springframework.graphql.data.method.annotation.QueryMapping;
import org.springframework.graphql.data.method.annotation.SchemaMapping;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;
import java.util.stream.StreamSupport;

/**
 * Admin management of users and library visibility. The admin role itself is assigned in the
 * OIDC provider (Keycloak realm role 'admin'); this controller only manages what non-admin users
 * may see. Users exist from their first login, so a user who never logged in cannot be granted
 * access yet.
 */
@Slf4j
@Controller
@RequiredArgsConstructor
public class UserAdminController {
    private final UserRepository userRepository;
    private final LibraryRepository libraryRepository;
    private final UserLibraryAccessRepository userLibraryAccessRepository;
    private final LibraryAccessService libraryAccessService;

    public record User(UUID id, String name, String email, boolean isAdmin) {
    }

    @PreAuthorize("hasRole('admin')")
    @QueryMapping
    public List<User> users() {
        return StreamSupport.stream(userRepository.findAll().spliterator(), false)
                .map(user -> new User(user.getId(), user.getName(), user.getEmail(), user.isAdmin()))
                .toList();
    }

    @PreAuthorize("hasRole('admin')")
    @SchemaMapping(typeName = "User", field = "grantedLibraries")
    public List<LibraryEntity> grantedLibraries(User user) {
        return userLibraryAccessRepository.findByUserEntityId(user.id()).stream()
                .map(UserLibraryAccessEntity::getLibraryEntity)
                .toList();
    }

    @PreAuthorize("hasRole('admin')")
    @MutationMapping
    public Boolean setLibraryVisibleToAll(@Argument UUID libraryId, @Argument boolean visibleToAll) {
        LibraryEntity library = libraryRepository.findById(libraryId)
                .orElseThrow(() -> new NoSuchElementException("Library not found: " + libraryId));
        library.setVisibleToAll(visibleToAll);
        libraryRepository.save(library);
        libraryAccessService.invalidateCache();
        return true;
    }

    @PreAuthorize("hasRole('admin')")
    @MutationMapping
    @Transactional
    public Boolean setUserLibraryAccess(@Argument UUID userId, @Argument UUID libraryId, @Argument boolean granted) {
        UserEntity user = userRepository.findById(userId)
                .orElseThrow(() -> new NoSuchElementException("User not found: " + userId));
        LibraryEntity library = libraryRepository.findById(libraryId)
                .orElseThrow(() -> new NoSuchElementException("Library not found: " + libraryId));
        if (granted) {
            if (userLibraryAccessRepository.findByUserEntityIdAndLibraryEntityId(userId, libraryId).isEmpty()) {
                userLibraryAccessRepository.save(UserLibraryAccessEntity.builder()
                        .userEntity(user)
                        .libraryEntity(library)
                        .build());
            }
        } else {
            userLibraryAccessRepository.findByUserEntityIdAndLibraryEntityId(userId, libraryId)
                    .ifPresent(userLibraryAccessRepository::delete);
        }
        libraryAccessService.invalidateCache();
        return true;
    }
}
