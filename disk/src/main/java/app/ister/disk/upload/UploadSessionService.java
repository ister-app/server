package app.ister.disk.upload;

import app.ister.core.entity.DirectoryEntity;
import app.ister.core.entity.UploadFileEntity;
import app.ister.core.entity.UploadPartEntity;
import app.ister.core.entity.UploadSessionEntity;
import app.ister.core.entity.UserEntity;
import app.ister.core.enums.UploadFileStatus;
import app.ister.core.enums.UploadSessionStatus;
import app.ister.core.repository.DirectoryRepository;
import app.ister.core.repository.UploadFileRepository;
import app.ister.core.repository.UploadPartRepository;
import app.ister.core.repository.UploadSessionRepository;
import app.ister.core.storage.LibraryWriteStore;
import app.ister.core.storage.LibraryWriteStore.Staged;
import app.ister.core.storage.LibraryWriteStoreResolver;
import app.ister.core.storage.ObjectStore;
import app.ister.disk.upload.UploadDtos.ChunkResponse;
import app.ister.disk.upload.UploadDtos.FileState;
import app.ister.disk.upload.UploadDtos.PlanRequest;
import app.ister.disk.upload.UploadDtos.PreviewEntry;
import app.ister.disk.upload.UploadDtos.PreviewResponse;
import app.ister.disk.upload.UploadDtos.SessionResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.FileAlreadyExistsException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.locks.ReentrantLock;

/**
 * The life of an upload: plan it, take its chunks, put each finished file in place and hand it to
 * the scanner, and clean up when it is cancelled or abandoned.
 *
 * <p>Transactions are explicit and short. The bytes of a chunk stream in for as long as the
 * client's uplink takes, and nothing may hold a database transaction open across that: every
 * chunk is "read what we know", then the transfer, then "write what changed".
 */
@Slf4j
@Service
public class UploadSessionService {

    private final UploadProperties properties;
    private final UploadPreviewService previewService;
    private final UploadedFilePublisher publisher;
    private final LibraryWriteStoreResolver writeStoreResolver;
    private final DirectoryRepository directoryRepository;
    private final UploadSessionRepository sessionRepository;
    private final UploadFileRepository fileRepository;
    private final UploadPartRepository partRepository;
    private final TransactionTemplate tx;
    private final Semaphore chunkSlots;
    /** One writer per file: a LOCAL part file is appended to, two appenders would interleave. */
    private final ConcurrentHashMap<UUID, ReentrantLock> fileLocks = new ConcurrentHashMap<>();

    @SuppressWarnings("java:S107") // collaborators of one cohesive flow
    public UploadSessionService(UploadProperties properties, UploadPreviewService previewService,
                                UploadedFilePublisher publisher, LibraryWriteStoreResolver writeStoreResolver,
                                DirectoryRepository directoryRepository, UploadSessionRepository sessionRepository,
                                UploadFileRepository fileRepository, UploadPartRepository partRepository,
                                PlatformTransactionManager transactionManager) {
        this.properties = properties;
        this.previewService = previewService;
        this.publisher = publisher;
        this.writeStoreResolver = writeStoreResolver;
        this.directoryRepository = directoryRepository;
        this.sessionRepository = sessionRepository;
        this.fileRepository = fileRepository;
        this.partRepository = partRepository;
        this.tx = new TransactionTemplate(transactionManager);
        this.chunkSlots = new Semaphore(Math.max(1, properties.getMaxConcurrentChunks()));
    }

    // ---- planning -------------------------------------------------------------------------------

    public PreviewResponse preview(PlanRequest request) {
        requireEnabled();
        return tx.execute(_ -> previewService.preview(directory(request.directoryId()), request));
    }

