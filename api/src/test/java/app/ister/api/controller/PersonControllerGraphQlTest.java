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
    private PersonRepository personRepository;

    @MockitoBean
    private ImageRepository imageRepository;

    @MockitoBean
    private LibraryRepository libraryRepository;

    @MockitoBean
    private CreditRepository creditRepository;

    @MockitoBean
    private BookRepository bookRepository;

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
    void personByIdReturnsNullWhenNotFound() {
        when(personRepository.findById(any(UUID.class))).thenReturn(java.util.Optional.empty());

        assertDoesNotThrow(() -> graphQlTester.document("""
                        { personById(id: "%s") { name } }
                        """.formatted(UUID.randomUUID()))
                .execute()
                .path("personById").valueIsNull());
    }
}
