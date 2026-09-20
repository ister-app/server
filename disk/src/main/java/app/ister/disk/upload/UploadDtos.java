package app.ister.disk.upload;

import app.ister.core.enums.LibraryType;
import app.ister.core.enums.StorageKind;
import app.ister.core.enums.UploadFileStatus;
import app.ister.core.enums.UploadSessionStatus;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;
import java.util.UUID;

/** Request and response bodies of {@link LibraryUploadController}. */
public final class UploadDtos {

    private UploadDtos() {
    }

    /** One file of the picked folder: its path relative to that folder, and its size. */
    public record Entry(String relativePath, long size) {
    }

    /**
     * @param targetParent relative path inside the directory to upload under; null/empty = its root
     *                     (an album goes under its artist folder, a show under the root)
     * @param rootName     name the picked folder gets on the server; null = the folder itself is
     *                     dropped and its children land directly under {@code targetParent}
     *                     (a folder full of artists)
     */
    public record PlanRequest(UUID directoryId, String targetParent, String rootName, boolean overwrite,
                              List<Entry> entries) {
    }

    public enum PreviewStatus {
        /** A scanner picks the file up; {@link PreviewEntry#recognition()} tells as what. */
        RECOGNISED,
        /** Nothing would scan it at this place; it is not uploaded. */
        IGNORED,
        /** The target is already there; skipped unless the session overwrites. */
        EXISTS,
        /** Another active upload is writing the same target. */
        BUSY,
        /** The path cannot be stored (traversal, dot-prefixed or reserved name, …). */
        INVALID,
        /** Two entries of this request map to the same target. */
        DUPLICATE
    }

    public enum IgnoreReason {
        /** No scanner of this library type takes this kind of file here. */
        UNSUPPORTED_FILE,
        /** A parent folder is at a level the scan does not descend into; detail = that folder. */
        FOLDER_NOT_SCANNED
    }

    /** What the path parsers made of a file. Only the fields of its library type are set. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Recognition(String kind,
                              String title, Integer year, Integer season, List<Integer> episodes,
                              String artist, String album, Integer disc, Integer track,
                              String author, String book, Integer chapter,
                              String series, Double volume) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record PreviewEntry(String relativePath, String targetPath, long size, PreviewStatus status,
                               IgnoreReason ignoreReason, String detail, Recognition recognition) {
    }

    /** A top-level folder the upload creates or extends, and the level the scanner sees it at. */
    public record PreviewRoot(String name, String level, boolean existing) {
    }

    public record PreviewResponse(UUID directoryId, LibraryType libraryType, List<PreviewRoot> roots,
                                  List<PreviewEntry> entries, long uploadBytes, int uploadFiles) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record DirectoryOption(UUID id, String name, UUID libraryId, String libraryName, LibraryType libraryType,
                                  StorageKind storageKind, String path, String nodeName, String nodeUrl,
                                  Long freeBytes, boolean writable) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record FileState(UUID fileId, String relativePath, String targetPath, long size, long chunkSize,
                            long receivedBytes, UploadFileStatus status, List<Integer> completedParts) {
    }

    public record SessionResponse(UUID sessionId, UploadSessionStatus status, UUID directoryId, boolean overwrite,
                                  List<FileState> files, List<PreviewEntry> skipped) {
    }

    /** Answer to a chunk: where the file continues. Also the body of a 409 on an offset mismatch. */
    public record ChunkResponse(UUID fileId, long receivedBytes, UploadFileStatus status) {
    }
}