    /**
     * Turns a plan into a session. The preview is run again here: what the client saw is a
     * courtesy, what gets written is decided by the server, at the moment it commits to it.
     */
    public SessionResponse create(PlanRequest request, UserEntity user) {
        requireEnabled();
        if (request.entries() == null || request.entries().isEmpty()) {
            throw new IllegalArgumentException("Nothing to upload");
        }
        if (request.entries().size() > properties.getMaxFilesPerSession()) {
            throw new IllegalArgumentException("Too many files in one upload (max "
                    + properties.getMaxFilesPerSession() + "); split it up");
        }
        try {
            return tx.execute(_ -> createInTransaction(request, user));
        } catch (DataIntegrityViolationException _) {
            // lost the race for a target path against a session that was created meanwhile
            throw new UploadException(HttpStatus.CONFLICT, "Another upload just claimed one of these files; preview again");
        }
    }

    private SessionResponse createInTransaction(PlanRequest request, UserEntity user) {
        DirectoryEntity directory = directory(request.directoryId());
        LibraryWriteStore store = storeFor(directory);
        if (!store.writable()) {
            throw new UploadException(HttpStatus.CONFLICT, "Directory " + directory.getName() + " is mounted read-only");
        }
        if (sessionRepository.countByStatus(UploadSessionStatus.ACTIVE) >= properties.getMaxActiveSessions()) {
            throw new UploadException(HttpStatus.TOO_MANY_REQUESTS, "Too many uploads are running; finish or cancel one first");
        }
        PreviewResponse preview = previewService.preview(directory, request);
        if (preview.uploadFiles() == 0) {
            throw new IllegalArgumentException("Nothing to upload: no file would be picked up by the library");
        }
        requireSpace(directory, store, preview.uploadBytes());

        Instant now = Instant.now();
        UploadSessionEntity session = sessionRepository.save(UploadSessionEntity.builder()
                .directoryEntity(directory).userEntity(user)
                .targetParent(request.targetParent() == null ? "" : request.targetParent().strip())
                .rootName(request.rootName() == null || request.rootName().isBlank() ? null : request.rootName().strip())
                .overwrite(request.overwrite()).status(UploadSessionStatus.ACTIVE).lastActivityAt(now).build());

        List<FileState> files = new ArrayList<>();
        List<PreviewEntry> skipped = new ArrayList<>();
        for (PreviewEntry entry : preview.entries()) {
            if (!UploadPreviewService.willUpload(entry.status(), request.overwrite())) {
                skipped.add(entry);
                continue;
            }
            UploadFileEntity file = fileRepository.save(UploadFileEntity.builder()
                    .uploadSession(session).relativePath(entry.relativePath()).targetPath(entry.targetPath())
                    .size(entry.size()).chunkSize(properties.chunkSizeFor(entry.size()))
                    .receivedBytes(0).status(UploadFileStatus.PENDING).build());
            files.add(state(file, List.of()));
        }
        log.info("Upload session {} by {}: {} files ({} bytes) into {}", session.getId(),
                user == null ? "?" : user.getId(), files.size(), preview.uploadBytes(), directory.getName());
        return new SessionResponse(session.getId(), session.getStatus(), directory.getId(), session.isOverwrite(),
                files, skipped);
    }

    private void requireSpace(DirectoryEntity directory, LibraryWriteStore store, long uploadBytes) {
        if (store.usableSpace().isEmpty()) {
            return;
        }
        long promised = fileRepository.sumRemainingBytesByDirectory(directory.getId());
        long available = store.usableSpace().getAsLong() - promised - properties.getMinFreeSpace().toBytes();
        if (uploadBytes > available) {
            throw new UploadException(HttpStatus.INSUFFICIENT_STORAGE, "Not enough space on " + directory.getName()
                    + ": " + uploadBytes + " bytes needed, " + Math.max(0, available) + " available");
        }
    }

    // ---- state ----------------------------------------------------------------------------------

    public SessionResponse get(UUID sessionId) {
        return tx.execute(_ -> {
            UploadSessionEntity session = session(sessionId);
            List<FileState> files = fileRepository.findByUploadSessionIdOrderByTargetPath(sessionId).stream()
                    .map(file -> state(file, completedParts(file))).toList();
            return new SessionResponse(session.getId(), session.getStatus(), session.getDirectoryEntity().getId(),
                    session.isOverwrite(), files, List.of());
        });
    }

