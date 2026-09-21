package app.ister.disk.upload;

import app.ister.core.entity.DirectoryEntity;
import app.ister.core.entity.LibraryEntity;
import app.ister.core.entity.UploadFileEntity;
import app.ister.core.entity.UploadSessionEntity;
import app.ister.core.enums.DirectoryType;
import app.ister.core.enums.LibraryType;
import app.ister.core.enums.UploadFileStatus;
import app.ister.core.enums.UploadSessionStatus;
import app.ister.core.repository.DirectoryRepository;
import app.ister.core.repository.UploadFileRepository;
import app.ister.core.repository.UploadPartRepository;
import app.ister.core.repository.UploadSessionRepository;
import app.ister.core.storage.LibraryWriteStore;
import app.ister.core.storage.LibraryWriteStoreResolver;
import app.ister.core.storage.LocalLibraryWriteStore;
import app.ister.disk.upload.UploadDtos.ChunkResponse;
import app.ister.disk.upload.UploadDtos.Entry;
import app.ister.disk.upload.UploadDtos.PlanRequest;
import app.ister.disk.upload.UploadDtos.PreviewEntry;
import app.ister.disk.upload.UploadDtos.PreviewResponse;
import app.ister.disk.upload.UploadDtos.PreviewStatus;
import app.ister.disk.upload.UploadDtos.SessionResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.util.unit.DataSize;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** The session flow on a real local write store; the repositories are small in-memory stand-ins. */
class UploadSessionServiceTest {

    @TempDir
    Path root;

    private final UploadProperties properties = new UploadProperties();
    private final UploadPreviewService previewService = mock(UploadPreviewService.class);
    private final UploadedFilePublisher publisher = mock(UploadedFilePublisher.class);
    private final LibraryWriteStoreResolver writeStoreResolver = mock(LibraryWriteStoreResolver.class);
    private final DirectoryRepository directoryRepository = mock(DirectoryRepository.class);
    private final UploadSessionRepository sessionRepository = mock(UploadSessionRepository.class);
    private final UploadFileRepository fileRepository = mock(UploadFileRepository.class);
    private final UploadPartRepository partRepository = mock(UploadPartRepository.class);

    private final Map<UUID, UploadSessionEntity> sessions = new LinkedHashMap<>();
    private final Map<UUID, UploadFileEntity> files = new LinkedHashMap<>();

    private DirectoryEntity directory;
    private UploadSessionService service;

    @BeforeEach
    void setUp() {
        properties.setMinFreeSpace(DataSize.ofBytes(0));
        directory = DirectoryEntity.builder().name("shows").path(root.toString()).directoryType(DirectoryType.LIBRARY)
                .libraryEntity(LibraryEntity.builder().name("Shows").libraryType(LibraryType.SHOW).build()).build();
        directory.setId(UUID.randomUUID());
        when(directoryRepository.findById(directory.getId())).thenReturn(Optional.of(directory));
        when(writeStoreResolver.canWrite(directory)).thenReturn(true);
        when(writeStoreResolver.storeFor(directory)).thenAnswer(_ -> new LocalLibraryWriteStore(directory));

        when(sessionRepository.save(any())).thenAnswer(call -> {
            UploadSessionEntity session = call.getArgument(0);
            if (session.getId() == null) {
                session.setId(UUID.randomUUID());
            }
            sessions.put(session.getId(), session);
            return session;
        });
        when(sessionRepository.findById(any())).thenAnswer(call -> Optional.ofNullable(sessions.get(call.<UUID>getArgument(0))));
        when(sessionRepository.countByStatus(UploadSessionStatus.ACTIVE)).thenAnswer(_ ->
                sessions.values().stream().filter(s -> s.getStatus() == UploadSessionStatus.ACTIVE).count());
        when(fileRepository.save(any())).thenAnswer(call -> {
            UploadFileEntity file = call.getArgument(0);
            if (file.getId() == null) {
                file.setId(UUID.randomUUID());
            }
            files.put(file.getId(), file);
            return file;
        });
        when(fileRepository.findById(any())).thenAnswer(call -> Optional.ofNullable(files.get(call.<UUID>getArgument(0))));
        when(fileRepository.findByUploadSessionIdOrderByTargetPath(any())).thenAnswer(call -> files.values().stream()
                .filter(f -> f.getUploadSession().getId().equals(call.<UUID>getArgument(0)))
                .sorted(Comparator.comparing(UploadFileEntity::getTargetPath)).toList());

        service = new UploadSessionService(properties, previewService, publisher, writeStoreResolver,
                directoryRepository, sessionRepository, fileRepository, partRepository,
                mock(PlatformTransactionManager.class));
    }

    private String target(String relative) {
        return root.resolve(relative).toString();
    }

