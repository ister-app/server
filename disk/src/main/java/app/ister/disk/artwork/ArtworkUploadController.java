package app.ister.disk.artwork;

import app.ister.core.artwork.ArtworkAdminService;
import app.ister.core.artwork.ImageTarget;
import app.ister.core.entity.ImageEntity;
import app.ister.core.enums.ImageType;
import app.ister.core.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;

/**
 * {@code POST /artwork/upload?type=COVER|BACKGROUND&movieId=…} (or {@code showId}, {@code episodeId},
 * {@code albumId}, {@code personId}, {@code bookId}, {@code podcastId}, {@code gameId},
 * {@code seriesId} — exactly one) with the image as the raw body ({@code image/jpeg},
 * {@code image/png} or {@code image/webp}, at most {@value #MAX_BYTES} bytes). The image is stored
 * in this node's cache, becomes the item's selected image of that type, and the answer carries its
 * {@code imageId}.
 *
 * <p>The binary twin of the GraphQL artwork mutations. Bearer JWT only, like the library upload:
 * a stream token in a URL must never be able to write. POST without custom headers, because the
 * web player calls this cross-origin and CORS allows nothing more. Deliberately not under
 * {@code /images/}, where a stream token logs a caller in.
 */
@RestController
@RequiredArgsConstructor
@PreAuthorize("hasRole('admin')")
public class ArtworkUploadController {

    static final long MAX_BYTES = 20L * 1024 * 1024;

    private final ArtworkAdminService artworkAdminService;
    private final UserService userService;

    @PostMapping(value = "/artwork/upload", consumes = {MediaType.IMAGE_JPEG_VALUE, MediaType.IMAGE_PNG_VALUE, "image/webp"})
    public Map<String, Object> upload(@RequestParam ImageType type,
                                      @RequestParam(required = false) UUID movieId,
                                      @RequestParam(required = false) UUID showId,
                                      @RequestParam(required = false) UUID episodeId,
                                      @RequestParam(required = false) UUID albumId,
                                      @RequestParam(required = false) UUID personId,
                                      @RequestParam(required = false) UUID bookId,
                                      @RequestParam(required = false) UUID podcastId,
                                      @RequestParam(required = false) UUID gameId,
                                      @RequestParam(required = false) UUID seriesId,
                                      @RequestHeader("Content-Type") String contentType,
                                      @RequestBody byte[] body,
                                      Authentication authentication) throws IOException {
        if (type != ImageType.COVER && type != ImageType.BACKGROUND) {
            throw new IllegalArgumentException("type must be COVER or BACKGROUND");
        }
        if (body.length == 0) {
            throw new IllegalArgumentException("Empty image");
        }
        if (body.length > MAX_BYTES) {
            throw new IllegalArgumentException("Image larger than " + MAX_BYTES + " bytes");
        }
        ImageTarget target = ImageTarget.of(movieId, showId, episodeId, albumId, personId, bookId, podcastId, gameId, seriesId);
        String mediaType = MediaType.parseMediaType(contentType).toString().split(";")[0].trim();
        UUID userId = userService.getOrCreateUser(authentication).getId();
        ImageEntity image = artworkAdminService.storeUpload(target, type, body, mediaType,
                "upload://" + userId + "/" + UUID.randomUUID());
        return Map.of("imageId", image.getId(), "type", image.getType().name());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<ProblemDetail> badRequest(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage()));
    }

    @ExceptionHandler(NoSuchElementException.class)
    ResponseEntity<ProblemDetail> notFound(NoSuchElementException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, e.getMessage()));
    }
}
