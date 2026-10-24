package app.ister.api.controller;

import app.ister.core.artwork.ArtworkAdminService;
import app.ister.core.artwork.ImageTarget;
import app.ister.core.artwork.RemoteArtworkProvider;
import app.ister.core.artwork.RemoteImageCandidate;
import app.ister.core.entity.ImageEntity;
import app.ister.core.enums.ImageType;
import app.ister.core.enums.MetadataSource;
import app.ister.core.repository.ImageRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.graphql.test.autoconfigure.GraphQlTest;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.graphql.execution.ErrorType;
import org.springframework.graphql.test.tester.GraphQlTester;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Schema wiring and admin gating of the artwork API; the work itself is mocked ({@link ArtworkAdminService}). */
@GraphQlTest(ImageAdminController.class)
class ImageAdminControllerGraphQlTest {

    private static final ParameterizedTypeReference<Map<String, Object>> MAP = new ParameterizedTypeReference<>() {
    };

    @Autowired
    private GraphQlTester graphQlTester;

    @MockitoBean
    private ArtworkAdminService artworkAdminService;
    @MockitoBean
    private ImageRepository imageRepository;
    @MockitoBean
    private RemoteArtworkProvider provider;

    private final UUID movieId = UUID.randomUUID();

    @BeforeEach
    void admin() {
        SecurityContextHolder.getContext().setAuthentication(
                new TestingAuthenticationToken("admin-1", "n/a", "ROLE_admin"));
    }

