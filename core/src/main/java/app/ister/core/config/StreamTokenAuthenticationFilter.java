package app.ister.core.config;

import app.ister.core.entity.UserEntity;
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
            Optional<UserEntity> user = streamTokenService.validateToken(token);
            if (user.isPresent()) {
                UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(
                        user.get().getExternalId(),
                        null,
                        List.of(new SimpleGrantedAuthority("ROLE_user"))
                );
                SecurityContextHolder.getContext().setAuthentication(auth);
            }
        }
        filterChain.doFilter(request, response);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return !path.startsWith(pathPrefix + "/hls/") && !path.startsWith(pathPrefix + "/images/");
    }
}
