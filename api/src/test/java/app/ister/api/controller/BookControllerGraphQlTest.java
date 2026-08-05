package app.ister.api.controller;

import app.ister.core.entity.BookEntity;
import app.ister.core.entity.LibraryEntity;
import app.ister.core.entity.UserEntity;
import app.ister.core.repository.BookRepository;
import app.ister.core.repository.ImageRepository;
import app.ister.core.repository.MediaFileRepository;
import app.ister.core.repository.PersonRepository;
import app.ister.core.repository.WatchStatusRepository;
import app.ister.core.service.BookProgressService;
import app.ister.core.service.BookResumeService;
import app.ister.core.service.ContinueWatchingService;
import app.ister.core.service.LibraryAccessService;
import app.ister.core.service.UserService;
import app.ister.core.service.WatchStatusService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.graphql.test.autoconfigure.GraphQlTest;
import org.springframework.graphql.test.tester.GraphQlTester;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.when;

/**
 * Schema-wiring test for whole-book progress: the aggregate the carousel and the book page draw
 * their bar from has to come out of the schema as one object, timestamp included.
 */
@GraphQlTest(BookController.class)
class BookControllerGraphQlTest {

    @Autowired
    private GraphQlTester graphQlTester;

    @MockitoBean
    private BookRepository bookRepository;

    @MockitoBean
    private PersonRepository personRepository;

    @MockitoBean
    private ImageRepository imageRepository;

    @MockitoBean
    private MediaFileRepository mediaFileRepository;

    @MockitoBean
    private WatchStatusRepository watchStatusRepository;

    @MockitoBean
    private WatchStatusService watchStatusService;

    @MockitoBean
    private ContinueWatchingService continueWatchingService;

    @MockitoBean
    private BookResumeService bookResumeService;

    @MockitoBean
    private BookProgressService bookProgressService;

    @MockitoBean
    private UserService userService;

    @MockitoBean
    private LibraryAccessService libraryAccessService;

    private final UUID bookId = UUID.randomUUID();

    @BeforeEach
    void authenticate() {
        SecurityContextHolder.getContext().setAuthentication(
                new TestingAuthenticationToken("user-1", "n/a", "ROLE_user"));
        LibraryEntity library = LibraryEntity.builder().name("Books").build();
        library.setId(UUID.randomUUID());
        BookEntity book = BookEntity.builder().libraryEntity(library).name("De wolven van Arazan").build();
        book.setId(bookId);
        when(bookRepository.findById(bookId)).thenReturn(Optional.of(book));
        when(libraryAccessService.canAccess(any(LibraryEntity.class), any())).thenReturn(true);
        when(userService.getOrCreateUser(any())).thenReturn(
                UserEntity.builder().name("test-user").externalId("user-1").build());
        when(bookProgressService.forBooks(any(), anyList())).thenReturn(Map.of(bookId,
                new BookProgressService.BookProgress(BookProgressService.BookProgressMode.LISTENING,
                        0.4, false, 43_200_000L, 17_280_000L, Instant.parse("2026-08-05T12:00:00Z"))));
    }

    @AfterEach
    void clearAuthentication() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void bookProgressIsServedAsAWholeBookPosition() {
        graphQlTester.document("""
                        query($id: ID!) {
                          bookById(id: $id) {
                            progress { mode progress finished durationInMilliseconds positionInMilliseconds updatedAt }
                          }
                        }""")
                .variable("id", bookId)
                .execute()
                .path("bookById.progress.mode").entity(String.class).isEqualTo("LISTENING")
                .path("bookById.progress.progress").entity(Double.class).isEqualTo(0.4)
                .path("bookById.progress.finished").entity(Boolean.class).isEqualTo(false)
                .path("bookById.progress.durationInMilliseconds").entity(Integer.class).isEqualTo(43_200_000)
                .path("bookById.progress.positionInMilliseconds").entity(Integer.class).isEqualTo(17_280_000)
                .path("bookById.progress.updatedAt").entity(String.class).isEqualTo("2026-08-05T12:00:00Z");
    }

    /** A book the user never started resolves to null, not to an error. */
    @Test
    void bookProgressIsNullForAnUnstartedBook() {
        when(bookProgressService.forBooks(any(), anyList())).thenReturn(Map.of());

        graphQlTester.document("""
                        query($id: ID!) {
                          bookById(id: $id) { id progress { progress } }
                        }""")
                .variable("id", bookId)
                .execute()
                .path("bookById.progress").valueIsNull();
    }

    @Test
    void unstartedBooksNeedNoWatchStatusRows() {
        when(bookProgressService.forBooks(any(), anyList())).thenReturn(Map.of());
        when(watchStatusRepository.findByUserEntityExternalIdAndBookEntityIn(any(), anyList(), any()))
                .thenReturn(List.of());

        graphQlTester.document("""
                        query($id: ID!) {
                          bookById(id: $id) { watchStatus { id } }
                        }""")
                .variable("id", bookId)
                .execute()
                .path("bookById.watchStatus").entityList(Object.class).hasSize(0);
    }
}