    private List<Integer> completedParts(UploadFileEntity file) {
        if (file.getS3UploadId() == null) {
            return List.of();
        }
        return partRepository.findByUploadFileIdOrderByPartNumber(file.getId()).stream()
                .map(UploadPartEntity::getPartNumber).toList();
    }

    private static FileState state(UploadFileEntity file, List<Integer> completedParts) {
        return new FileState(file.getId(), file.getRelativePath(), file.getTargetPath(), file.getSize(),
                file.getChunkSize(), file.getReceivedBytes(), file.getStatus(),
                completedParts.isEmpty() ? null : completedParts);
    }

    // ---- chunks ---------------------------------------------------------------------------------

    /** What a chunk needs to know about its file, read in one short transaction. */
    private record ChunkContext(LibraryWriteStore store, Staged staged, boolean s3, long size, long chunkSize,
                                long receivedBytes) {
    }

    public ChunkResponse chunk(UUID sessionId, UUID fileId, long offset, long length, InputStream body)
            throws IOException {
        requireEnabled();
        if (!chunkSlots.tryAcquire()) {
            throw new UploadException(HttpStatus.TOO_MANY_REQUESTS, "Too many chunks in flight; retry shortly");
        }
        ReentrantLock lock = fileLocks.computeIfAbsent(fileId, _ -> new ReentrantLock());
        if (!lock.tryLock()) {
            chunkSlots.release();
            // Usually the previous attempt of the same chunk, which the client gave up on and the
            // server is still reading: not a conflict to resolve but a moment to wait out.
            throw new UploadException(HttpStatus.TOO_MANY_REQUESTS, "Another chunk of this file is being written");
        }
        try {
            ChunkContext context = tx.execute(_ -> chunkContext(sessionId, fileId));
            validate(context, fileId, offset, length);
            if (!context.s3() && offset + length <= context.receivedBytes()) {
                // A replay: the response to this chunk got lost, the bytes did not.
                body.transferTo(OutputStream.nullOutputStream());
                return new ChunkResponse(fileId, context.receivedBytes(), UploadFileStatus.UPLOADING);
            }
            int partNumber = (int) (offset / context.chunkSize()) + 1;
            String etag = context.store().writeChunk(context.staged(), offset, partNumber, body, length);
            return tx.execute(_ -> recordChunk(sessionId, fileId, offset, length, partNumber, etag));
        } finally {
            lock.unlock();
            chunkSlots.release();
        }
    }

    private ChunkContext chunkContext(UUID sessionId, UUID fileId) {
        UploadFileEntity file = file(sessionId, fileId);
        UploadSessionEntity session = file.getUploadSession();
        if (session.getStatus() != UploadSessionStatus.ACTIVE || !file.getStatus().isActive()) {
            throw new UploadException(HttpStatus.CONFLICT, "This file no longer takes chunks ("
                    + session.getStatus() + "/" + file.getStatus() + ")");
        }
        DirectoryEntity directory = session.getDirectoryEntity();
        LibraryWriteStore store = storeFor(directory);
        Staged staged = new Staged(sessionId, fileId, file.getTargetPath(), file.getS3UploadId());
        if (file.getStatus() == UploadFileStatus.PENDING) {
            try {
                staged = store.begin(sessionId, fileId, file.getTargetPath(), file.getSize());
            } catch (IOException e) {
                throw new UploadException(HttpStatus.SERVICE_UNAVAILABLE, "Cannot stage " + file.getTargetPath() + ": " + e.getMessage());
            }
            file.setS3UploadId(staged.uploadId());
            file.setStatus(UploadFileStatus.UPLOADING);
            fileRepository.save(file);
        }
        return new ChunkContext(store, staged, directory.isS3(), file.getSize(), file.getChunkSize(),
                file.getReceivedBytes());
    }

