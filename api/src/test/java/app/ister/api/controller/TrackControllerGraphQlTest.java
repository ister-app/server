package app.ister.api.controller;

import app.ister.core.entity.PersonEntity;
import app.ister.core.entity.TrackCreditEntity;
import app.ister.core.entity.TrackEntity;
import app.ister.core.enums.TrackCreditType;
import app.ister.core.repository.LibraryRepository;
import app.ister.core.repository.PersonRepository;
import app.ister.core.repository.TrackCreditRepository;
import app.ister.core.repository.TrackRepository;
import app.ister.core.repository.WatchStatusRepository;
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
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * Schema-wiring test: the tracks query binds its arguments as one object off the argument map
 * (@Arguments record with Optional components), so execute the real query — with artistId absent
 * and present — to exercise that binding and the TrackCredit field mappings.
 */
@GraphQlTest(TrackController.class)
class TrackControllerGraphQlTest {

    @Autowired
    private GraphQlTester graphQlTester;

    @MockitoBean
    private TrackRepository trackRepository;

    @MockitoBean
    private TrackCreditRepository trackCreditRepository;

    @MockitoBean
    private PersonRepository personRepository;

    @MockitoBean
    private WatchStatusRepository watchStatusRepository;

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
        when(filteredBrowse.page(any(), any(), any(), any(), any(), any(), any())).thenReturn(Optional.empty());
    }

    @AfterEach
    void clearAuthentication() {
        SecurityContextHolder.clearContext();
    }

    private TrackEntity track() {
        TrackEntity track = TrackEntity.builder().number(1).discNumber(1).build();
        ReflectionTestUtils.setField(track, "id", UUID.randomUUID());
        return track;
    }

    @Test
    void tracksQueryWithoutArgumentsBindsToEmptyOptionals() {
        UUID libraryId = UUID.randomUUID();
        when(libraryAccessService.allowedLibraryIds(any())).thenReturn(Optional.of(Set.of(libraryId)));
        TrackEntity track = track();
        track.setDateCreated(java.time.Instant.parse("2026-08-20T10:15:30Z"));
        when(trackRepository.findInLibraries(any(), any(), any(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(track)));

        assertDoesNotThrow(() -> graphQlTester.document("""
                        { tracks { content { id number dateAdded } } }
                        """)
                .execute()
                .path("tracks.content[0].number").entity(Integer.class).isEqualTo(1)
                .path("tracks.content[0].dateAdded").entity(String.class).isEqualTo("2026-08-20T10:15:30Z"));
    }

    @Test
    void tracksQueryBindsTheArtistIdArgumentAndResolvesCredits() {
        UUID personId = UUID.randomUUID();
        UUID libraryId = UUID.randomUUID();
        TrackEntity track = track();
        PersonEntity guest = PersonEntity.builder().name("Sean Paul").build();
        guest.setId(UUID.randomUUID());
        TrackCreditEntity credit = TrackCreditEntity.builder().trackEntity(track).personEntity(guest)
                .creditType(TrackCreditType.FEATURED).position(1).build();
        credit.setId(UUID.randomUUID());
        when(libraryAccessService.allowedLibraryIds(any())).thenReturn(Optional.of(Set.of(libraryId)));
        when(trackRepository.findForPersonInLibraries(eq(personId), any(), any(), any(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(track)));
        when(trackCreditRepository.findByTrackEntity_IdIn(List.of(track.getId()))).thenReturn(List.of(credit));

        assertDoesNotThrow(() -> graphQlTester.document("""
                        { tracks(artistId: "%s") { content { id artists { type position person { name } } } } }
                        """.formatted(personId))
                .execute()
                .path("tracks.content[0].artists[0].person.name").entity(String.class).isEqualTo("Sean Paul"));
    }
}
