package app.ister.api.controller;

import app.ister.core.entity.EpisodeEntity;
import app.ister.core.entity.ShowEntity;
import app.ister.core.repository.EpisodeRepository;
import app.ister.core.repository.ImageRepository;
import app.ister.core.repository.LibraryRepository;
import app.ister.core.repository.ShowRepository;
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

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.when;

/**
 * Schema-wiring test: executes a real GraphQL query against the schema so that argument
 * binding, the ShowPage mapping and the @BatchMapping registration are actually exercised —
 * things the plain Mockito unit tests can never catch.
 */
@GraphQlTest(ShowController.class)
class ShowControllerGraphQlTest {

    @Autowired
    private GraphQlTester graphQlTester;

    @MockitoBean
    private ShowRepository showRepository;

    @MockitoBean
    private EpisodeRepository episodeRepository;

    @MockitoBean
    private ImageRepository imageRepository;

    @MockitoBean
    private LibraryRepository libraryRepository;

    @Test
    void showsQueryResolvesPageAndBatchedEpisodes() {
        ShowEntity show = ShowEntity.builder().name("Test show").releaseYear(2020).build();
        show.setId(UUID.randomUUID());
        EpisodeEntity episode = EpisodeEntity.builder().number(1).showEntity(show).build();
        when(showRepository.findAll(any(Pageable.class))).thenReturn(new PageImpl<>(List.of(show)));
        when(episodeRepository.findByShowEntityIdIn(anyCollection(), any(Sort.class))).thenReturn(List.of(episode));
        when(imageRepository.findByShowEntityIdIn(anyCollection())).thenReturn(List.of());

        graphQlTester.document("""
                        { shows(size: 10) {
                            totalElements
                            content { name releaseYear episodes { number } images { id } }
                        } }
                        """)
                .execute()
                .path("shows.totalElements").entity(Long.class).isEqualTo(1L)
                .path("shows.content[0].name").entity(String.class).isEqualTo("Test show")
                .path("shows.content[0].episodes[0].number").entity(Integer.class).isEqualTo(1)
                .path("shows.content[0].images").entityList(Object.class).hasSize(0);
    }

    @Test
    void showByIdReturnsNullWhenNotFound() {
        when(showRepository.findById(any(UUID.class))).thenReturn(java.util.Optional.empty());

        graphQlTester.document("""
                        { showById(id: "%s") { name } }
                        """.formatted(UUID.randomUUID()))
                .execute()
                .path("showById").valueIsNull();
    }
}
