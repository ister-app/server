package app.ister.api.controller;

import app.ister.core.entity.AlbumEntity;
import app.ister.core.repository.AlbumRepository;
import app.ister.core.repository.ImageRepository;
import app.ister.core.repository.LibraryRepository;
import app.ister.core.repository.PersonRepository;
import app.ister.core.service.LibraryAccessService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.graphql.test.autoconfigure.GraphQlTest;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.graphql.test.tester.GraphQlTester;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * Schema-wiring test: the albums query's arguments are bound as one object off the argument
 * map (@Arguments record with Optional components), so execute the real query — with the
 * arguments absent and present — to exercise that binding.
 */
@GraphQlTest(AlbumController.class)
class AlbumControllerGraphQlTest {

    @Autowired
    private GraphQlTester graphQlTester;

    @MockitoBean
    private AlbumRepository albumRepository;

    @MockitoBean
    private PersonRepository personRepository;

    @MockitoBean
    private ImageRepository imageRepository;

    @MockitoBean
    private LibraryRepository libraryRepository;

    @MockitoBean
    private LibraryAccessService libraryAccessService;

    @MockitoBean
    private FilteredBrowse filteredBrowse;

    @BeforeEach
    void authenticateAsUser() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(
                        "test-user", null,
                        List.of(new SimpleGrantedAuthority("ROLE_user"))));
    }

    @AfterEach
    void clearAuthentication() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void albumsQueryWithoutArgumentsBindsToEmptyOptionals() {
        AlbumEntity album = AlbumEntity.builder().name("Abbey Road").releaseYear(1969).build();
        album.setId(UUID.randomUUID());
        album.setDateCreated(java.time.Instant.parse("2026-08-20T10:15:30Z"));
        when(libraryAccessService.allowedLibraryIds(any())).thenReturn(Optional.empty());
        when(albumRepository.findAll(any(Pageable.class))).thenReturn(new PageImpl<>(List.of(album)));

        assertDoesNotThrow(() -> graphQlTester.document("""
                        { albums { content { id name dateAdded } } }
                        """)
                .execute()
                .path("albums.content[0].name").entity(String.class).isEqualTo("Abbey Road")
                .path("albums.content[0].dateAdded").entity(String.class).isEqualTo("2026-08-20T10:15:30Z"));
    }

    @Test
    void albumsQueryBindsTheLibraryIdArgument() {
        UUID libraryId = UUID.randomUUID();
        AlbumEntity album = AlbumEntity.builder().name("Abbey Road").releaseYear(1969).build();
        album.setId(UUID.randomUUID());
        when(libraryAccessService.canAccess(eq(libraryId), any())).thenReturn(true);
        when(albumRepository.findByLibraryEntityId(eq(libraryId), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(album)));

        assertDoesNotThrow(() -> graphQlTester.document("""
                        { albums(libraryId: "%s") { content { id name } } }
                        """.formatted(libraryId))
                .execute()
                .path("albums.content[0].name").entity(String.class).isEqualTo("Abbey Road"));
    }

    @Test
    void albumsQueryBindsTheAppearsOnArtistIdArgument() {
        UUID personId = UUID.randomUUID();
        UUID libraryId = UUID.randomUUID();
        AlbumEntity compilation = AlbumEntity.builder().name("Top 40 Hits").releaseYear(2001).build();
        compilation.setId(UUID.randomUUID());
        when(libraryAccessService.allowedLibraryIds(any())).thenReturn(Optional.of(Set.of(libraryId)));
        when(albumRepository.findAppearsOnForPerson(eq(personId), any(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(compilation)));

        assertDoesNotThrow(() -> graphQlTester.document("""
                        { albums(appearsOnArtistId: "%s") { content { id name } } }
                        """.formatted(personId))
                .execute()
                .path("albums.content[0].name").entity(String.class).isEqualTo("Top 40 Hits"));
    }
}
