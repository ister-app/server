package app.ister.core.storage;

import java.time.Instant;

/** Metadata of one object, as returned by a HEAD or a listing. */
public record ObjectStat(String key, long size, Instant lastModified, String etag, String contentType) {
}
