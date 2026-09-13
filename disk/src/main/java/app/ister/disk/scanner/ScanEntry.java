package app.ister.disk.scanner;

import java.time.Instant;

/**
 * One file met during a scan, storage-agnostic: {@code path} is an absolute local path or an
 * {@code s3://bucket/key} uri. Produced by the filesystem walk and the S3 listing alike, consumed
 * by {@link ScanEntryDispatcher}.
 */
public record ScanEntry(String path, boolean regularFile, long size, Instant lastModified) {
}
