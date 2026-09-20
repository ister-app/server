package app.ister.transcoder.bitmapsub;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class BitmapCueTest {

    @Test
    void equalityLooksAtThePixelsNotTheArrayIdentity() {
        BitmapCue a = new BitmapCue(1000, 2000, 10, 20, 2, 1, false, new int[]{0xFFFFFFFF, 0});
        BitmapCue same = new BitmapCue(1000, 2000, 10, 20, 2, 1, false, new int[]{0xFFFFFFFF, 0});
        BitmapCue otherPixels = new BitmapCue(1000, 2000, 10, 20, 2, 1, false, new int[]{0xFF000000, 0});
        BitmapCue otherTime = a.withEnd(3000);

        assertEquals(a, same);
        assertEquals(a.hashCode(), same.hashCode());
        assertNotEquals(a, otherPixels);
        assertNotEquals(a, otherTime);
        assertNotEquals("a cue", a);
    }

    @Test
    void toStringLeavesThePixelsOut() {
        BitmapCue cue = new BitmapCue(1000, 2000, 10, 20, 2, 1, true, new int[]{1, 2});

        assertEquals("BitmapCue[1000-2000 ms, 2x1 @10,20, forced]", cue.toString());
        assertFalse(new BitmapCue(0, 1, 0, 0, 1, 1, false, new int[]{1}).toString().contains("forced"));
    }

    @Test
    void croppedTrimsTransparentMarginsAndMovesTheOrigin() {
        int o = 0xFF112233;
        int[] pixels = {
                0, 0, 0, 0,
                0, o, o, 0,
                0, 0, 0, 0,
        };

        BitmapCue cue = BitmapCue.cropped(0, 1, 100, 200, 4, 3, false, pixels);

        assertEquals(101, cue.x());
        assertEquals(201, cue.y());
        assertEquals(2, cue.w());
        assertEquals(1, cue.h());
        assertArrayEquals(new int[]{o, o}, cue.argb());
        assertNull(BitmapCue.cropped(0, 1, 0, 0, 2, 1, false, new int[]{0, 0}));
    }
}
