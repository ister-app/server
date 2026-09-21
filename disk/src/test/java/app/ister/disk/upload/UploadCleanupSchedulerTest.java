package app.ister.disk.upload;

import app.ister.core.entity.DirectoryEntity;
import app.ister.core.entity.NodeEntity;
import app.ister.core.entity.UploadSessionEntity;
import app.ister.core.enums.DirectoryType;
import app.ister.core.enums.StorageKind;
import app.ister.core.enums.UploadSessionStatus;
import app.ister.core.repository.DirectoryRepository;
import app.ister.core.repository.UploadSessionRepository;
import app.ister.core.service.NodeService;
import app.ister.core.storage.LibraryWriteStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class UploadCleanupSchedulerTest {

    @TempDir
    Path root;

    private final UploadSessionService sessionService = mock(UploadSessionService.class);
    private final UploadSessionRepository sessionRepository = mock(UploadSessionRepository.class);
    private final DirectoryRepository directoryRepository = mock(DirectoryRepository.class);
    private final NodeService nodeService = mock(NodeService.class);
    private UploadCleanupScheduler scheduler;

    @BeforeEach
    void setUp() {
        PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
        when(transactionManager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        NodeEntity node = NodeEntity.builder().name("node").build();
        when(nodeService.getOrCreateNodeEntityForThisNode()).thenReturn(node);
        DirectoryEntity local = DirectoryEntity.builder().name("shows").path(root.toString())
                .directoryType(DirectoryType.LIBRARY).storageKind(StorageKind.LOCAL).build();
        DirectoryEntity s3 = DirectoryEntity.builder().name("bucket").path("s3://bucket/shows")
                .directoryType(DirectoryType.LIBRARY).storageKind(StorageKind.S3).build();
        DirectoryEntity neverUploadedTo = DirectoryEntity.builder().name("movies").path(root.resolve("movies").toString())
                .directoryType(DirectoryType.LIBRARY).storageKind(StorageKind.LOCAL).build();
        when(directoryRepository.findByDirectoryTypeAndNodeEntity(DirectoryType.LIBRARY, node))
                .thenReturn(List.of(s3, neverUploadedTo, local));
        scheduler = new UploadCleanupScheduler(new UploadProperties(), sessionService, sessionRepository,
                directoryRepository, nodeService, transactionManager);
    }

    private static UploadSessionEntity session(UploadSessionStatus status) {
        UploadSessionEntity session = UploadSessionEntity.builder().status(status).build();
        session.setId(UUID.randomUUID());
        return session;
    }

    private Path staged(String name) throws IOException {
        Path dir = root.resolve(LibraryWriteStore.STAGING_DIR).resolve(name);
        Files.createDirectories(dir.resolve("nested"));
        Files.writeString(dir.resolve("nested/part"), "bytes");
        return dir;
    }

    @Test
    void expiresEveryIdleSessionEvenWhenOneFails() {
        UploadSessionEntity broken = session(UploadSessionStatus.ACTIVE);
        UploadSessionEntity idle = session(UploadSessionStatus.ACTIVE);
        UploadSessionEntity gone = session(UploadSessionStatus.ACTIVE);
        when(sessionRepository.findByStatusAndLastActivityAtBefore(any(), any()))
                .thenReturn(List.of(broken, gone, idle));
        when(sessionRepository.findById(broken.getId())).thenReturn(Optional.of(broken));
        when(sessionRepository.findById(gone.getId())).thenReturn(Optional.empty());
        when(sessionRepository.findById(idle.getId())).thenReturn(Optional.of(idle));
        doThrow(new IllegalStateException("storage down")).when(sessionService).end(broken, UploadSessionStatus.EXPIRED);

        scheduler.run();

        verify(sessionService).end(idle, UploadSessionStatus.EXPIRED);
    }

    @Test
    void removesOnlyStagingFoldersWithoutAnActiveSession() throws IOException {
        UploadSessionEntity active = session(UploadSessionStatus.ACTIVE);
        UploadSessionEntity justCreated = session(UploadSessionStatus.ACTIVE);
        UploadSessionEntity ended = session(UploadSessionStatus.ABORTED);
        when(sessionRepository.findByStatus(UploadSessionStatus.ACTIVE)).thenReturn(List.of(active));
        when(sessionRepository.findById(justCreated.getId())).thenReturn(Optional.of(justCreated));
        when(sessionRepository.findById(ended.getId())).thenReturn(Optional.of(ended));
        Path activeDir = staged(active.getId().toString());
        Path justCreatedDir = staged(justCreated.getId().toString());
        Path endedDir = staged(ended.getId().toString());
        Path unknownDir = staged(UUID.randomUUID().toString());
        Path notOurs = staged("not-a-session");
        Path looseFile = Files.writeString(root.resolve(LibraryWriteStore.STAGING_DIR).resolve("loose"), "x");

        scheduler.removeOrphanStaging();

        assertThat(List.of(activeDir, justCreatedDir, notOurs, looseFile)).allMatch(Files::exists);
        assertThat(List.of(endedDir, unknownDir)).noneMatch(Files::exists);
    }
}
