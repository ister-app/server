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

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String token = request.getParameter("token");
        if (token != null && !token.isBlank() && SecurityContextHolder.getContext().getAuthentication() == null) {
            Optional<StreamTokenEntity> tokenEntity = streamTokenService.validateStreamToken(token);
            tokenEntity.ifPresent(entity -> {
                String path = request.getRequestURI();
                if ((path.startsWith(pathPrefix + "/hls/") || path.startsWith(pathPrefix + "/images/"))
                        && entity.getUserEntity() != null) {
                    setAuth(entity.getUserEntity().getExternalId(), "ROLE_user");
                } else if (path.startsWith(pathPrefix + "/mediaFile/") && entity.isDownload()) {
                    setAuth("node", "ROLE_node");
                } else if (path.startsWith(pathPrefix + "/transcode/upload/") && entity.isUpload()) {
                    setAuth("node", "ROLE_node");
                }
            });
        }
        filterChain.doFilter(request, response);
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
        return !path.startsWith(pathPrefix + "/hls/")
                && !path.startsWith(pathPrefix + "/images/")
                && !path.startsWith(pathPrefix + "/mediaFile/")
                && !path.startsWith(pathPrefix + "/transcode/upload/");
    }
}
