package app.ister.api.controller;

import app.ister.core.entity.LibraryEntity;
import app.ister.core.entity.UserEntity;
import app.ister.core.entity.UserLibraryAccessEntity;
import app.ister.core.repository.LibraryRepository;
import app.ister.core.repository.UserLibraryAccessRepository;
import app.ister.core.repository.UserRepository;
import app.ister.core.service.LibraryAccessService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UserAdminControllerTest {

    @InjectMocks
    private UserAdminController subject;

    @Mock
    private UserRepository userRepository;

    @Mock
    private LibraryRepository libraryRepository;

    @Mock
    private UserLibraryAccessRepository userLibraryAccessRepository;

    @Mock
    private LibraryAccessService libraryAccessService;

    private static UserEntity user(UUID id, String name, String email, boolean admin) {
        UserEntity user = UserEntity.builder().externalId("ext-" + name).name(name).email(email).admin(admin).build();
        user.setId(id);
        return user;
    }

    private static LibraryEntity library(UUID id) {
        LibraryEntity library = LibraryEntity.builder().name("Library " + id).build();
        library.setId(id);
        return library;
    }

    @Test
    void usersMapsEntitiesToRecords() {
        UUID adminId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        when(userRepository.findAll()).thenReturn(List.of(
                user(adminId, "Admin", "admin@example.com", true),
                user(userId, "User", "user@example.com", false)));

        List<UserAdminController.User> result = subject.users();

        assertEquals(2, result.size());
        assertEquals(new UserAdminController.User(adminId, "Admin", "admin@example.com", true), result.get(0));
        assertEquals(new UserAdminController.User(userId, "User", "user@example.com", false), result.get(1));
    }

    @Test
    void setLibraryVisibleToAllUpdatesFlagAndInvalidatesCache() {
        UUID libraryId = UUID.randomUUID();
        LibraryEntity library = library(libraryId);
        assertTrue(library.isVisibleToAll());
        when(libraryRepository.findById(libraryId)).thenReturn(Optional.of(library));

        Boolean result = subject.setLibraryVisibleToAll(libraryId, false);

        assertTrue(result);
        assertFalse(library.isVisibleToAll());
        verify(libraryRepository).save(library);
        verify(libraryAccessService).invalidateCache();
    }

    @Test
    void setLibraryVisibleToAllThrowsWhenLibraryUnknown() {
        UUID libraryId = UUID.randomUUID();
        when(libraryRepository.findById(libraryId)).thenReturn(Optional.empty());

        assertThrows(NoSuchElementException.class, () -> subject.setLibraryVisibleToAll(libraryId, true));
    }

    @Test
    void setUserLibraryAccessGrantSavesAccessRow() {
        UUID userId = UUID.randomUUID();
        UUID libraryId = UUID.randomUUID();
        UserEntity user = user(userId, "User", "user@example.com", false);
        LibraryEntity library = library(libraryId);
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(libraryRepository.findById(libraryId)).thenReturn(Optional.of(library));
        when(userLibraryAccessRepository.findByUserEntityIdAndLibraryEntityId(userId, libraryId)).thenReturn(Optional.empty());

        Boolean result = subject.setUserLibraryAccess(userId, libraryId, true);

        assertTrue(result);
        ArgumentCaptor<UserLibraryAccessEntity> captor = ArgumentCaptor.forClass(UserLibraryAccessEntity.class);
        verify(userLibraryAccessRepository).save(captor.capture());
        assertEquals(user, captor.getValue().getUserEntity());
        assertEquals(library, captor.getValue().getLibraryEntity());
        verify(libraryAccessService).invalidateCache();
    }

    @Test
    void setUserLibraryAccessGrantIsIdempotent() {
        UUID userId = UUID.randomUUID();
        UUID libraryId = UUID.randomUUID();
        UserEntity user = user(userId, "User", "user@example.com", false);
        LibraryEntity library = library(libraryId);
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(libraryRepository.findById(libraryId)).thenReturn(Optional.of(library));
        when(userLibraryAccessRepository.findByUserEntityIdAndLibraryEntityId(userId, libraryId)).thenReturn(Optional.of(
                UserLibraryAccessEntity.builder().userEntity(user).libraryEntity(library).build()));

        Boolean result = subject.setUserLibraryAccess(userId, libraryId, true);

        assertTrue(result);
        verify(userLibraryAccessRepository, never()).save(any());
        verify(libraryAccessService).invalidateCache();
    }

    @Test
    void setUserLibraryAccessRevokeDeletesExistingRow() {
        UUID userId = UUID.randomUUID();
        UUID libraryId = UUID.randomUUID();
        UserEntity user = user(userId, "User", "user@example.com", false);
        LibraryEntity library = library(libraryId);
        UserLibraryAccessEntity access = UserLibraryAccessEntity.builder().userEntity(user).libraryEntity(library).build();
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(libraryRepository.findById(libraryId)).thenReturn(Optional.of(library));
        when(userLibraryAccessRepository.findByUserEntityIdAndLibraryEntityId(userId, libraryId)).thenReturn(Optional.of(access));

        Boolean result = subject.setUserLibraryAccess(userId, libraryId, false);

        assertTrue(result);
        verify(userLibraryAccessRepository).delete(access);
        verify(libraryAccessService).invalidateCache();
    }

    @Test
    void setUserLibraryAccessRevokeWithoutRowDoesNothing() {
        UUID userId = UUID.randomUUID();
        UUID libraryId = UUID.randomUUID();
        when(userRepository.findById(userId)).thenReturn(Optional.of(user(userId, "User", "user@example.com", false)));
        when(libraryRepository.findById(libraryId)).thenReturn(Optional.of(library(libraryId)));
        when(userLibraryAccessRepository.findByUserEntityIdAndLibraryEntityId(userId, libraryId)).thenReturn(Optional.empty());

        Boolean result = subject.setUserLibraryAccess(userId, libraryId, false);

        assertTrue(result);
        verify(userLibraryAccessRepository, never()).delete(any());
        verify(libraryAccessService).invalidateCache();
    }

    @Test
    void setUserLibraryAccessThrowsWhenUserUnknown() {
        UUID userId = UUID.randomUUID();
        when(userRepository.findById(userId)).thenReturn(Optional.empty());

        assertThrows(NoSuchElementException.class, () -> subject.setUserLibraryAccess(userId, UUID.randomUUID(), true));
    }

    @Test
    void setUserLibraryAccessThrowsWhenLibraryUnknown() {
        UUID userId = UUID.randomUUID();
        UUID libraryId = UUID.randomUUID();
        when(userRepository.findById(userId)).thenReturn(Optional.of(user(userId, "User", "user@example.com", false)));
        when(libraryRepository.findById(libraryId)).thenReturn(Optional.empty());

        assertThrows(NoSuchElementException.class, () -> subject.setUserLibraryAccess(userId, libraryId, true));
    }
}
