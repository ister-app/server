package app.ister.transcoder.bitmapsub;

import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VobSubParserTest {

    private static final String PALETTE =
            "000000, 111111, 222222, f0e010, 444444, 555555, 666666, 777777, "
                    + "888888, 999999, aaaaaa, bbbbbb, cccccc, dddddd, eeeeee, ffffff";

    @Test
    void cueIsCroppedToItsVisiblePixelsAndColouredFromTheDiscPalette() {
        VobSubStreamBuilder builder = new VobSubStreamBuilder("720x576", PALETTE)
                .cue(8880, 100, 400, 120, 60, 20, 200, 1);

        BitmapSubtitle subtitle = VobSubParser.parse(builder.idxLines(), builder.subBytes());

        assertEquals(720, subtitle.width());
        assertEquals(576, subtitle.height());
        assertEquals(1, subtitle.cues().size());
        BitmapCue cue = subtitle.cues().getFirst();
        assertEquals(8880, cue.startMs());
        assertEquals(8880 + 200 * 1024 / 90, cue.endMs());
        assertEquals(120, cue.x());
        assertEquals(420, cue.y());
        assertEquals(80, cue.w());
        assertEquals(20, cue.h());
        assertTrue(Arrays.stream(cue.argb()).allMatch(p -> p == 0xFFF0E010), "both fields, palette entry 3, opaque");
    }

    @Test
    void spuSpreadOverSeveralPacketsIsReassembled() {
        VobSubStreamBuilder builder = new VobSubStreamBuilder("720x480", PALETTE)
                .cue(1000, 0, 0, 120, 60, 20, 100, 3)
                .cue(5000, 10, 10, 100, 50, 16, 100, 1);

        BitmapSubtitle subtitle = VobSubParser.parse(builder.idxLines(), builder.subBytes());

        assertEquals(2, subtitle.cues().size());
        assertEquals(80, subtitle.cues().get(0).w());
        assertEquals(5000, subtitle.cues().get(1).startMs());
        assertEquals(68, subtitle.cues().get(1).w());
    }

    @Test
    void cueWithoutStopCommandEndsWhenTheNextOneStarts() {
        VobSubStreamBuilder builder = new VobSubStreamBuilder("720x576", PALETTE)
                .cue(1000, 0, 0, 120, 60, 20, 0, 1)
                .cue(4000, 0, 0, 120, 60, 20, 0, 1);

        BitmapSubtitle subtitle = VobSubParser.parse(builder.idxLines(), builder.subBytes());

        assertEquals(4000, subtitle.cues().get(0).endMs());
        assertEquals(14000, subtitle.cues().get(1).endMs(), "the last one is capped");
    }

    @Test
    void missingPaletteFallsBackToWhite() {
        VobSubStreamBuilder builder = new VobSubStreamBuilder(null, null)
                .cue(0, 0, 0, 120, 60, 20, 100, 1);

        BitmapSubtitle subtitle = VobSubParser.parse(builder.idxLines(), builder.subBytes());

        assertEquals(720, subtitle.width());
        assertEquals(0xFFFFFFFF, subtitle.cues().getFirst().argb()[0]);
    }

    @Test
    void cuePointingPastTheEndOfTheSubFileIsSkipped() {
        VobSubStreamBuilder builder = new VobSubStreamBuilder("720x576", PALETTE)
                .cue(1000, 0, 0, 120, 60, 20, 100, 1)
                .cue(4000, 0, 0, 120, 60, 20, 100, 1);
        byte[] sub = builder.subBytes();

        BitmapSubtitle subtitle = VobSubParser.parse(builder.idxLines(), Arrays.copyOf(sub, sub.length - 30));

        assertEquals(1, subtitle.cues().size());
        assertEquals(1000, subtitle.cues().getFirst().startMs());
    }
}