    private static void validate(ChunkContext context, UUID fileId, long offset, long length) {
        if (offset < 0 || length <= 0 || length > context.chunkSize() || offset + length > context.size()) {
            throw new IllegalArgumentException("Chunk " + offset + "+" + length + " does not fit a file of "
                    + context.size() + " bytes in chunks of " + context.chunkSize());
        }
        if (context.s3()) {
            // Parts are addressed by number, so they may arrive in any order, but only on the grid.
            boolean last = offset + length == context.size();
            if (offset % context.chunkSize() != 0 || (!last && length != context.chunkSize())) {
                throw new IllegalArgumentException("Chunks of this file must be " + context.chunkSize()
                        + " bytes at multiples of that");
            }
            return;
        }
        boolean replay = offset + length <= context.receivedBytes();
        if (offset != context.receivedBytes() && !replay) {
            throw new UploadException(HttpStatus.CONFLICT, "File continues at " + context.receivedBytes(),
                    new ChunkResponse(fileId, context.receivedBytes(), UploadFileStatus.UPLOADING));
        }
    }

    private ChunkResponse recordChunk(UUID sessionId, UUID fileId, long offset, long length, int partNumber,
                                      String etag) {
        UploadFileEntity file = file(sessionId, fileId);
        if (etag != null) {
            partRepository.upsert(fileId, partNumber, etag, length);
            file.setReceivedBytes(partRepository.sumSizeByUploadFileId(fileId));
        } else {
            file.setReceivedBytes(offset + length);
        }
        fileRepository.save(file);
        file.getUploadSession().setLastActivityAt(Instant.now());
        sessionRepository.save(file.getUploadSession());
        return new ChunkResponse(fileId, file.getReceivedBytes(), file.getStatus());
    }

    // ---- completion -----------------------------------------------------------------------------

    public ChunkResponse complete(UUID sessionId, UUID fileId) throws IOException {
        requireEnabled();
        ReentrantLock lock = fileLocks.computeIfAbsent(fileId, _ -> new ReentrantLock());
        if (!lock.tryLock()) {
            throw new UploadException(HttpStatus.TOO_MANY_REQUESTS, "A chunk of this file is still being written");
        }
        try {
            IOException[] failure = new IOException[1];
            ChunkResponse response = tx.execute(_ -> {
                try {
                    return completeInTransaction(sessionId, fileId);
                } catch (IOException e) {
                    failure[0] = e;
                    return null;
                }
            });
            if (failure[0] != null) {
                throw failure[0];
            }
            return response;
        } finally {
            lock.unlock();
            fileLocks.remove(fileId, lock);
        }
    }

    private ChunkResponse completeInTransaction(UUID sessionId, UUID fileId) throws IOException {
        UploadFileEntity file = file(sessionId, fileId);
        UploadSessionEntity session = file.getUploadSession();
        if (!file.getStatus().isActive()) {
            return new ChunkResponse(fileId, file.getReceivedBytes(), file.getStatus());
        }
        if (session.getStatus() != UploadSessionStatus.ACTIVE) {
            throw new UploadException(HttpStatus.CONFLICT, "Session is " + session.getStatus());
        }
        if (file.getReceivedBytes() != file.getSize()) {
            throw new UploadException(HttpStatus.CONFLICT, "File is incomplete: " + file.getReceivedBytes() + " of "
                    + file.getSize() + " bytes", new ChunkResponse(fileId, file.getReceivedBytes(), file.getStatus()));
        }
        DirectoryEntity directory = session.getDirectoryEntity();
        LibraryWriteStore store = storeFor(directory);
        Staged staged = new Staged(sessionId, fileId, file.getTargetPath(), file.getS3UploadId());
        if (file.getStatus() == UploadFileStatus.PENDING) {
            // an empty file: there never was a chunk to begin it with
            staged = store.begin(sessionId, fileId, file.getTargetPath(), file.getSize());
        }
        List<ObjectStore.UploadedPart> parts = partRepository.findByUploadFileIdOrderByPartNumber(fileId).stream()
                .map(p -> new ObjectStore.UploadedPart(p.getPartNumber(), p.getEtag())).toList();
        try {
            store.complete(staged, parts, file.getSize(), session.isOverwrite());
            file.setStatus(UploadFileStatus.COMPLETED);
            publisher.published(directory, file.getTargetPath(), file.getSize());
        } catch (FileAlreadyExistsException _) {
            // Appeared since the preview (or this is a retry whose first attempt did put it there):
            // either way the library has a file at this path and the next scan knows about it.
            log.info("Upload target {} exists, skipping", file.getTargetPath());
            store.abort(staged);
            file.setStatus(UploadFileStatus.SKIPPED);
        }
        fileRepository.save(file);
        session.setLastActivityAt(Instant.now());
        finishSessionIfDone(session, store);
        return new ChunkResponse(fileId, file.getReceivedBytes(), file.getStatus());
    }

