package app.ister.api.dto;

import app.ister.core.enums.SubtitleFormat;

import java.util.UUID;

/**
 * Stream settings the client is currently playing with, reported via updatePlayQueue.
 * Used to prefetch (pre-transcode) the next queue item in the same format. {@code mediaFileId}
 * names the file the client opened when the item has several (versions); null means the first.
 */
public record StreamSettingsInput(Boolean direct, Boolean transcode, SubtitleFormat subtitleFormat, UUID mediaFileId) {
}
