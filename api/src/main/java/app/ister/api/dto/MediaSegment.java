package app.ister.api.dto;

import app.ister.core.enums.SegmentType;

import java.util.UUID;

/**
 * A detected intro/outro range of a media file, in absolute file time.
 *
 * @param episodeId whose intro/outro this is within a multi-episode file; null for
 *                  single-episode files
 */
public record MediaSegment(UUID id, SegmentType type, long startInMilliseconds, long endInMilliseconds,
                           UUID episodeId) {
}
