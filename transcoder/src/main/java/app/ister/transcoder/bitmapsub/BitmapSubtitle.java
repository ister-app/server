package app.ister.transcoder.bitmapsub;

import java.util.List;

/**
 * A parsed bitmap subtitle stream: the canvas the cue coordinates refer to
 * (the video frame the disc was authored for) and the cues in display order.
 */
public record BitmapSubtitle(int width, int height, List<BitmapCue> cues) {
}
