package app.ister.api.dto;

import app.ister.core.enums.SubtitleFormat;

/**
 * Stream settings the client is currently playing with, reported via updatePlayQueue.
 * Used to prefetch (pre-transcode) the next queue item in the same format.
 */
public record StreamSettingsInput(Boolean direct, Boolean transcode, SubtitleFormat subtitleFormat) {
}
