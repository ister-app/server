package app.ister.core.storage;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The cluster-shared copy of the HLS transcode working directories ({@code {mediaFileId}/…}):
 * the node running a pass publishes finished segments, done markers and playlists here, and
 * every node serving playback reads them through into its local tmp dir on a miss. Encoding
 * itself always happens on a local filesystem — ffmpeg's segment muxer, the stability checks and
 * the cleanup rules stay exactly as they are.
 */
public interface TmpStore {

    Optional<ObjectStat> stat(UUID mediaFileId, String fileName);

    /** Downloads one file of the media file's working directory; {@code false} when it is not there (yet). */
    boolean copyToLocal(UUID mediaFileId, String fileName, Path target) throws IOException;

    /** Publishes a finished local file (segment, done marker, playlist) under the media file's prefix. */
    void put(UUID mediaFileId, Path file) throws IOException;

    /** Every published file name of the media file. */
    List<ObjectStat> list(UUID mediaFileId);

    /** The media file ids that have a working directory published. */
    List<UUID> mediaFileIds();

    /** Newest modification time across the media file's published files. */
    Optional<Instant> lastActivity(UUID mediaFileId);

    /** Removes everything published for the media file. */
    void deleteAll(UUID mediaFileId) throws IOException;
}
