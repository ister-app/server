package app.ister.api.controller;

import app.ister.core.entity.CreditEntity;
import app.ister.core.entity.MovieEntity;
import app.ister.core.entity.PersonEntity;
import app.ister.core.enums.CreditType;
import app.ister.core.repository.CreditRepository;
import app.ister.core.repository.LibraryRepository;
import app.ister.core.repository.MovieRepository;
import app.ister.core.repository.WatchStatusRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.graphql.test.autoconfigure.GraphQlTest;
import org.springframework.graphql.test.tester.GraphQlTester;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.when;

/**
 * Schema-wiring test for the cast batch mapping: movieById → cast → person.
 */
@GraphQlTest({MovieController.class, CreditController.class})
class CreditControllerGraphQlTest {

    @Autowired
    private GraphQlTester graphQlTester;

    @MockitoBean
    private MovieRepository movieRepository;

    @MockitoBean
    private LibraryRepository libraryRepository;

    @MockitoBean
    private WatchStatusRepository watchStatusRepository;

    @MockitoBean
    private CreditRepository creditRepository;

    @Test
    void movieCastResolvesCreditsAndPersons() {
        MovieEntity movie = MovieEntity.builder().name("A Star Is Born").releaseYear(2018).build();
        movie.setId(UUID.randomUUID());
        PersonEntity person = PersonEntity.builder().name("Lady Gaga").birthYear(1986).build();
        person.setId(UUID.randomUUID());
        CreditEntity credit = CreditEntity.builder()
                .personEntity(person).characterName("Ally").creditType(CreditType.CAST).castOrder(1).build();
        credit.setId(UUID.randomUUID());
        credit.setMovieEntity(movie);
        when(movieRepository.findById(movie.getId())).thenReturn(Optional.of(movie));
        when(creditRepository.findByMovieEntityIdIn(anyCollection())).thenReturn(List.of(credit));

        assertDoesNotThrow(() -> graphQlTester.document("""
                        { movieById(id: "%s") {
                            name
                            cast { characterName creditType castOrder person { name birthYear } }
                        } }
                        """.formatted(movie.getId()))
                .execute()
                .path("movieById.cast[0].characterName").entity(String.class).isEqualTo("Ally")
                .path("movieById.cast[0].creditType").entity(String.class).isEqualTo("CAST")
                .path("movieById.cast[0].person.name").entity(String.class).isEqualTo("Lady Gaga")
                .path("movieById.cast[0].person.birthYear").entity(Integer.class).isEqualTo(1986));
    }
}
