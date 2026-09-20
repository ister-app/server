package app.ister.disk.upload;

import app.ister.core.entity.DirectoryEntity;
import app.ister.core.entity.NodeEntity;
import app.ister.core.enums.DirectoryType;
import app.ister.core.enums.LibraryType;
import app.ister.core.eventdata.NodeActivityStatusData;
import app.ister.core.eventdata.NodeActivityStatusData.DirectoryFact;
import app.ister.core.repository.DirectoryRepository;
import app.ister.core.service.UserService;
import app.ister.core.status.NodeActivityRegistry;
import app.ister.core.storage.LibraryWriteStore;
import app.ister.core.storage.LibraryWriteStoreResolver;
import app.ister.disk.upload.UploadDtos.ChunkResponse;
import app.ister.disk.upload.UploadDtos.DirectoryOption;
import app.ister.disk.upload.UploadDtos.PlanRequest;
import app.ister.disk.upload.UploadDtos.PreviewResponse;
import app.ister.disk.upload.UploadDtos.SessionResponse;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Admin upload of media into a library directory: pick a directory, preview how the scanner will
 * see the files, then send them in resumable chunks.
 *
 * <p>Everything but the first two calls must go to the node that can write the directory
 * ({@link DirectoryOption#nodeUrl()}): a chunk is written where it arrives, never forwarded.
 * Only GET and POST, and no custom headers: the player's web build calls this cross-origin, and
 * the CORS setup allows nothing more.
 *
 * <p>Bearer JWT only. Stream tokens are not accepted here on purpose: a token in a URL that can
 * write into the libraries is a worse thing to leak than one that can read a segment.
 */
@Slf4j
@RestController
@RequestMapping("/library-upload")
@PreAuthorize("hasRole('admin')")
public class LibraryUploadController {

    private final UploadSessionService sessionService;
    private final DirectoryRepository directoryRepository;
    private final LibraryWriteStoreResolver writeStoreResolver;
    private final NodeActivityRegistry nodeActivityRegistry;
    private final UserService userService;

    public LibraryUploadController(UploadSessionService sessionService, DirectoryRepository directoryRepository,
                                   LibraryWriteStoreResolver writeStoreResolver,
                                   NodeActivityRegistry nodeActivityRegistry, UserService userService) {
        this.sessionService = sessionService;
        this.directoryRepository = directoryRepository;
        this.writeStoreResolver = writeStoreResolver;
        this.nodeActivityRegistry = nodeActivityRegistry;
        this.userService = userService;
    }

    /** The directories an upload can go to, optionally of one library, with what the admin needs to choose between them. */
    @GetMapping("/directories")
    @Transactional(readOnly = true)
    public List<DirectoryOption> directories(@RequestParam(required = false) UUID libraryId) {
        List<DirectoryEntity> directories = libraryId == null
                ? directoryRepository.findByDirectoryType(DirectoryType.LIBRARY)
                : directoryRepository.findByDirectoryTypeAndLibraryEntityId(DirectoryType.LIBRARY, libraryId);
        Map<String, DirectoryFact> facts = nodeActivityRegistry.nodesSnapshot().stream()
                .map(NodeActivityStatusData::getFacts).filter(java.util.Objects::nonNull)
                .flatMap(f -> f.getDirectories() == null ? java.util.stream.Stream.<DirectoryFact>empty() : f.getDirectories().stream())
                .collect(Collectors.toMap(DirectoryFact::getName, f -> f, (a, _) -> a));
        return directories.stream()
                .filter(d -> d.getLibraryEntity() != null && d.getLibraryEntity().getLibraryType() != LibraryType.PODCAST)
                .map(d -> option(d, facts.get(d.getName())))
                .toList();
    }

    private DirectoryOption option(DirectoryEntity directory, DirectoryFact fact) {
        Long freeBytes = fact == null ? null : fact.getFreeBytes();
        // Unknown counts as writable: the session creation on the serving node has the last word.
        boolean writable = fact == null || !Boolean.FALSE.equals(fact.getWritable());
        if (writeStoreResolver.canWrite(directory)) {
            LibraryWriteStore store = writeStoreResolver.storeFor(directory);
            writable = store.writable();
            freeBytes = store.usableSpace().isPresent() ? Long.valueOf(store.usableSpace().getAsLong()) : null;
        }
        NodeEntity serving = writeStoreResolver.servingNode(directory);
        return new DirectoryOption(directory.getId(), directory.getName(), directory.getLibraryEntity().getId(),
                directory.getLibraryEntity().getName(), directory.getLibraryEntity().getLibraryType(),
                directory.getStorageKind(), directory.getPath(),
                serving == null ? null : serving.getName(), serving == null ? null : serving.getUrl(),
                freeBytes, writable);
    }

    @PostMapping("/preview")
    public PreviewResponse preview(@RequestBody PlanRequest request) {
        return sessionService.preview(request);
    }

    @PostMapping("/sessions")
    public SessionResponse create(@RequestBody PlanRequest request, Authentication authentication) {
        return sessionService.create(request, userService.getOrCreateUser(authentication));
    }

    /** The state a client resumes from: per file the bytes received, for S3 the parts stored. */
    @GetMapping("/sessions/{sessionId}")
    public SessionResponse get(@PathVariable UUID sessionId) {
        return sessionService.get(sessionId);
    }

    /**
     * One chunk, as the raw request body. {@code Content-Length} is required: the bytes are streamed
     * straight to where they are stored, and both the local and the S3 writer must know how many
     * to expect to tell a complete chunk from a connection that broke off.
     */
    @PostMapping("/sessions/{sessionId}/files/{fileId}/chunk")
    public ChunkResponse chunk(@PathVariable UUID sessionId, @PathVariable UUID fileId, @RequestParam long offset,
                               HttpServletRequest request) throws IOException {
        long length = request.getContentLengthLong();
        if (length < 0) {
            throw new UploadException(HttpStatus.LENGTH_REQUIRED, "Content-Length is required");
        }
        return sessionService.chunk(sessionId, fileId, offset, length, request.getInputStream());
    }

    @PostMapping("/sessions/{sessionId}/files/{fileId}/complete")
    public ChunkResponse complete(@PathVariable UUID sessionId, @PathVariable UUID fileId) throws IOException {
        return sessionService.complete(sessionId, fileId);
    }

    @PostMapping("/sessions/{sessionId}/abort")
    public SessionResponse abort(@PathVariable UUID sessionId) {
        return sessionService.abort(sessionId);
    }

    @ExceptionHandler(UploadException.class)
    public ResponseEntity<Object> handleUploadException(UploadException ex) {
        ResponseEntity.BodyBuilder response = ResponseEntity.status(ex.status());
        if (ex.status() == HttpStatus.TOO_MANY_REQUESTS) {
            response.header(HttpHeaders.RETRY_AFTER, "2");
        }
        return response.body(ex.body() != null ? ex.body() : ProblemDetail.forStatusAndDetail(ex.status(), ex.getMessage()));
    }
}
