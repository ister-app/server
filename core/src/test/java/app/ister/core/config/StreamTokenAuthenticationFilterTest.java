package app.ister.core.config;

import app.ister.core.entity.StreamTokenEntity;
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
    void shouldNotFilterReturnsFalseForMediaFilePath() {
        when(request.getRequestURI()).thenReturn("/api/mediaFile/some-uuid/download");

        boolean result = filter.shouldNotFilter(request);

        assertFalse(result, "MediaFile paths should be filtered");
    }

    @Test
    void shouldNotFilterReturnsFalseForTranscodeUploadPath() {
        when(request.getRequestURI()).thenReturn("/api/transcode/upload/some-uuid/seg.ts");

        boolean result = filter.shouldNotFilter(request);

        assertFalse(result, "Transcode upload paths should be filtered");
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
    void doFilterInternalWithValidUserTokenSetsAuthentication() throws ServletException, IOException {
        UserEntity user = UserEntity.builder().externalId("user-xyz").build();
        StreamTokenEntity tokenEntity = StreamTokenEntity.builder()
                .userEntity(user)
                .download(false)
                .upload(false)
                .build();
        when(request.getParameter("token")).thenReturn("valid-token-uuid");
        when(request.getRequestURI()).thenReturn("/api/hls/some-uuid/master.m3u8");
        when(streamTokenService.validateStreamToken("valid-token-uuid")).thenReturn(Optional.of(tokenEntity));

        filter.doFilterInternal(request, response, filterChain);

        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        assertNotNull(auth);
        assertEquals("user-xyz", auth.getPrincipal());
        assertTrue(auth.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("ROLE_user")));
        verify(filterChain).doFilter(request, response);
    }

    @Test
    void doFilterInternalWithNodeDownloadTokenSetsNodeAuth() throws ServletException, IOException {
        StreamTokenEntity tokenEntity = StreamTokenEntity.builder()
                .download(true)
                .upload(true)
                .build();
        when(request.getParameter("token")).thenReturn("node-token-uuid");
        when(request.getRequestURI()).thenReturn("/api/mediaFile/some-uuid/download");
        when(streamTokenService.validateStreamToken("node-token-uuid")).thenReturn(Optional.of(tokenEntity));

        filter.doFilterInternal(request, response, filterChain);

        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        assertNotNull(auth);
        assertEquals("node", auth.getPrincipal());
        assertTrue(auth.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("ROLE_node")));
        verify(filterChain).doFilter(request, response);
    }

    @Test
    void doFilterInternalWithNodeUploadTokenSetsNodeAuth() throws ServletException, IOException {
        StreamTokenEntity tokenEntity = StreamTokenEntity.builder()
                .download(true)
                .upload(true)
                .build();
        when(request.getParameter("token")).thenReturn("node-token-uuid");
        when(request.getRequestURI()).thenReturn("/api/transcode/upload/some-uuid/seg.ts");
        when(streamTokenService.validateStreamToken("node-token-uuid")).thenReturn(Optional.of(tokenEntity));

        filter.doFilterInternal(request, response, filterChain);

        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        assertNotNull(auth);
        assertEquals("node", auth.getPrincipal());
        assertTrue(auth.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("ROLE_node")));
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
        when(streamTokenService.validateStreamToken("invalid-token")).thenReturn(Optional.empty());

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
