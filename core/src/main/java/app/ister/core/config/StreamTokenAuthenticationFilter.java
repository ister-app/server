package app.ister.core.config;

import app.ister.core.entity.StreamTokenEntity;
import app.ister.core.service.StreamTokenService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;
import java.util.Optional;

@RequiredArgsConstructor
public class StreamTokenAuthenticationFilter extends OncePerRequestFilter {

    private final StreamTokenService streamTokenService;
    private final String pathPrefix;

    /**
     * Cookie fallback for the epub reader: chapter subresources (images, css) are loaded by the
     * browser itself, so a query parameter cannot be attached. The reader stores its stream token
     * in this same-origin cookie once and every epub request carries it automatically.
     */
    public static final String STREAM_TOKEN_COOKIE = "IsterStreamToken";

    // Route prefixes, not file locations: they must match the controller mappings, so making them
    // configurable would only let a deployment break its own stream authentication.
    private static final List<String> USER_PATHS =
            List.of("/hls/", "/images/", "/epub/", "/comic/", "/reading-progress", "/book-progress");
    // Node-to-node: a helper node reads media files and extracted subtitles from the owner
    // with a download token, and pushes HLS artefacts / cache files back with an upload token.
    private static final List<String> DOWNLOAD_PATHS = List.of("/mediaFile/", "/mediaFileStream/");
    private static final List<String> UPLOAD_PATHS = List.of("/transcode/upload/", "/cache/upload/");

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String token = extractToken(request);
        if (!token.isBlank() && SecurityContextHolder.getContext().getAuthentication() == null) {
            Optional<StreamTokenEntity> tokenEntity = streamTokenService.validateStreamToken(token);
            tokenEntity.ifPresent(entity -> authenticate(entity, request.getRequestURI()));
        }
        filterChain.doFilter(request, response);
    }

    private String extractToken(HttpServletRequest request) {
        String token = request.getParameter("token");
        if (token != null && !token.isBlank()) {
            return token;
        }
        if (request.getCookies() != null) {
            for (var cookie : request.getCookies()) {
                if (STREAM_TOKEN_COOKIE.equals(cookie.getName())) {
                    return cookie.getValue() == null ? "" : cookie.getValue();
                }
            }
        }
        return "";
    }

    private void authenticate(StreamTokenEntity entity, String path) {
        if (matchesAny(path, USER_PATHS) && entity.getUserEntity() != null) {
            setAuth(entity.getUserEntity().getExternalId(), "ROLE_user");
        } else if (matchesAny(path, DOWNLOAD_PATHS) && entity.isDownload()) {
            setAuth("node", "ROLE_node");
        } else if (matchesAny(path, UPLOAD_PATHS) && entity.isUpload()) {
            setAuth("node", "ROLE_node");
        }
    }

    private boolean matches(String path, String suffix) {
        return path.startsWith(pathPrefix + suffix);
    }

    private boolean matchesAny(String path, List<String> suffixes) {
        return suffixes.stream().anyMatch(suffix -> matches(path, suffix));
    }

    private void setAuth(String principal, String role) {
        UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(
                principal,
                null,
                List.of(new SimpleGrantedAuthority(role))
        );
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return !matchesAny(path, USER_PATHS)
                && !matchesAny(path, DOWNLOAD_PATHS)
                && !matchesAny(path, UPLOAD_PATHS);
    }

    /**
     * Streaming endpoints (epub resources, HLS) complete through an async dispatch, which
     * runs the security filter chain a second time. The authorization filter checks that
     * dispatch too, so authentication must run again as well — without this the dispatch
     * is anonymous and every streamed response logs an AuthorizationDeniedException
     * (harmless for the client, the body is already committed, but it floods the log).
     */
    @Override
    protected boolean shouldNotFilterAsyncDispatch() {
        return false;
    }
}