    /** A plan whose preview says: every given file is recognised. */
    private PlanRequest plan(boolean overwrite, Map<String, Long> sizes) {
        List<Entry> entries = sizes.entrySet().stream().map(e -> new Entry(e.getKey(), e.getValue())).toList();
        PlanRequest request = new PlanRequest(directory.getId(), null, "Show (2019)", overwrite, entries);
        List<PreviewEntry> verdicts = entries.stream().map(e -> new PreviewEntry(e.relativePath(),
                target("Show (2019)/" + e.relativePath()), e.size(), PreviewStatus.RECOGNISED, null, null, null)).toList();
        when(previewService.preview(directory, request)).thenReturn(new PreviewResponse(directory.getId(),
                LibraryType.SHOW, List.of(), verdicts, entries.stream().mapToLong(Entry::size).sum(), entries.size()));
        return request;
    }

    private static ByteArrayInputStream body(String content) {
        return new ByteArrayInputStream(content.getBytes());
    }

    @Test
    void uploadsAFileInChunksAndHandsItToTheScanner() throws IOException {
        SessionResponse session = service.create(plan(false, Map.of("Season 01/s01e01.mkv", 10L)), null);
        UUID sessionId = session.sessionId();
        UUID fileId = session.files().getFirst().fileId();
        assertThat(session.files().getFirst().chunkSize()).isGreaterThanOrEqualTo(UploadProperties.MIN_CHUNK_BYTES);

        assertThat(service.chunk(sessionId, fileId, 0, 5, body("hello")).receivedBytes()).isEqualTo(5);
        assertThat(service.get(sessionId).files().getFirst().receivedBytes()).isEqualTo(5);
        assertThat(service.chunk(sessionId, fileId, 5, 5, body("world")).receivedBytes()).isEqualTo(10);

        ChunkResponse done = service.complete(sessionId, fileId);

        assertThat(done.status()).isEqualTo(UploadFileStatus.COMPLETED);
        assertThat(Files.readString(root.resolve("Show (2019)/Season 01/s01e01.mkv"))).isEqualTo("helloworld");
        verify(publisher).published(directory, target("Show (2019)/Season 01/s01e01.mkv"), 10);
        assertThat(service.get(sessionId).status()).isEqualTo(UploadSessionStatus.COMPLETED);
        assertThat(root.resolve(LibraryWriteStore.STAGING_DIR).resolve(sessionId.toString())).doesNotExist();
        // completing again is harmless
        assertThat(service.complete(sessionId, fileId).status()).isEqualTo(UploadFileStatus.COMPLETED);
    }

