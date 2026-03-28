package app.ister.core.config;

import app.ister.core.entity.UserEntity;
import app.ister.core.service.StreamTokenService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import java.io.IOException;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class StreamTokenAuthenticationFilterTest {

    @Mock
    private StreamTokenService streamTokenService;
    @Mock
    private HttpServletRequest request;
    @Mock
    private HttpServletResponse response;
    @Mock
    private FilterChain filterChain;

    private StreamTokenAuthenticationFilter filter;

    @BeforeEach
    void setUp() {
        filter = new StreamTokenAuthenticationFilter(streamTokenService, "/api");
        SecurityContextHolder.clearContext();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    // ========== shouldNotFilter ==========

    @Test
    void shouldNotFilterReturnsFalseForHlsPath() {
        when(request.getRequestURI()).thenReturn("/api/hls/some-uuid/master.m3u8");

        boolean result = filter.shouldNotFilter(request);

        assertFalse(result, "HLS paths should be filtered");
    }

    @Test
    void shouldNotFilterReturnsFalseForImagesPath() {
        when(request.getRequestURI()).thenReturn("/api/images/some-image.jpg");

        boolean result = filter.shouldNotFilter(request);

        assertFalse(result, "Image paths should be filtered");
    }

    @Test
    void shouldNotFilterReturnsTrueForOtherPaths() {
        when(request.getRequestURI()).thenReturn("/api/graphql");

        boolean result = filter.shouldNotFilter(request);

        assertTrue(result, "Non-HLS/image paths should not be filtered");
    }

    @Test
    void shouldNotFilterReturnsTrueForRootPath() {
        when(request.getRequestURI()).thenReturn("/");

        boolean result = filter.shouldNotFilter(request);

        assertTrue(result);
    }

    @Test
    void shouldNotFilterReturnsFalseForHlsPathWithDifferentPrefix() {
        StreamTokenAuthenticationFilter filterWithPrefix = new StreamTokenAuthenticationFilter(streamTokenService, "");
        when(request.getRequestURI()).thenReturn("/hls/uuid/master.m3u8");

        boolean result = filterWithPrefix.shouldNotFilter(request);

        assertFalse(result);
    }

    // ========== doFilterInternal ==========

    @Test
    void doFilterInternalWithValidTokenSetsAuthentication() throws ServletException, IOException {
        UserEntity user = UserEntity.builder().externalId("user-xyz").build();
        when(request.getParameter("token")).thenReturn("valid-token-uuid");
        when(streamTokenService.validateToken("valid-token-uuid")).thenReturn(Optional.of(user));

        filter.doFilterInternal(request, response, filterChain);

        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        assertNotNull(auth);
        assertEquals("user-xyz", auth.getPrincipal());
        assertTrue(auth.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("ROLE_user")));
        verify(filterChain).doFilter(request, response);
    }

    @Test
    void doFilterInternalWithNullTokenJustCallsFilterChain() throws ServletException, IOException {
        when(request.getParameter("token")).thenReturn(null);

        filter.doFilterInternal(request, response, filterChain);

        assertNull(SecurityContextHolder.getContext().getAuthentication());
        verify(filterChain).doFilter(request, response);
        verifyNoInteractions(streamTokenService);
    }

    @Test
    void doFilterInternalWithBlankTokenJustCallsFilterChain() throws ServletException, IOException {
        when(request.getParameter("token")).thenReturn("   ");

        filter.doFilterInternal(request, response, filterChain);

        assertNull(SecurityContextHolder.getContext().getAuthentication());
        verify(filterChain).doFilter(request, response);
        verifyNoInteractions(streamTokenService);
    }

    @Test
    void doFilterInternalWithInvalidTokenJustCallsFilterChain() throws ServletException, IOException {
        when(request.getParameter("token")).thenReturn("invalid-token");
        when(streamTokenService.validateToken("invalid-token")).thenReturn(Optional.empty());

        filter.doFilterInternal(request, response, filterChain);

        assertNull(SecurityContextHolder.getContext().getAuthentication());
        verify(filterChain).doFilter(request, response);
    }

    @Test
    void doFilterInternalDoesNotOverrideExistingAuthentication() throws ServletException, IOException {
        // Pre-set an existing authentication
        Authentication existingAuth = mock(Authentication.class);
        SecurityContextHolder.getContext().setAuthentication(existingAuth);

        when(request.getParameter("token")).thenReturn("some-token");

        filter.doFilterInternal(request, response, filterChain);

        // Original auth should be preserved, not replaced
        assertSame(existingAuth, SecurityContextHolder.getContext().getAuthentication());
        verifyNoInteractions(streamTokenService);
        verify(filterChain).doFilter(request, response);
    }
}