    @AfterEach
    void clearAuthentication() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void imageCandidatesListsStoredImagesOfTheTypeAndProviderAlternatives() {
        ImageEntity cover = image(ImageType.COVER, true, "TMDB://https://image.tmdb.org/t/p/original/a.jpg");
        ImageEntity background = image(ImageType.BACKGROUND, false, "file:///m/background.jpg");
        when(imageRepository.findByMovieEntityId(movieId)).thenReturn(List.of(cover, background));
        when(provider.candidates(eq(new ImageTarget(ImageTarget.Kind.MOVIE, movieId)), eq(ImageType.COVER)))
                .thenReturn(List.of(new RemoteImageCandidate("https://image.tmdb.org/t/p/w500/b.jpg",
                        "TMDB://https://image.tmdb.org/t/p/original/b.jpg", MetadataSource.TMDB, 2000, 3000, "eng", 5.2)));

        Map<String, Object> result = graphQlTester.document("""
                        query($id: ID!) {
                          imageCandidates(target: {movieId: $id}, type: COVER) {
                            current { id type selected sourceUri source }
                            remote { previewUrl sourceUri source width height language score }
                          }
                        }""")
                .variable("id", movieId)
                .execute()
                .path("imageCandidates").entity(MAP).get();

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> current = (List<Map<String, Object>>) result.get("current");
        assertEquals(1, current.size());
        assertEquals(cover.getId().toString(), current.getFirst().get("id"));
        assertEquals(true, current.getFirst().get("selected"));
        assertEquals("TMDB", current.getFirst().get("source"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> remote = (List<Map<String, Object>>) result.get("remote");
        assertEquals(1, remote.size());
        assertEquals("https://image.tmdb.org/t/p/w500/b.jpg", remote.getFirst().get("previewUrl"));
        assertEquals("eng", remote.getFirst().get("language"));
        assertEquals(5.2, remote.getFirst().get("score"));
    }

    @Test
    void imageCandidatesWithTwoIdsIsABadRequest() {
        graphQlTester.document("""
                        query($id: ID!) {
                          imageCandidates(target: {movieId: $id, showId: $id}, type: COVER) { current { id } }
                        }""")
                .variable("id", movieId)
                .execute()
                .errors().satisfy(errors -> assertEquals(ErrorType.BAD_REQUEST, errors.getFirst().getErrorType()));
    }

    /**
     * The slice test has no method security; {@link app.ister.api.GraphQlRootFieldsRequireAuthorizationTest}
     * guards the annotation. Here: every root field of this controller is admin-only.
     */
    @Test
    void everyRootFieldIsAdminOnly() {
        for (var method : ImageAdminController.class.getDeclaredMethods()) {
            boolean root = method.isAnnotationPresent(org.springframework.graphql.data.method.annotation.QueryMapping.class)
                    || method.isAnnotationPresent(org.springframework.graphql.data.method.annotation.MutationMapping.class);
            if (!root) {
                continue;
            }
            var pre = method.getAnnotation(org.springframework.security.access.prepost.PreAuthorize.class);
            assertTrue(pre != null && pre.value().contains("hasRole('admin')"), method.getName() + " must be admin-only");
        }
    }

    @Test
    void selectVideoFramePassesTheFilePositionThrough() throws Exception {
        UUID mediaFileId = UUID.randomUUID();
        ImageEntity frame = image(ImageType.BACKGROUND, true, "frame://" + mediaFileId + "@754000");
        when(artworkAdminService.selectVideoFrame(new ImageTarget(ImageTarget.Kind.MOVIE, movieId), ImageType.BACKGROUND, mediaFileId, 754000L))
                .thenReturn(frame);

        graphQlTester.document("""
                        mutation($m: ID!, $f: ID!) {
                          selectVideoFrame(target: {movieId: $m}, type: BACKGROUND, mediaFileId: $f, positionMs: 754000) { id selected source }
                        }""")
                .variable("m", movieId).variable("f", mediaFileId)
                .execute()
                .path("selectVideoFrame.selected").entity(Boolean.class).isEqualTo(true)
                .path("selectVideoFrame.source").entity(String.class).isEqualTo("USER");
    }

    @Test
    void selectRemoteImageAndSelectImageReturnTheImage() throws Exception {
        ImageEntity picked = image(ImageType.COVER, true, "TMDB://https://image.tmdb.org/t/p/original/b.jpg");
        when(artworkAdminService.selectRemote(new ImageTarget(ImageTarget.Kind.SHOW, movieId), ImageType.COVER, picked.getSourceUri()))
                .thenReturn(picked);
        when(artworkAdminService.select(picked.getId())).thenReturn(picked);

        graphQlTester.document("""
                        mutation($id: ID!, $uri: String!) {
                          selectRemoteImage(target: {showId: $id}, type: COVER, sourceUri: $uri) { id }
                        }""")
                .variable("id", movieId).variable("uri", picked.getSourceUri())
                .execute()
                .path("selectRemoteImage.id").entity(String.class).isEqualTo(picked.getId().toString());

        graphQlTester.document("mutation($id: ID!) { selectImage(imageId: $id) { id selected } }")
                .variable("id", picked.getId())
                .execute()
                .path("selectImage.selected").entity(Boolean.class).isEqualTo(true);
    }

    @Test
    void unknownImageIsNotFoundAndClearAndDeleteReturnBooleans() {
        UUID unknown = UUID.randomUUID();
        when(artworkAdminService.select(unknown)).thenThrow(new NoSuchElementException("Image not found"));
        graphQlTester.document("mutation($id: ID!) { selectImage(imageId: $id) { id } }")
                .variable("id", unknown)
                .execute()
                .errors().satisfy(errors -> assertEquals(ErrorType.NOT_FOUND, errors.getFirst().getErrorType()));

        when(artworkAdminService.clearSelected(new ImageTarget(ImageTarget.Kind.ALBUM, movieId), ImageType.COVER)).thenReturn(true);
        graphQlTester.document("mutation($id: ID!) { clearSelectedImage(target: {albumId: $id}, type: COVER) }")
                .variable("id", movieId)
                .execute()
                .path("clearSelectedImage").entity(Boolean.class).isEqualTo(true);

        when(artworkAdminService.deleteUserImage(unknown)).thenThrow(new IllegalArgumentException("Only uploaded images"));
        graphQlTester.document("mutation($id: ID!) { deleteImage(imageId: $id) }")
                .variable("id", unknown)
                .execute()
                .errors().satisfy(errors -> {
                    assertEquals(ErrorType.BAD_REQUEST, errors.getFirst().getErrorType());
                    assertTrue(errors.getFirst().getMessage().contains("Only uploaded"));
                });
    }

    private ImageEntity image(ImageType type, boolean selected, String sourceUri) {
        ImageEntity image = ImageEntity.builder().id(UUID.randomUUID()).type(type).selected(selected)
                .sourceUri(sourceUri).source(MetadataSource.fromSourceUri(sourceUri).orElse(null))
                .movieEntityId(movieId).path("/cache/x.jpg").build();
        return image;
    }
}
