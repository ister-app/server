package app.ister.api.controller;

import app.ister.core.entity.CreditEntity;
import app.ister.core.entity.PersonEntity;
import app.ister.core.enums.CreditType;
import app.ister.core.repository.BookRepository;
import app.ister.core.repository.CreditRepository;
import app.ister.core.repository.ImageRepository;
import app.ister.core.repository.LibraryRepository;
import app.ister.core.repository.PersonRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.graphql.test.autoconfigure.GraphQlTest;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.graphql.test.tester.GraphQlTester;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Schema-wiring test: executes real GraphQL queries against the schema so that the renamed
 * Person type, the persons/personById queries and the credits/images resolvers are exercised.
 */
@GraphQlTest(PersonController.class)
class PersonControllerGraphQlTest {

    @Autowired
    private GraphQlTester graphQlTester;

    @MockitoBean
    private app.ister.core.service.LibraryAccessService libraryAccessService;

    @MockitoBean
    private app.ister.core.service.FilterQueryService filterQueryService;

    @MockitoBean
    private PersonRepository personRepository;

    @MockitoBean
    private ImageRepository imageRepository;

    @MockitoBean
    private LibraryRepository libraryRepository;

    @MockitoBean
    private CreditRepository creditRepository;

    @MockitoBean
    private BookRepository bookRepository;

    @MockitoBean
    private app.ister.core.repository.TrackRepository trackRepository;


