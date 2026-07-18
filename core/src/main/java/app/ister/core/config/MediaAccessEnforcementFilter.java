package app.ister.core.config;

import app.ister.core.entity.LibraryEntity;
import app.ister.core.service.LibraryAccessService;
import app.ister.core.service.MediaLibraryResolver;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Enforces library visibility on the id-addressed media endpoints ({@code /hls/{mediaFileId}},
 * {@code /epub/{mediaFileId}}, {@code /comic/{mediaFileId}}, {@code /images/{imageId}/download}).
 * Authentication (bearer or stream token) has already happened earlier in the chain; this filter
 * only decides whether the authenticated user may see the library the resource belongs to.
 * A denied resource answers 404, indistinguishable from a resource that does not exist.
 * Node-to-node traffic ({@code ROLE_node}) and resources without a library (e.g. person images)
 * pass through.
 */
@RequiredArgsConstructor
public class MediaAccessEnforcementFilter extends OncePerRequestFilter {

    private static final List<String> MEDIA_FILE_PATHS = List.of("/hls/", "/epub/", "/comic/");
    @SuppressWarnings("java:S1075")
    private static final String IMAGE_PATH = "/images/";

    private final MediaLibraryResolver mediaLibraryResolver;
    private final LibraryAccessService libraryAccessService;
    private final String pathPrefix;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !hasUserRole(authentication)) {
            // Unauthenticated (the chain answers 401) or node traffic — not ours to judge.
            filterChain.doFilter(request, response);
            return;
        }
        if (!allowed(request.getRequestURI(), authentication)) {
            response.sendError(HttpStatus.NOT_FOUND.value(), HttpStatus.NOT_FOUND.getReasonPhrase());
            return;
        }
        filterChain.doFilter(request, response);
    }

    private boolean allowed(String path, Authentication authentication) {
        Optional<UUID> id = idAfterPrefix(path);
        if (id.isEmpty()) {
            return true; // malformed id: let the controller answer its usual 400/404
        }
        Optional<LibraryEntity> library = isMediaFilePath(path)
                ? mediaLibraryResolver.ofMediaFileId(id.get())
                : mediaLibraryResolver.ofImageId(id.get());
        return library.map(lib -> libraryAccessService.canAccess(lib, authentication)).orElse(true);
    }

    private boolean isMediaFilePath(String path) {
        return MEDIA_FILE_PATHS.stream().anyMatch(prefix -> path.startsWith(pathPrefix + prefix));
    }

    /** The path segment right after the route prefix: the mediaFileId or imageId. */
    private Optional<UUID> idAfterPrefix(String path) {
        String remainder = path.substring(pathPrefix.length());
        String[] segments = remainder.split("/");
        if (segments.length < 3) {
            return Optional.empty();
        }
        try {
            return Optional.of(UUID.fromString(segments[2]));
        } catch (IllegalArgumentException _) {
            return Optional.empty();
        }
    }

    private static boolean hasUserRole(Authentication authentication) {
        return authentication.getAuthorities().stream()
                .anyMatch(authority -> "ROLE_user".equals(authority.getAuthority()));
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return !isMediaFilePath(path) && !path.startsWith(pathPrefix + IMAGE_PATH);
    }
}
