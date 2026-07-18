package app.ister.api.controller;

import app.ister.core.entity.SeriesEntity;
import app.ister.core.enums.ReadingDirection;
import app.ister.core.repository.ImageRepository;
import app.ister.core.repository.MetadataRepository;
import app.ister.core.repository.SeriesRepository;
import app.ister.core.repository.UserSeriesPreferenceRepository;
import app.ister.core.service.SeriesPreferenceService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.graphql.test.autoconfigure.GraphQlTest;
import org.springframework.graphql.test.tester.GraphQlTester;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Schema-wiring test for the per-user series reading direction: the batch resolver falls back to
 * the detected default, and the mutation reaches the service — including the null "clear" form.
 */
@GraphQlTest(SeriesController.class)
class SeriesControllerGraphQlTest {

    @Autowired
    private GraphQlTester graphQlTester;

    @MockitoBean
    private app.ister.core.service.LibraryAccessService libraryAccessService;

    @MockitoBean
    private SeriesRepository seriesRepository;

    @MockitoBean
    private ImageRepository imageRepository;

    @MockitoBean
    private MetadataRepository metadataRepository;

    @MockitoBean
    private UserSeriesPreferenceRepository userSeriesPreferenceRepository;

    @MockitoBean
    private SeriesPreferenceService seriesPreferenceService;

    private final UUID seriesId = UUID.randomUUID();

    /** The resolvers take an Authentication argument; without one they fail before any logic runs. */
    @BeforeEach
    void authenticate() {
        org.mockito.Mockito.lenient().when(libraryAccessService.allowedLibraryIds(org.mockito.ArgumentMatchers.any()))
                .thenReturn(java.util.Optional.empty());
        org.mockito.Mockito.lenient().when(libraryAccessService.canAccess(
                org.mockito.ArgumentMatchers.<app.ister.core.entity.LibraryEntity>any(),
                org.mockito.ArgumentMatchers.any())).thenReturn(true);
        org.mockito.Mockito.lenient().when(libraryAccessService.canAccess(
                org.mockito.ArgumentMatchers.<java.util.UUID>any(),
                org.mockito.ArgumentMatchers.any())).thenReturn(true);
        SecurityContextHolder.getContext().setAuthentication(
                new TestingAuthenticationToken("user-1", "n/a", "ROLE_user"));
    }

    @AfterEach
    void clearAuthentication() {
        SecurityContextHolder.clearContext();
    }



    @Test
    void readingDirectionResolvesTheSeriesDefaultWithoutAnOverride() {
        SeriesEntity manga = SeriesEntity.builder().name("Attack on Titan").startYear(2009)
                .defaultReadingDirection(ReadingDirection.RTL).build();
        manga.setId(seriesId);
        when(seriesRepository.findById(seriesId)).thenReturn(Optional.of(manga));
        when(userSeriesPreferenceRepository.findByUserEntityExternalIdAndSeriesEntityIn(eq("user-1"), any()))
                .thenReturn(List.of());

        graphQlTester.document("""
                        query($id: ID) {
                          seriesById(id: $id) { id readingDirection userReadingDirection }
                        }""")
                .variable("id", seriesId)
                .execute()
                .path("seriesById.readingDirection").entity(ReadingDirection.class).isEqualTo(ReadingDirection.RTL)
                .path("seriesById.userReadingDirection").valueIsNull();
    }

    @Test
    void setSeriesReadingDirectionReachesTheService() {
        graphQlTester.document("""
                        mutation($seriesId: ID!) {
                          setSeriesReadingDirection(seriesId: $seriesId, direction: RTL)
                        }""")
                .variable("seriesId", seriesId)
                .execute()
                .path("setSeriesReadingDirection").entity(Boolean.class).isEqualTo(true);

        verify(seriesPreferenceService).setReadingDirection(any(), eq(seriesId), eq(ReadingDirection.RTL));
    }

    /** Omitting the nullable argument is the "clear back to the series default" form. */
    @Test
    void setSeriesReadingDirectionWithoutADirectionClears() {
        graphQlTester.document("""
                        mutation($seriesId: ID!) {
                          setSeriesReadingDirection(seriesId: $seriesId)
                        }""")
                .variable("seriesId", seriesId)
                .execute()
                .path("setSeriesReadingDirection").entity(Boolean.class).isEqualTo(true);

        verify(seriesPreferenceService).setReadingDirection(any(), eq(seriesId), isNull());
    }
}
