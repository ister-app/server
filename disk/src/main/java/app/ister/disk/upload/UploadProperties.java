package app.ister.disk.upload;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.unit.DataSize;

import java.time.Duration;

/** Limits of the admin media upload ({@code app.ister.upload.*}). */
@Getter
@Setter
@Configuration
@ConfigurationProperties(prefix = "app.ister.upload")
public class UploadProperties {

    /** S3 refuses multipart parts (but the last) under 5 MiB, and a chunk is a part. */
    public static final long MIN_CHUNK_BYTES = 5L * 1024 * 1024;
    /** S3 allows 10,000 parts per upload; staying under it leaves room for a rounding chunk. */
    private static final long MAX_CHUNKS_PER_FILE = 9_000;

    /** Runtime switch (not a bean condition: those are frozen in the native image). */
    private boolean enabled = true;

    /** Size of one chunk request. Keep it under what the reverse proxy accepts as a request body. */
    private DataSize chunkSize = DataSize.ofMegabytes(16);

    /** Sessions that may be active at once, cluster-wide. */
    private int maxActiveSessions = 2;

    private int maxFilesPerSession = 20_000;

    /** Chunk requests this node handles at once; more get 429. */
    private int maxConcurrentChunks = 4;

    /** A session nobody sent a chunk to for this long is expired and its staged bytes are removed. */
    private Duration sessionIdleTimeout = Duration.ofHours(24);

    /** Space a LOCAL directory must keep free after the upload. */
    private DataSize minFreeSpace = DataSize.ofGigabytes(5);

    /** The chunk size for a file: the configured one, raised for files that would otherwise need too many parts. */
    public long chunkSizeFor(long fileSize) {
        long configured = Math.max(chunkSize.toBytes(), MIN_CHUNK_BYTES);
        long needed = (fileSize + MAX_CHUNKS_PER_FILE - 1) / MAX_CHUNKS_PER_FILE;
        return Math.max(configured, needed);
    }
}
