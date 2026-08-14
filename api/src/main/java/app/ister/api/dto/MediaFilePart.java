package app.ister.api.dto;

import app.ister.core.entity.MediaFileEntity;

/**
 * One episode's time slice within a media file, for the GraphQL {@code MediaFilePart} type. For
 * normal single-episode files this is simply (file, 0, file duration).
 */
public record MediaFilePart(MediaFileEntity mediaFile, long startInMilliseconds, long durationInMilliseconds) {
}
