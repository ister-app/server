package app.ister.core.config;

import app.ister.core.entity.StreamTokenEntity;
import app.ister.core.entity.UserEntity;
import app.ister.core.service.StreamTokenService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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

    @ParameterizedTest
    @ValueSource(strings = {
        "/api/hls/some-uuid/master.m3u8",
        "/api/images/some-image.jpg",
        "/api/mediaFile/some-uuid/download",
        "/api/transcode/upload/some-uuid/seg.ts"
    })
    void shouldNotFilterReturnsFalseForFilteredPaths(String uri) {
        when(request.getRequestURI()).thenReturn(uri);

        assertFalse(filter.shouldNotFilter(request), "Path should be filtered: " + uri);
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

    // ========== cookie fallback ==========

    /**
     * Epub subresources (images, css) are loaded by the browser itself, so the reader cannot
     * attach a query parameter; the token travels in a same-origin cookie instead.
     */
    @Test
    void doFilterInternalAcceptsTheTokenFromTheCookie() throws ServletException, IOException {
        UserEntity user = UserEntity.builder().externalId("user-xyz").build();
        StreamTokenEntity tokenEntity = StreamTokenEntity.builder().userEntity(user).build();
        when(request.getParameter("token")).thenReturn(null);
        when(request.getCookies()).thenReturn(new Cookie[]{
                new Cookie("other", "irrelevant"),
                new Cookie(StreamTokenAuthenticationFilter.STREAM_TOKEN_COOKIE, "cookie-token")});
        when(request.getRequestURI()).thenReturn("/api/epub/some-uuid/resource/chapter1.xhtml");
        when(streamTokenService.validateStreamToken("cookie-token")).thenReturn(Optional.of(tokenEntity));

        filter.doFilterInternal(request, response, filterChain);

        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        assertNotNull(auth);
        assertEquals("user-xyz", auth.getPrincipal());
        verify(filterChain).doFilter(request, response);
    }

    /** The query parameter wins when both are present. */
    @Test
    void doFilterInternalPrefersTheQueryParameterOverTheCookie() throws ServletException, IOException {
        UserEntity user = UserEntity.builder().externalId("user-xyz").build();
        when(request.getParameter("token")).thenReturn("query-token");
        when(request.getRequestURI()).thenReturn("/api/hls/some-uuid/master.m3u8");
        when(streamTokenService.validateStreamToken("query-token"))
                .thenReturn(Optional.of(StreamTokenEntity.builder().userEntity(user).build()));

        filter.doFilterInternal(request, response, filterChain);

        assertNotNull(SecurityContextHolder.getContext().getAuthentication());
        verify(streamTokenService, never()).validateStreamToken("cookie-token");
    }

    @Test
    void doFilterInternalIgnoresACookieWithoutAValue() throws ServletException, IOException {
        when(request.getParameter("token")).thenReturn(null);
        when(request.getCookies()).thenReturn(new Cookie[]{
                new Cookie(StreamTokenAuthenticationFilter.STREAM_TOKEN_COOKIE, "")});

        filter.doFilterInternal(request, response, filterChain);

        assertNull(SecurityContextHolder.getContext().getAuthentication());
        verifyNoInteractions(streamTokenService);
        verify(filterChain).doFilter(request, response);
    }

    @Test
    void doFilterInternalIgnoresUnrelatedCookies() throws ServletException, IOException {
        when(request.getParameter("token")).thenReturn(null);
        when(request.getCookies()).thenReturn(new Cookie[]{new Cookie("other", "irrelevant")});

        filter.doFilterInternal(request, response, filterChain);

        assertNull(SecurityContextHolder.getContext().getAuthentication());
        verifyNoInteractions(streamTokenService);
    }

    // ========== token/path mismatches ==========

    @Test
    void aNodeTokenDoesNotAuthenticateOnAUserPath() throws ServletException, IOException {
        StreamTokenEntity tokenEntity = StreamTokenEntity.builder().download(true).upload(true).build();
        when(request.getParameter("token")).thenReturn("node-token");
        when(request.getRequestURI()).thenReturn("/api/hls/some-uuid/master.m3u8");
        when(streamTokenService.validateStreamToken("node-token")).thenReturn(Optional.of(tokenEntity));

        filter.doFilterInternal(request, response, filterChain);

        assertNull(SecurityContextHolder.getContext().getAuthentication());
        verify(filterChain).doFilter(request, response);
    }

    @Test
    void aUserTokenWithoutTheDownloadFlagCannotDownload() throws ServletException, IOException {
        UserEntity user = UserEntity.builder().externalId("user-xyz").build();
        StreamTokenEntity tokenEntity = StreamTokenEntity.builder().userEntity(user).download(false).build();
        when(request.getParameter("token")).thenReturn("user-token");
        when(request.getRequestURI()).thenReturn("/api/mediaFile/some-uuid/download");
        when(streamTokenService.validateStreamToken("user-token")).thenReturn(Optional.of(tokenEntity));

        filter.doFilterInternal(request, response, filterChain);

        assertNull(SecurityContextHolder.getContext().getAuthentication());
    }

    @Test
    void aTokenWithoutTheUploadFlagCannotUpload() throws ServletException, IOException {
        StreamTokenEntity tokenEntity = StreamTokenEntity.builder().download(true).upload(false).build();
        when(request.getParameter("token")).thenReturn("download-only-token");
        when(request.getRequestURI()).thenReturn("/api/transcode/upload/some-uuid/seg.ts");
        when(streamTokenService.validateStreamToken("download-only-token")).thenReturn(Optional.of(tokenEntity));

        filter.doFilterInternal(request, response, filterChain);

        assertNull(SecurityContextHolder.getContext().getAuthentication());
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "/api/hls/uuid/master.m3u8",
        "/api/images/uuid.jpg",
        "/api/epub/uuid/resource/chapter1.xhtml",
        "/api/reading-progress",
        "/api/book-progress"
    })
    void everyUserPathAuthenticatesAUserToken(String uri) throws ServletException, IOException {
        UserEntity user = UserEntity.builder().externalId("user-xyz").build();
        when(request.getParameter("token")).thenReturn("user-token");
        when(request.getRequestURI()).thenReturn(uri);
        when(streamTokenService.validateStreamToken("user-token"))
                .thenReturn(Optional.of(StreamTokenEntity.builder().userEntity(user).build()));

        filter.doFilterInternal(request, response, filterChain);

        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        assertNotNull(auth, "Path should authenticate: " + uri);
        assertEquals("user-xyz", auth.getPrincipal());
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
