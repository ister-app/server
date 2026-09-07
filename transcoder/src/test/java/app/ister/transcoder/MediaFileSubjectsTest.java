package app.ister.transcoder;

import app.ister.core.entity.DirectoryEntity;
import app.ister.core.entity.LibraryEntity;
import app.ister.core.entity.MediaFileEntity;
import app.ister.core.repository.MediaFileRepository;
import app.ister.core.status.ActivitySubjects;
import org.hibernate.LazyInitializationException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MediaFileSubjectsTest {

    @Mock
    private MediaFileRepository mediaFileRepository;

    @Mock
    private PlatformTransactionManager transactionManager;

    private final UUID mediaFileId = UUID.randomUUID();

    private MediaFileSubjects subject() {
        return new MediaFileSubjects(mediaFileRepository, transactionManager);
    }

    private MediaFileEntity mediaFile() {
        return MediaFileEntity.builder()
                .path("/media/movies/Big Movie (2020).mkv")
                .directoryEntity(DirectoryEntity.builder()
                        .name("movies")
                        .libraryEntity(LibraryEntity.builder().name("Movies").build())
                        .build())
                .build();
    }

    @Test
    void describesTheFileWithItsDirectoryAndLibrary() {
        when(mediaFileRepository.findById(mediaFileId)).thenReturn(Optional.of(mediaFile()));

        ActivitySubjects.Subject described = subject().describe(mediaFileId).orElseThrow();

        assertEquals("Big Movie (2020).mkv", described.title());
        assertEquals("movies", described.directory());
        assertEquals("Movies", described.library());
    }

    /**
     * The regression this class exists for: describing a media file walks lazy associations,
     * so the lookup must happen inside a transaction. Without one every TRANSCODE_REQUESTED
     * died with a LazyInitializationException and dead-lettered, and no playlist was ever
     * written (chart e2e: "master playlist did not happen within 300s").
     */
    @Test
    void looksTheFileUpInsideAReadOnlyTransaction() {
        when(mediaFileRepository.findById(mediaFileId)).thenReturn(Optional.of(mediaFile()));

        subject().describe(mediaFileId);

        ArgumentCaptor<TransactionDefinition> definition = ArgumentCaptor.forClass(TransactionDefinition.class);
        InOrder order = inOrder(transactionManager, mediaFileRepository);
        order.verify(transactionManager).getTransaction(definition.capture());
        order.verify(mediaFileRepository).findById(mediaFileId);
        order.verify(transactionManager).commit(any());
        assertTrue(definition.getValue().isReadOnly());
    }

    /** The description is cosmetic; it must never take the transcode down with it. */
    @Test
    void swallowsALookupFailure() {
        when(mediaFileRepository.findById(mediaFileId))
                .thenThrow(new LazyInitializationException("no session"));

        MediaFileSubjects subject = subject();

        assertDoesNotThrow(() -> assertTrue(subject.describe(mediaFileId).isEmpty()));
    }

    @Test
    void emptyForAMissingRow() {
        when(mediaFileRepository.findById(mediaFileId)).thenReturn(Optional.empty());

        assertTrue(subject().describe(mediaFileId).isEmpty());
    }

    @Test
    void emptyForANullIdWithoutTouchingTheDatabase() {
        assertTrue(subject().describe((UUID) null).isEmpty());

        verify(transactionManager, never()).getTransaction(any());
    }

    @Test
    void emptyForANonUuidPassKey() {
        assertTrue(subject().describe("video_720p").isEmpty());

        verify(transactionManager, never()).getTransaction(any());
    }

    @Test
    void acceptsAPassKeyThatIsAMediaFileId() {
        when(mediaFileRepository.findById(mediaFileId)).thenReturn(Optional.of(mediaFile()));

        assertEquals("Big Movie (2020).mkv", subject().describe(mediaFileId.toString()).orElseThrow().title());
    }
}