    @org.junit.jupiter.api.BeforeEach
    void authenticateAsUser() {
        org.mockito.Mockito.lenient().when(libraryAccessService.allowedLibraryIds(org.mockito.ArgumentMatchers.any()))
                .thenReturn(java.util.Optional.empty());
        org.mockito.Mockito.lenient().when(libraryAccessService.canAccess(
                org.mockito.ArgumentMatchers.<app.ister.core.entity.LibraryEntity>any(),
                org.mockito.ArgumentMatchers.any())).thenReturn(true);
        org.mockito.Mockito.lenient().when(libraryAccessService.canAccess(
                org.mockito.ArgumentMatchers.<java.util.UUID>any(),
                org.mockito.ArgumentMatchers.any())).thenReturn(true);
        org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(
                new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(
                        "test-user", null,
                        java.util.List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_user"))));
    }

    @org.junit.jupiter.api.AfterEach
    void clearAuthentication() {
        org.springframework.security.core.context.SecurityContextHolder.clearContext();
    }

    @Test
    void personsQueryResolvesPageWithBirthYearAndCredits() {
        PersonEntity person = PersonEntity.builder().name("Lady Gaga").birthYear(1986).build();
        person.setId(UUID.randomUUID());
        CreditEntity credit = CreditEntity.builder()
                .characterName("Ally").creditType(CreditType.CAST).castOrder(0).build();
        credit.setId(UUID.randomUUID());
        when(personRepository.findAll(any(Pageable.class))).thenReturn(new PageImpl<>(List.of(person)));
        when(creditRepository.findByPersonEntityId(eq(person.getId()), any(Sort.class))).thenReturn(List.of(credit));
        when(imageRepository.findByPersonEntityIdIn(anyCollection())).thenReturn(List.of());

        assertDoesNotThrow(() -> graphQlTester.document("""
                        { persons(size: 10) {
                            totalElements
                            content { name birthYear credits { characterName creditType castOrder } images { id } }
                        } }
                        """)
                .execute()
                .path("persons.totalElements").entity(Long.class).isEqualTo(1L)
                .path("persons.content[0].name").entity(String.class).isEqualTo("Lady Gaga")
                .path("persons.content[0].birthYear").entity(Integer.class).isEqualTo(1986)
                .path("persons.content[0].credits[0].characterName").entity(String.class).isEqualTo("Ally")
                .path("persons.content[0].credits[0].creditType").entity(String.class).isEqualTo("CAST")
                .path("persons.content[0].images").entityList(Object.class).hasSize(0));
    }

    @Test
    void personByIdResolvesLibrarylessPersonWithCredits() {
        PersonEntity person = PersonEntity.builder().name("Cast Only").build();
        person.setId(UUID.randomUUID());
        CreditEntity credit = CreditEntity.builder()
                .characterName("Neo").creditType(CreditType.CAST).castOrder(0).build();
        credit.setId(UUID.randomUUID());
        when(personRepository.findById(person.getId())).thenReturn(java.util.Optional.of(person));
        when(creditRepository.findByPersonEntityId(eq(person.getId()), any(Sort.class))).thenReturn(List.of(credit));

        assertDoesNotThrow(() -> graphQlTester.document("""
                        { personById(id: "%s") { name credits { characterName } } }
                        """.formatted(person.getId()))
                .execute()
                .path("personById.name").entity(String.class).isEqualTo("Cast Only")
                .path("personById.credits[0].characterName").entity(String.class).isEqualTo("Neo"));
    }

    @Test
    void topTrackListsResolveInRepositoryOrder() {
        PersonEntity person = PersonEntity.builder().name("Lady Gaga").build();
        person.setId(UUID.randomUUID());
        app.ister.core.entity.TrackEntity first = app.ister.core.entity.TrackEntity.builder().number(3).discNumber(1).build();
        first.setId(UUID.randomUUID());
        app.ister.core.entity.TrackEntity second = app.ister.core.entity.TrackEntity.builder().number(1).discNumber(1).build();
        second.setId(UUID.randomUUID());
        when(personRepository.findById(person.getId())).thenReturn(java.util.Optional.of(person));
        // Repository ranks first before second; findAllById returns them in the other order.
        when(trackRepository.findTopPlayedTrackIdsForPerson(eq(person.getId()), any(), any(), eq(10), eq(0)))
                .thenReturn(List.of(first.getId(), second.getId()));
        when(trackRepository.findRecentlyPlayedTrackIdsForPerson(eq(person.getId()), any(), any(), eq(5), eq(0)))
                .thenReturn(List.of(second.getId()));
        when(trackRepository.findTopRatedTrackIdsForPerson(eq(person.getId()), any(), eq(10), eq(0)))
                .thenReturn(List.of());
        when(trackRepository.findAllById(anyCollection())).thenAnswer(invocation -> {
            java.util.Collection<UUID> ids = invocation.getArgument(0);
            return java.util.stream.Stream.of(second, first).filter(t -> ids.contains(t.getId())).toList();
        });

        assertDoesNotThrow(() -> graphQlTester.document("""
                        { personById(id: "%s") {
                            topPlayedTracks { id }
                            recentlyPlayedTracks(limit: 5) { id }
                            topRatedTracks { id }
                        } }
                        """.formatted(person.getId()))
                .execute()
                .path("personById.topPlayedTracks[0].id").entity(String.class).isEqualTo(first.getId().toString())
                .path("personById.topPlayedTracks[1].id").entity(String.class).isEqualTo(second.getId().toString())
                .path("personById.recentlyPlayedTracks[0].id").entity(String.class).isEqualTo(second.getId().toString())
                .path("personById.topRatedTracks").entityList(Object.class).hasSize(0));
    }

    @Test
    void topTrackListsUseLibraryScopedQueryForRestrictedUsers() {
        PersonEntity person = PersonEntity.builder().name("Scoped").build();
        person.setId(UUID.randomUUID());
        UUID allowedLibrary = UUID.randomUUID();
        when(personRepository.findById(person.getId())).thenReturn(java.util.Optional.of(person));
        when(libraryAccessService.allowedLibraryIds(any())).thenReturn(java.util.Optional.of(java.util.Set.of(allowedLibrary)));
        when(creditRepository.hasCreditInLibraries(any(), anyCollection())).thenReturn(true);
        when(trackRepository.findTopPlayedTrackIdsForPersonInLibraries(eq(person.getId()), any(), anyCollection(), any(), eq(10), eq(0)))
                .thenReturn(List.of());
        when(trackRepository.findAllById(anyCollection())).thenReturn(List.of());

        assertDoesNotThrow(() -> graphQlTester.document("""
                        { personById(id: "%s") { topPlayedTracks { id } } }
                        """.formatted(person.getId()))
                .execute()
                .path("personById.topPlayedTracks").entityList(Object.class).hasSize(0));
        verify(trackRepository)
                .findTopPlayedTrackIdsForPersonInLibraries(eq(person.getId()), any(), anyCollection(), any(), eq(10), eq(0));
    }

    @Test
    void personByIdReturnsNullWhenNotFound() {
        when(personRepository.findById(any(UUID.class))).thenReturn(java.util.Optional.empty());

        assertDoesNotThrow(() -> graphQlTester.document("""
                        { personById(id: "%s") { name } }
                        """.formatted(UUID.randomUUID()))
                .execute()
                .path("personById").valueIsNull());
    }
}
