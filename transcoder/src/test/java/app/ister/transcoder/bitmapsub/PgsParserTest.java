package app.ister.transcoder.bitmapsub;

import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PgsParserTest {

    @Test
    void cueRunsUntilTheNextDisplaySet() {
        byte[] sup = new PgsStreamBuilder(1920, 1080)
                .show(1000, 700, 900, 400, 60, 235, 128, 128, 255)
                .clear(3500)
                .show(5000, 100, 50, 20, 10, 235, 128, 128, 255)
                .show(6000, 300, 60, 30, 12, 235, 128, 128, 255)
                .clear(8000)
                .build();

        BitmapSubtitle subtitle = PgsParser.parse(sup);

        assertEquals(1920, subtitle.width());
        assertEquals(1080, subtitle.height());
        assertEquals(3, subtitle.cues().size());
        BitmapCue first = subtitle.cues().get(0);
        assertEquals(1000, first.startMs());
        assertEquals(3500, first.endMs());
        assertEquals(700, first.x());
        assertEquals(900, first.y());
        assertEquals(400, first.w());
        assertEquals(60, first.h());
        assertFalse(first.forced());
        // a display set replaces the previous picture even without a clear in between
        assertEquals(6000, subtitle.cues().get(1).endMs());
        assertEquals(8000, subtitle.cues().get(2).endMs());
    }

    @Test
    void paletteIsLimitedRangeYCbCr() {
        byte[] sup = new PgsStreamBuilder(1920, 1080)
                .show(0, 0, 0, 4, 2, 235, 128, 128, 200)
                .clear(1000)
                .build();

        int[] argb = PgsParser.parse(sup).cues().getFirst().argb();

        assertEquals(8, argb.length);
        assertTrue(Arrays.stream(argb).allMatch(p -> p == 0xC8FFFFFF), "Y=235 is white, alpha carried over");
    }

    @Test
    void objectSplitOverTwoSegmentsIsReassembled() {
        byte[] sup = new PgsStreamBuilder(1920, 1080)
                .show(0, 10, 20, 300, 40, 235, 128, 128, 255, 0x80, 2)
                .clear(1000)
                .build();

        BitmapCue cue = PgsParser.parse(sup).cues().getFirst();

        assertEquals(300, cue.w());
        assertEquals(40, cue.h());
        assertTrue(Arrays.stream(cue.argb()).allMatch(p -> p == 0xFFFFFFFF));
    }

    @Test
    void fullyTransparentPictureIsNoCue() {
        byte[] sup = new PgsStreamBuilder(1920, 1080)
                .show(0, 10, 20, 30, 40, 235, 128, 128, 0)
                .clear(1000)
                .build();

        assertTrue(PgsParser.parse(sup).cues().isEmpty());
    }

    @Test
    void truncatedStreamKeepsTheCuesBeforeTheCut() {
        byte[] full = new PgsStreamBuilder(1920, 1080)
                .show(1000, 0, 0, 10, 10, 235, 128, 128, 255)
                .clear(2000)
                .show(3000, 0, 0, 10, 10, 235, 128, 128, 255)
                .clear(4000)
                .build();
        byte[] cut = Arrays.copyOf(full, full.length - 20); // loses the last clear

        BitmapSubtitle subtitle = PgsParser.parse(cut);

        assertEquals(2, subtitle.cues().size());
        assertEquals(2000, subtitle.cues().get(0).endMs());
        assertTrue(subtitle.cues().get(1).endMs() > 3000, "a cue the stream never closes still ends");
    }

    @Test
    void garbageYieldsNoCues() {
        assertTrue(PgsParser.parse(new byte[]{1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14}).cues().isEmpty());
        assertTrue(PgsParser.parse(new byte[0]).cues().isEmpty());
    }
}
