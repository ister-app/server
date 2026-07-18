package app.ister.core.service;

import app.ister.core.entity.LibraryEntity;
import app.ister.core.entity.UserEntity;
import app.ister.core.entity.UserLibraryAccessEntity;
import app.ister.core.repository.LibraryRepository;
import app.ister.core.repository.UserLibraryAccessRepository;
import app.ister.core.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LibraryAccessServiceTest {

    @InjectMocks
    private LibraryAccessService subject;

    @Mock
    private UserRepository userRepository;

    @Mock
    private UserLibraryAccessRepository userLibraryAccessRepository;

    @Mock
    private LibraryRepository libraryRepository;

    @Mock
    private Authentication authentication;

    private static LibraryEntity library(UUID id) {
        LibraryEntity library = LibraryEntity.builder().name("Library " + id).build();
        library.setId(id);
        return library;
    }

    private void givenAdminAuthorities() {
        doReturn(List.of(new SimpleGrantedAuthority("ROLE_admin"))).when(authentication).getAuthorities();
    }

    private void givenNonAdminJwtUser(String name) {
        when(authentication.getName()).thenReturn(name);
        doReturn(List.of(new SimpleGrantedAuthority("ROLE_user"))).when(authentication).getAuthorities();
        lenient().when(authentication.getPrincipal()).thenReturn(mock(Jwt.class));
    }

    @Test
    void adminAuthorityMeansAllLibraries() {
        when(authentication.getName()).thenReturn("admin-1");
        givenAdminAuthorities();

        assertTrue(subject.isAdmin(authentication));
        assertEquals(Optional.empty(), subject.allowedLibraryIds(authentication));
    }

    @Test
    void nonAdminSeesVisibleToAllPlusGrantedLibraries() {
        UUID visibleId = UUID.randomUUID();
        UUID grantedId = UUID.randomUUID();
        givenNonAdminJwtUser("user-1");
        when(libraryRepository.findByVisibleToAllTrue()).thenReturn(List.of(library(visibleId)));
        when(userLibraryAccessRepository.findByUserEntityExternalId("user-1")).thenReturn(List.of(
                UserLibraryAccessEntity.builder().libraryEntity(library(grantedId)).build()));

        Optional<Set<UUID>> result = subject.allowedLibraryIds(authentication);

        assertTrue(result.isPresent());
        assertEquals(Set.of(visibleId, grantedId), result.get());
    }

    @Test
    void canAccessChecksAllowedIdsForNonAdmin() {
        UUID allowedId = UUID.randomUUID();
        givenNonAdminJwtUser("user-2");
        when(libraryRepository.findByVisibleToAllTrue()).thenReturn(List.of(library(allowedId)));
        when(userLibraryAccessRepository.findByUserEntityExternalId("user-2")).thenReturn(List.of());

        assertTrue(subject.canAccess(allowedId, authentication));
        assertFalse(subject.canAccess(UUID.randomUUID(), authentication));
    }

    @Test
    void canAccessDelegatesOnLibraryEntityId() {
        UUID allowedId = UUID.randomUUID();
        givenNonAdminJwtUser("user-3");
        when(libraryRepository.findByVisibleToAllTrue()).thenReturn(List.of(library(allowedId)));
        when(userLibraryAccessRepository.findByUserEntityExternalId("user-3")).thenReturn(List.of());

        assertTrue(subject.canAccess(library(allowedId), authentication));
        assertFalse(subject.canAccess(library(UUID.randomUUID()), authentication));
    }

    @Test
    void canAccessWithNullLibraryIdIsFalse() {
        assertFalse(subject.canAccess((UUID) null, authentication));
    }

    @Test
    void allowedLibraryIdsAreCachedUntilInvalidated() {
        UUID firstId = UUID.randomUUID();
        UUID secondId = UUID.randomUUID();
        givenNonAdminJwtUser("user-4");
        when(libraryRepository.findByVisibleToAllTrue())
                .thenReturn(List.of(library(firstId)))
                .thenReturn(List.of(library(firstId), library(secondId)));
        when(userLibraryAccessRepository.findByUserEntityExternalId("user-4")).thenReturn(List.of());

        assertEquals(Optional.of(Set.of(firstId)), subject.allowedLibraryIds(authentication));
        // Grants changed on the server, but the cache still serves the old snapshot.
        assertEquals(Optional.of(Set.of(firstId)), subject.allowedLibraryIds(authentication));

        subject.invalidateCache();

        assertEquals(Optional.of(Set.of(firstId, secondId)), subject.allowedLibraryIds(authentication));
    }

    @Test
    void streamTokenFallsBackToAdminSnapshotOnUserRow() {
        when(authentication.getName()).thenReturn("stream-user");
        doReturn(List.of(new SimpleGrantedAuthority("ROLE_user"))).when(authentication).getAuthorities();
        when(authentication.getPrincipal()).thenReturn("stream-token");
        when(userRepository.findByExternalId("stream-user")).thenReturn(Optional.of(
                UserEntity.builder().externalId("stream-user").admin(true).build()));

        assertTrue(subject.isAdmin(authentication));
        assertEquals(Optional.empty(), subject.allowedLibraryIds(authentication));
    }
}
