package app.ister.api.dto;

import java.util.List;

/**
 * Playback settings as saved from a client. The language lists are ordered — first match wins, the
 * way the player applies them.
 *
 * @param maxVideoHeight highest video variant to pre-transcode (720 or 480); null means every variant
 */
public record UserSettingsInput(List<String> preferredAudioLanguages, List<String> preferredSubtitleLanguages,
                                boolean directPlay, boolean transcode, Integer maxVideoHeight) {
}