    private void finishSessionIfDone(UploadSessionEntity session, LibraryWriteStore store) throws IOException {
        boolean open = fileRepository.findByUploadSessionIdOrderByTargetPath(session.getId()).stream()
                .anyMatch(f -> f.getStatus().isActive());
        if (!open) {
            session.setStatus(UploadSessionStatus.COMPLETED);
            store.abortSession(session.getId());
            log.info("Upload session {} completed", session.getId());
        }
        sessionRepository.save(session);
    }

    // ---- ending a session early -----------------------------------------------------------------

    public SessionResponse abort(UUID sessionId) {
        tx.executeWithoutResult(_ -> end(session(sessionId), UploadSessionStatus.ABORTED));
        return get(sessionId);
    }

    /**
     * Ends a session and removes what it staged. Only on a node that can reach the storage; the
     * cleanup scheduler of the node that can will take a session this one cannot.
     *
     * @return whether the session was ended here
     */
    boolean end(UploadSessionEntity session, UploadSessionStatus endStatus) {
        if (session.getStatus() != UploadSessionStatus.ACTIVE) {
            return false;
        }
        DirectoryEntity directory = session.getDirectoryEntity();
        if (!writeStoreResolver.canWrite(directory)) {
            return false;
        }
        LibraryWriteStore store = writeStoreResolver.storeFor(directory);
        for (UploadFileEntity file : fileRepository.findByUploadSessionIdOrderByTargetPath(session.getId())) {
            if (!file.getStatus().isActive()) {
                continue;
            }
            try {
                store.abort(new Staged(session.getId(), file.getId(), file.getTargetPath(), file.getS3UploadId()));
            } catch (IOException e) {
                log.warn("Could not discard staged bytes of {}: {}", file.getTargetPath(), e.getMessage());
            }
            file.setStatus(UploadFileStatus.FAILED);
            fileRepository.save(file);
            fileLocks.remove(file.getId());
        }
        try {
            store.abortSession(session.getId());
        } catch (IOException e) {
            log.warn("Could not remove the staging folder of session {}: {}", session.getId(), e.getMessage());
        }
        session.setStatus(endStatus);
        sessionRepository.save(session);
        log.info("Upload session {} {}", session.getId(), endStatus);
        return true;
    }

    // ---- lookups --------------------------------------------------------------------------------

    private void requireEnabled() {
        if (!properties.isEnabled()) {
            throw new UploadException(HttpStatus.SERVICE_UNAVAILABLE, "Uploads are disabled on this server");
        }
    }

    private DirectoryEntity directory(UUID directoryId) {
        if (directoryId == null) {
            throw new IllegalArgumentException("directoryId is required");
        }
        return directoryRepository.findById(directoryId).orElseThrow(NoSuchElementException::new);
    }

    private UploadSessionEntity session(UUID sessionId) {
        return sessionRepository.findById(sessionId).orElseThrow(NoSuchElementException::new);
    }

    private UploadFileEntity file(UUID sessionId, UUID fileId) {
        UploadFileEntity file = fileRepository.findById(fileId).orElseThrow(NoSuchElementException::new);
        if (!file.getUploadSession().getId().equals(sessionId)) {
            throw new NoSuchElementException();
        }
        return file;
    }

    /** A request that reached a node that cannot write the directory: the client has to go to its serving node. */
    private LibraryWriteStore storeFor(DirectoryEntity directory) {
        try {
            return writeStoreResolver.storeFor(directory);
        } catch (IllegalStateException e) {
            throw new UploadException(HttpStatus.MISDIRECTED_REQUEST, e.getMessage());
        }
    }
}