    @Test
    void tellsAClientWithTheWrongOffsetWhereToContinue() throws IOException {
        SessionResponse session = service.create(plan(false, Map.of("a.mkv", 10L)), null);
        UUID fileId = session.files().getFirst().fileId();
        service.chunk(session.sessionId(), fileId, 0, 5, body("hello"));

        assertThatThrownBy(() -> service.chunk(session.sessionId(), fileId, 7, 3, body("rld")))
                .isInstanceOfSatisfying(UploadException.class, e -> {
                    assertThat(e.status()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(e.body()).isEqualTo(new ChunkResponse(fileId, 5, UploadFileStatus.UPLOADING));
                });
    }

    @Test
    void aReplayedChunkChangesNothing() throws IOException {
        SessionResponse session = service.create(plan(false, Map.of("a.mkv", 10L)), null);
        UUID fileId = session.files().getFirst().fileId();
        service.chunk(session.sessionId(), fileId, 0, 5, body("hello"));

        // the response to the first chunk got lost; the client sends it again, garbled or not
        assertThat(service.chunk(session.sessionId(), fileId, 0, 5, body("XXXXX")).receivedBytes()).isEqualTo(5);
        service.chunk(session.sessionId(), fileId, 5, 5, body("world"));
        service.complete(session.sessionId(), fileId);

        assertThat(Files.readString(root.resolve("Show (2019)/a.mkv"))).isEqualTo("helloworld");
    }

    @Test
    void refusesToCompleteAnIncompleteFile() throws IOException {
        SessionResponse session = service.create(plan(false, Map.of("a.mkv", 10L)), null);
        UUID fileId = session.files().getFirst().fileId();
        service.chunk(session.sessionId(), fileId, 0, 5, body("hello"));

        assertThatThrownBy(() -> service.complete(session.sessionId(), fileId))
                .isInstanceOfSatisfying(UploadException.class, e -> assertThat(e.status()).isEqualTo(HttpStatus.CONFLICT));
        verify(publisher, never()).published(any(), anyString(), anyLong());
    }

    @Test
    void skipsATargetThatAppearedSinceThePreview() throws IOException {
        SessionResponse session = service.create(plan(false, Map.of("a.mkv", 3L)), null);
        UUID fileId = session.files().getFirst().fileId();
        service.chunk(session.sessionId(), fileId, 0, 3, body("new"));
        Files.createDirectories(root.resolve("Show (2019)"));
        Files.writeString(root.resolve("Show (2019)/a.mkv"), "old");

        assertThat(service.complete(session.sessionId(), fileId).status()).isEqualTo(UploadFileStatus.SKIPPED);
        assertThat(Files.readString(root.resolve("Show (2019)/a.mkv"))).isEqualTo("old");
        verify(publisher, never()).published(any(), anyString(), anyLong());
    }

    @Test
    void overwritesWhenTheSessionSaysSo() throws IOException {
        Files.createDirectories(root.resolve("Show (2019)"));
        Files.writeString(root.resolve("Show (2019)/a.mkv"), "old");
        SessionResponse session = service.create(plan(true, Map.of("a.mkv", 3L)), null);
        UUID fileId = session.files().getFirst().fileId();
        service.chunk(session.sessionId(), fileId, 0, 3, body("new"));

        assertThat(service.complete(session.sessionId(), fileId).status()).isEqualTo(UploadFileStatus.COMPLETED);
        assertThat(Files.readString(root.resolve("Show (2019)/a.mkv"))).isEqualTo("new");
        verify(publisher).published(directory, target("Show (2019)/a.mkv"), 3L);
    }

    @Test
    void abortRemovesStagedBytesAndReleasesTheFiles() throws IOException {
        SessionResponse session = service.create(plan(false, Map.of("a.mkv", 10L, "b.mkv", 10L)), null);
        UUID fileId = session.files().getFirst().fileId();
        service.chunk(session.sessionId(), fileId, 0, 5, body("hello"));

        SessionResponse aborted = service.abort(session.sessionId());

        assertThat(aborted.status()).isEqualTo(UploadSessionStatus.ABORTED);
        assertThat(aborted.files()).allSatisfy(f -> assertThat(f.status()).isEqualTo(UploadFileStatus.FAILED));
        assertThat(root.resolve(LibraryWriteStore.STAGING_DIR).resolve(session.sessionId().toString())).doesNotExist();
        assertThatThrownBy(() -> service.chunk(session.sessionId(), fileId, 5, 5, body("world")))
                .isInstanceOfSatisfying(UploadException.class, e -> assertThat(e.status()).isEqualTo(HttpStatus.CONFLICT));
    }

    @Test
    void refusesASessionThatDoesNotFitTheDisk() {
        when(fileRepository.sumRemainingBytesByDirectory(directory.getId())).thenReturn(Long.MAX_VALUE / 2);
        PlanRequest request = plan(false, Map.of("a.mkv", 10L));

        assertThatThrownBy(() -> service.create(request, null)).isInstanceOfSatisfying(UploadException.class,
                e -> assertThat(e.status()).isEqualTo(HttpStatus.INSUFFICIENT_STORAGE));
        assertThat(sessions).isEmpty();
    }

    @Test
    void limitsTheNumberOfRunningSessions() {
        properties.setMaxActiveSessions(1);
        service.create(plan(false, Map.of("a.mkv", 10L)), null);
        PlanRequest second = plan(false, Map.of("b.mkv", 10L));

        assertThatThrownBy(() -> service.create(second, null)).isInstanceOfSatisfying(UploadException.class,
                e -> assertThat(e.status()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS));
    }

    @Test
    void sendsARequestForAnotherNodesDirectoryAway() {
        when(writeStoreResolver.storeFor(directory)).thenThrow(new IllegalStateException("served by node b"));
        PlanRequest request = plan(false, Map.of("a.mkv", 10L));

        assertThatThrownBy(() -> service.create(request, null)).isInstanceOfSatisfying(UploadException.class,
                e -> assertThat(e.status()).isEqualTo(HttpStatus.MISDIRECTED_REQUEST));
    }

    @Test
    void refusesEverythingWhenDisabled() {
        properties.setEnabled(false);
        PlanRequest request = plan(false, Map.of("a.mkv", 10L));

        assertThatThrownBy(() -> service.create(request, null)).isInstanceOfSatisfying(UploadException.class,
                e -> assertThat(e.status()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE));
    }

    @Test
    void refusesAPlanWithNothingTheLibraryWouldPickUp() {
        PlanRequest request = new PlanRequest(directory.getId(), null, null, false, List.of(new Entry("notes.txt", 5)));
        when(previewService.preview(directory, request)).thenReturn(new PreviewResponse(directory.getId(),
                LibraryType.SHOW, List.of(), List.of(new PreviewEntry("notes.txt", target("notes.txt"), 5,
                PreviewStatus.IGNORED, UploadDtos.IgnoreReason.UNSUPPORTED_FILE, null, null)), 0, 0));

        assertThatThrownBy(() -> service.create(request, null)).isInstanceOf(IllegalArgumentException.class);
    }
}
