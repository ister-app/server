package app.ister.core.service;

import app.ister.core.entity.LibraryEntity;
import app.ister.core.entity.UserEntity;
import app.ister.core.entity.UserLibraryPreferenceEntity;
import app.ister.core.enums.SortingEnum;
import app.ister.core.enums.SortingOrder;
import app.ister.core.repository.LibraryRepository;
import app.ister.core.repository.UserLibraryPreferenceRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.Authentication;

import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LibraryPreferenceServiceTest {

    @InjectMocks
    private LibraryPreferenceService subject;

    @Mock
    private UserService userService;

    @Mock
    private UserLibraryPreferenceRepository userLibraryPreferenceRepository;

    @Mock
    private LibraryRepository libraryRepository;

    @Mock
    private Authentication authentication;

    private UserEntity user;
    private LibraryEntity library;

    @BeforeEach
    void setUp() {
        user = UserEntity.builder().id(UUID.randomUUID()).externalId("user-1").build();
        library = LibraryEntity.builder().name("Movies").build();
        library.setId(UUID.randomUUID());
    }

    private void mockUserAndLibrary() {
        when(userService.getOrCreateUser(authentication)).thenReturn(user);
        when(libraryRepository.findById(library.getId())).thenReturn(Optional.of(library));
    }

    @Test
    void sortingDefaultsToNameWhenTheUserNeverSetOne() {
        mockUserAndLibrary();
        when(userLibraryPreferenceRepository.findByUserEntityAndLibraryEntity(user, library))
                .thenReturn(Optional.empty());

        assertEquals(SortingEnum.NAME, subject.getSorting(authentication, library.getId()));
    }

    @Test
    void sortingOrderDefaultsToAscendingWhenTheUserNeverSetOne() {
        mockUserAndLibrary();
        when(userLibraryPreferenceRepository.findByUserEntityAndLibraryEntity(user, library))
                .thenReturn(Optional.empty());

        assertEquals(SortingOrder.ASCENDING, subject.getSortingOrder(authentication, library.getId()));
    }

    @Test
    void gettersReturnTheStoredPreference() {
        mockUserAndLibrary();
        when(userLibraryPreferenceRepository.findByUserEntityAndLibraryEntity(user, library))
                .thenReturn(Optional.of(UserLibraryPreferenceEntity.builder()
                        .userEntity(user).libraryEntity(library)
                        .sorting(SortingEnum.RELEASE_YEAR).sortingOrder(SortingOrder.DESCENDING).build()));

        assertEquals(SortingEnum.RELEASE_YEAR, subject.getSorting(authentication, library.getId()));
        assertEquals(SortingOrder.DESCENDING, subject.getSortingOrder(authentication, library.getId()));
    }

    @Test
    void setSortingInsertsARowWhenThereIsNone() {
        mockUserAndLibrary();
        when(userLibraryPreferenceRepository.findByUserEntityAndLibraryEntity(user, library))
                .thenReturn(Optional.empty());

        subject.setSorting(authentication, library.getId(), SortingEnum.RELEASE_YEAR, SortingOrder.DESCENDING);

        ArgumentCaptor<UserLibraryPreferenceEntity> saved =
                ArgumentCaptor.forClass(UserLibraryPreferenceEntity.class);
        verify(userLibraryPreferenceRepository).save(saved.capture());
        assertEquals(user, saved.getValue().getUserEntity());
        assertEquals(library, saved.getValue().getLibraryEntity());
        assertEquals(SortingEnum.RELEASE_YEAR, saved.getValue().getSorting());
        assertEquals(SortingOrder.DESCENDING, saved.getValue().getSortingOrder());
    }

    @Test
    void setSortingUpdatesTheExistingRow() {
        mockUserAndLibrary();
        UserLibraryPreferenceEntity existing = UserLibraryPreferenceEntity.builder()
                .userEntity(user).libraryEntity(library)
                .sorting(SortingEnum.NAME).sortingOrder(SortingOrder.ASCENDING).build();
        when(userLibraryPreferenceRepository.findByUserEntityAndLibraryEntity(user, library))
                .thenReturn(Optional.of(existing));

        subject.setSorting(authentication, library.getId(), SortingEnum.DATE_CREATED, SortingOrder.DESCENDING);

        assertEquals(SortingEnum.DATE_CREATED, existing.getSorting());
        assertEquals(SortingOrder.DESCENDING, existing.getSortingOrder());
        verify(userLibraryPreferenceRepository).save(existing);
    }

    @Test
    void setSortingRejectsAnUnknownLibrary() {
        UUID unknownId = UUID.randomUUID();
        when(userService.getOrCreateUser(authentication)).thenReturn(user);
        when(libraryRepository.findById(unknownId)).thenReturn(Optional.empty());

        assertThrows(NoSuchElementException.class,
                () -> subject.setSorting(authentication, unknownId, SortingEnum.NAME, SortingOrder.ASCENDING));
        verify(userLibraryPreferenceRepository, never()).save(org.mockito.ArgumentMatchers.any());
    }
}
