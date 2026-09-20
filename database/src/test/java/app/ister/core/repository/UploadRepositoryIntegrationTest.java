package app.ister.core.repository;

import app.ister.core.entity.DirectoryEntity;
import app.ister.core.entity.LibraryEntity;
import app.ister.core.entity.MediaFileEntity;
import app.ister.core.entity.NodeEntity;
import app.ister.core.entity.UploadFileEntity;
import app.ister.core.entity.UploadPartEntity;
import app.ister.core.entity.UploadSessionEntity;
import app.ister.core.enums.DirectoryType;
import app.ister.core.enums.LibraryType;
import app.ister.core.enums.UploadFileStatus;
import app.ister.core.enums.UploadSessionStatus;
import jakarta.persistence.PersistenceException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The upload tables (V50) against a real PostgreSQL: the schema matches the entities
 * (ddl-auto=validate), and the parts that only PostgreSQL can prove — the partial unique index
 * that keeps two sessions off one target, and the part upsert.
 */
@DataJpaTest(properties = {
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.flyway.enabled=true"
})
@org.springframework.boot.autoconfigure.ImportAutoConfiguration(org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration.class)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@org.springframework.context.annotation.Import(app.ister.core.config.PersistenceConfig.class)
@Testcontainers(disabledWithoutDocker = true)
class UploadRepositoryIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:18-alpine");

    @Autowired
    private TestEntityManager em;
    @Autowired
    private UploadSessionRepository sessionRepository;
    @Autowired
    private UploadFileRepository fileRepository;
    @Autowired
    private UploadPartRepository partRepository;
    @Autowired
    private MediaFileRepository mediaFileRepository;

    private DirectoryEntity directory(String name) {
        LibraryEntity library = em.persist(LibraryEntity.builder().libraryType(LibraryType.SHOW).name("lib-" + name).build());
        NodeEntity node = em.persist(NodeEntity.builder().name("node-" + name).url("http://localhost").build());
        return em.persist(DirectoryEntity.builder().nodeEntity(node).libraryEntity(library).name(name)
                .path("/media/" + name).directoryType(DirectoryType.LIBRARY).build());
    }

    private UploadSessionEntity session(DirectoryEntity directory, UploadSessionStatus status, Instant lastActivity) {
        return em.persist(UploadSessionEntity.builder().directoryEntity(directory).targetParent("")
                .overwrite(false).status(status).lastActivityAt(lastActivity).build());
    }

    private UploadFileEntity file(UploadSessionEntity session, String target, long size, long received,
                                  UploadFileStatus status) {
        return em.persist(UploadFileEntity.builder().uploadSession(session).relativePath("rel/" + target)
                .targetPath(target).size(size).chunkSize(16).receivedBytes(received).status(status).build());
    }

    @Test
    void onlyOneActiveUploadPerTargetPath() {
        DirectoryEntity directory = directory("unique");
        UploadSessionEntity first = session(directory, UploadSessionStatus.ACTIVE, Instant.now());
        UploadSessionEntity second = session(directory, UploadSessionStatus.ACTIVE, Instant.now());
        file(first, "/media/unique/a.mkv", 100, 0, UploadFileStatus.FAILED);
        file(first, "/media/unique/a.mkv", 100, 0, UploadFileStatus.UPLOADING);
        em.flush();

        assertEquals(List.of("/media/unique/a.mkv"),
                fileRepository.findActiveTargetPathsIn(List.of("/media/unique/a.mkv", "/media/unique/b.mkv")));
        // a finished or failed attempt does not block a new one, a running one does
        assertThrows(PersistenceException.class, () -> {
            file(second, "/media/unique/a.mkv", 100, 0, UploadFileStatus.PENDING);
            em.flush();
        });
    }

    @Test
    void sumsWhatActiveUploadsStillHaveToDeliver() {
        DirectoryEntity directory = directory("space");
        DirectoryEntity other = directory("space-other");
        UploadSessionEntity active = session(directory, UploadSessionStatus.ACTIVE, Instant.now());
        UploadSessionEntity aborted = session(directory, UploadSessionStatus.ABORTED, Instant.now());
        file(active, "/media/space/a.mkv", 1000, 400, UploadFileStatus.UPLOADING);
        file(active, "/media/space/b.mkv", 500, 0, UploadFileStatus.PENDING);
        file(active, "/media/space/c.mkv", 300, 300, UploadFileStatus.COMPLETED);
        file(aborted, "/media/space/d.mkv", 9000, 0, UploadFileStatus.FAILED);
        file(session(other, UploadSessionStatus.ACTIVE, Instant.now()), "/media/space-other/e.mkv", 7000, 0,
                UploadFileStatus.PENDING);
        em.flush();

        assertEquals(600 + 500, fileRepository.sumRemainingBytesByDirectory(directory.getId()));
        assertEquals(0, fileRepository.sumRemainingBytesByDirectory(directory("space-empty").getId()));
    }

    @Test
    void aRetriedPartReplacesItself() {
        UploadSessionEntity session = session(directory("parts"), UploadSessionStatus.ACTIVE, Instant.now());
        UploadFileEntity file = file(session, "/media/parts/a.mkv", 48, 0, UploadFileStatus.UPLOADING);
        em.flush();

        partRepository.upsert(file.getId(), 2, "\"etag-2\"", 16);
        partRepository.upsert(file.getId(), 1, "\"etag-1-first-try\"", 16);
        partRepository.upsert(file.getId(), 1, "\"etag-1\"", 16);
        em.clear();

        List<UploadPartEntity> parts = partRepository.findByUploadFileIdOrderByPartNumber(file.getId());
        assertEquals(List.of(1, 2), parts.stream().map(UploadPartEntity::getPartNumber).toList());
        assertEquals("\"etag-1\"", parts.getFirst().getEtag());
        assertEquals(32, partRepository.sumSizeByUploadFileId(file.getId()));
    }

    @Test
    void findsIdleSessionsAndKnownPaths() {
        DirectoryEntity directory = directory("idle");
        UploadSessionEntity idle = session(directory, UploadSessionStatus.ACTIVE, Instant.now().minus(2, ChronoUnit.DAYS));
        session(directory, UploadSessionStatus.ACTIVE, Instant.now());
        session(directory, UploadSessionStatus.COMPLETED, Instant.now().minus(2, ChronoUnit.DAYS));
        MediaFileEntity known = MediaFileEntity.builder().path("/media/idle/known.mkv").size(1).build();
        known.setDirectoryEntity(directory);
        em.persist(known);
        em.flush();

        List<UploadSessionEntity> found = sessionRepository.findByStatusAndLastActivityAtBefore(
                UploadSessionStatus.ACTIVE, Instant.now().minus(1, ChronoUnit.DAYS));
        assertEquals(List.of(idle.getId()), found.stream().map(UploadSessionEntity::getId).toList());
        assertEquals(2, sessionRepository.countByStatus(UploadSessionStatus.ACTIVE));
        assertTrue(mediaFileRepository.findPathsByDirectoryEntityIdAndPathIn(directory.getId(),
                List.of("/media/idle/known.mkv", "/media/idle/new.mkv")).contains("/media/idle/known.mkv"));
        assertEquals(1, mediaFileRepository.findPathsByDirectoryEntityIdAndPathIn(directory.getId(),
                List.of("/media/idle/known.mkv", "/media/idle/new.mkv")).size());
    }
}
