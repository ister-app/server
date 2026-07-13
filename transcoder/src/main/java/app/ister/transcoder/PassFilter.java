package app.ister.transcoder;

import java.util.List;
import java.util.Set;

/**
 * Narrows the FFmpeg passes a pre-transcode produces down to what the interested users will actually
 * play. Without it, an episode with seven audio streams costs sixteen passes — two video plus every
 * stream in two bitrates — while nobody ever selects five of those languages.
 *
 * <p>Interactive playback uses {@link #none()}: it must be able to serve any track the player asks for,
 * and it starts passes lazily anyway (one per requested segment).
 *
 * @param audioLanguages audio languages to transcode; empty means every audio stream
 * @param maxVideoHeight highest video variant to produce; null means every variant
 * @param preTranscode   background pre-transcode: skips the 64k audio bitrate, which no master
 *                       playlist ever references (the builder folds that group into 192k)
 */
public record PassFilter(Set<String> audioLanguages, Integer maxVideoHeight, boolean preTranscode) {

    /** No restrictions: every audio stream, every quality. */
    public static PassFilter none() {
        return new PassFilter(Set.of(), null, false);
    }

    /** The filter for a pre-transcode request; a null or empty language list means every stream. */
    public static PassFilter preTranscode(List<String> audioLanguages, Integer maxVideoHeight) {
        return new PassFilter(audioLanguages == null ? Set.of() : Set.copyOf(audioLanguages), maxVideoHeight, true);
    }
}
