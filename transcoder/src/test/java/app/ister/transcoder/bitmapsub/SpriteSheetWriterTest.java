package app.ister.transcoder.bitmapsub;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SpriteSheetWriterTest {

    @TempDir
    Path dir;

    private static BitmapCue cue(long start, int w, int h, int color) {
        int[] argb = new int[w * h];
        Arrays.fill(argb, color);
        return new BitmapCue(start, start + 1000, 10, 20, w, h, false, argb);
    }

    @Test
    void writesSheetsThatDecodeBackToTheCuePixels() throws IOException {
        BitmapSubtitle subtitle = new BitmapSubtitle(200, 100,
                List.of(cue(0, 120, 30, 0xFFFF0000), cue(2000, 120, 40, 0x8000FF00), cue(4000, 50, 10, 0xFF0000FF)));

        List<Path> written = SpriteSheetWriter.write(subtitle, dir, "bsub_x");

        assertEquals(List.of("bsub_x_00.png", "bsub_x.json"), written.stream().map(p -> p.getFileName().toString()).toList());
        // ImageIO only here, to prove the hand-written PNG is one other decoders accept
        BufferedImage sheet = ImageIO.read(written.getFirst().toFile());
        assertEquals(200, sheet.getWidth());
        assertEquals(0xFFFF0000, sheet.getRGB(0, 0));
        assertEquals(0, sheet.getRGB(121, 0) >>> 24, "gap between sprites stays transparent");
        // the second cue does not fit next to the first: it starts a new shelf
        assertEquals(0x8000FF00, sheet.getRGB(0, 30 + SpriteSheetWriter.GAP));
        assertEquals(0xFF0000FF, sheet.getRGB(120 + SpriteSheetWriter.GAP, 30 + SpriteSheetWriter.GAP));

        String json = Files.readString(written.getLast());
        assertTrue(json.startsWith("{\"version\":1,\"width\":200,\"height\":100,\"sheets\":[\"bsub_x_00.png\"],\"cues\":["), json);
        assertTrue(json.contains("{\"s\":2000,\"e\":3000,\"x\":10,\"y\":20,\"w\":120,\"h\":40,\"sheet\":0,\"sx\":0,\"sy\":32,\"forced\":false}"), json);
    }

    @Test
    void startsANewSheetWhenTheHeightCapIsReached() {
        List<BitmapCue> cues = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            cues.add(cue(i * 1000L, 150, 100, 0xFFFFFFFF));
        }
        List<SpriteSheetWriter.Placement> placements = new ArrayList<>();

        List<Integer> heights = SpriteSheetWriter.pack(cues, 200, placements);

        assertEquals(2, heights.size());
        assertTrue(heights.stream().allMatch(h -> h <= SpriteSheetWriter.MAX_SHEET_HEIGHT));
        for (int i = 0; i < cues.size(); i++) {
            SpriteSheetWriter.Placement p = placements.get(i);
            assertTrue(p.sy() + 100 <= heights.get(p.sheet()), "cue " + i + " lies inside its sheet");
        }
        assertEquals(0, placements.get(20).sy(), "first cue of the second sheet starts at the top");
    }

    @Test
    void streamWithoutCuesStillGetsAnIndex() throws IOException {
        List<Path> written = SpriteSheetWriter.write(new BitmapSubtitle(720, 576, List.of()), dir, "bsub_empty");

        assertEquals(1, written.size());
        assertEquals("{\"version\":1,\"width\":720,\"height\":576,\"sheets\":[],\"cues\":[]}",
                Files.readString(written.getFirst()));
    }
}
