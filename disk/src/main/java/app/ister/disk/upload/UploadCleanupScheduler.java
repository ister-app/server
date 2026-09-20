package app.ister.disk.upload;

import app.ister.core.entity.DirectoryEntity;
import app.ister.core.entity.UploadSessionEntity;
import app.ister.core.enums.DirectoryType;
import app.ister.core.enums.StorageKind;
import app.ister.core.enums.UploadSessionStatus;
import app.ister.core.repository.DirectoryRepository;
import app.ister.core.repository.UploadSessionRepository;
import app.ister.core.service.NodeService;
import app.ister.core.storage.LibraryWriteStore;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Ends uploads nobody is sending chunks to any more, and removes what they staged: a closed
 * browser tab must not leave 30 GB of part files on the media disk, nor keep claiming the target
 * paths and the space it was promised.
 *
 * <p>Runs on every node; each ends the sessions whose storage it can reach (its own LOCAL
 * directories, and S3 directories it has the connection for). Ending a session is idempotent, so
 * two nodes attached to the same bucket racing for one is harmless. S3 sessions are cleaned from
 * their rows (key + upload id), not from a bucket listing: not every S3 server lists pending
 * multipart uploads by prefix.
 */
@Slf4j
@Component
public class UploadCleanupScheduler {

    private final UploadProperties properties;
    private final UploadSessionService sessionService;
    private final UploadSessionRepository sessionRepository;
    private final DirectoryRepository directoryRepository;
    private final NodeService nodeService;
    private final TransactionTemplate tx;

    public UploadCleanupScheduler(UploadProperties properties, UploadSessionService sessionService,
                                  UploadSessionRepository sessionRepository, DirectoryRepository directoryRepository,
                                  NodeService nodeService, PlatformTransactionManager transactionManager) {
        this.properties = properties;
        this.sessionService = sessionService;
        this.sessionRepository = sessionRepository;
        this.directoryRepository = directoryRepository;
        this.nodeService = nodeService;
        this.tx = new TransactionTemplate(transactionManager);
    }

    @Scheduled(fixedDelayString = "${app.ister.upload.cleanup-interval:PT1H}", initialDelayString = "PT5M")
    public void run() {
        expireIdleSessions();
        removeOrphanStaging();
    }

    void expireIdleSessions() {
        Instant cutoff = Instant.now().minus(properties.getSessionIdleTimeout());
        List<UUID> idle = sessionRepository.findByStatusAndLastActivityAtBefore(UploadSessionStatus.ACTIVE, cutoff)
                .stream().map(UploadSessionEntity::getId).toList();
        for (UUID sessionId : idle) {
            try {
                // one transaction per session: a storage error on one must not keep the others alive
                tx.executeWithoutResult(_ -> sessionRepository.findById(sessionId)
                        .ifPresent(session -> sessionService.end(session, UploadSessionStatus.EXPIRED)));
            } catch (RuntimeException e) {
                log.warn("Could not expire upload session {}: {}", sessionId, e.getMessage());
            }
        }
    }

    /**
     * Staging folders without an active session: left by a session row that was removed another
     * way (its directory was deleted and re-created, a database restore). Only LOCAL has folders.
     */
    void removeOrphanStaging() {
        Set<String> active = sessionRepository.findByStatus(UploadSessionStatus.ACTIVE).stream()
                .map(s -> s.getId().toString()).collect(Collectors.toSet());
        List<DirectoryEntity> own = directoryRepository
                .findByDirectoryTypeAndNodeEntity(DirectoryType.LIBRARY, nodeService.getOrCreateNodeEntityForThisNode());
        for (DirectoryEntity directory : own) {
            if (directory.getStorageKind() == StorageKind.S3) {
                continue;
            }
            Path staging = Path.of(directory.getPath(), LibraryWriteStore.STAGING_DIR);
            if (!Files.isDirectory(staging)) {
                continue;
            }
            try (Stream<Path> sessions = Files.list(staging)) {
                for (Path sessionDir : sessions.filter(Files::isDirectory).toList()) {
                    if (!active.contains(sessionDir.getFileName().toString()) && !isActiveNow(sessionDir)) {
                        log.info("Removing orphan upload staging {}", sessionDir);
                        deleteRecursively(sessionDir);
                    }
                }
            } catch (IOException e) {
                log.warn("Could not sweep {}: {}", staging, e.getMessage());
            }
        }
    }

    /**
     * Asked again right before deleting: a session created after the set above was read must keep
     * its folder. A folder that is not named like a session is not ours to remove either.
     */
    private boolean isActiveNow(Path sessionDir) {
        try {
            return sessionRepository.findById(UUID.fromString(sessionDir.getFileName().toString()))
                    .map(s -> s.getStatus() == UploadSessionStatus.ACTIVE).orElse(false);
        } catch (IllegalArgumentException _) {
            return true;
        }
    }

    private static void deleteRecursively(Path dir) throws IOException {
        try (Stream<Path> walk = Files.walk(dir)) {
            for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(p);
            }
        }
    }
}
