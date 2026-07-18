package app.ister.api.controller;

import app.ister.core.entity.LibraryEntity;
import app.ister.core.enums.SortingEnum;
import app.ister.core.enums.SortingOrder;
import app.ister.core.repository.LibraryRepository;
import app.ister.core.repository.UserLibraryPreferenceRepository;
import app.ister.core.service.LibraryAccessService;
import app.ister.core.service.LibraryPreferenceService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.Authentication;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class LibraryControllerTest {

    @InjectMocks
    private LibraryController subject;

    @Mock
    private LibraryRepository libraryRepository;

    @Mock
    private UserLibraryPreferenceRepository userLibraryPreferenceRepository;

    @Mock
    private LibraryPreferenceService libraryPreferenceService;

    @Mock
    private LibraryAccessService libraryAccessService;

    @Mock
    private Authentication auth;

    @Test
    void librariesReturnsAllFromRepository() {
        LibraryEntity library = LibraryEntity.builder().name("Movies").build();
        when(libraryAccessService.allowedLibraryIds(auth)).thenReturn(java.util.Optional.empty());
        when(libraryRepository.findAll()).thenReturn(List.of(library));

        List<LibraryEntity> result = subject.libraries(auth);

        assertEquals(1, result.size());
        assertEquals(library, result.get(0));
        verify(libraryRepository).findAll();
    }

    @Test
    void librariesReturnsEmptyListWhenNoneExist() {
        when(libraryAccessService.allowedLibraryIds(auth)).thenReturn(java.util.Optional.empty());
        when(libraryRepository.findAll()).thenReturn(List.of());

        List<LibraryEntity> result = subject.libraries(auth);

        assertTrue(result.isEmpty());
    }

    @Test
    void setLibrarySortingDelegatesToTheServiceAndReturnsTrue() {
        Authentication authentication = mock(Authentication.class);
        when(libraryAccessService.canAccess(any(UUID.class), any())).thenReturn(true);
        UUID libraryId = UUID.randomUUID();

        Boolean result = subject.setLibrarySorting(
                libraryId, SortingEnum.RELEASE_YEAR, SortingOrder.DESCENDING, authentication);

        assertTrue(result);
        verify(libraryPreferenceService)
                .setSorting(authentication, libraryId, SortingEnum.RELEASE_YEAR, SortingOrder.DESCENDING);
    }
}
