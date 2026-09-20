package app.ister.transcoder.bitmapsub;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Packs the cues of one subtitle stream into a few PNG sprite sheets plus a
 * JSON index, the form the player's overlay consumes:
 *
 * <pre>{@code
 * {"version":1,"width":1920,"height":1080,
 *  "sheets":["bsub_<id>_00.png", ...],
 *  "cues":[{"s":9593,"e":11094,"x":728,"y":915,"w":465,"h":61,"sheet":0,"sx":0,"sy":0,"forced":false}, ...]}
 * }</pre>
 *
 * {@code s}/{@code e} are milliseconds on the source timeline, {@code x}/{@code y}
 * the position on the {@code width}x{@code height} canvas, {@code sx}/{@code sy}
 * the sprite's corner inside its sheet. Sheets rather than a file per cue: an
 * episode has hundreds of cues, and that many tiny files is what hurts on S3
 * and over HTTP; sheets also stay in display order, so a player only ever
 * needs the one around the playhead.
 */
public final class SpriteSheetWriter {

    static final int FORMAT_VERSION = 1;
    /** Sheet height cap: keeps a sheet a few hundred KB and well inside every GPU's texture limit. */
    static final int MAX_SHEET_HEIGHT = 2048;
    /** Transparent gap between sprites, so scaled sampling never bleeds a neighbour in. */
    static final int GAP = 2;

    private SpriteSheetWriter() {
    }

    /** Where one cue ended up. */
    record Placement(int sheet, int sx, int sy) {
    }

    /**
     * Writes {@code <baseName>_NN.png} and {@code <baseName>.json} into {@code dir}
     * and returns the written files, index last.
     */
    public static List<Path> write(BitmapSubtitle subtitle, Path dir, String baseName) throws IOException {
        Files.createDirectories(dir);
        int sheetWidth = subtitle.cues().stream().mapToInt(BitmapCue::w).max().orElse(0);
        sheetWidth = Math.max(sheetWidth, Math.max(subtitle.width(), 1));

        List<Placement> placements = new ArrayList<>(subtitle.cues().size());
        List<Integer> sheetHeights = pack(subtitle.cues(), sheetWidth, placements);

        List<Path> written = new ArrayList<>();
        List<String> sheetNames = new ArrayList<>();
        for (int sheet = 0; sheet < sheetHeights.size(); sheet++) {
            int height = sheetHeights.get(sheet);
            int[] pixels = new int[sheetWidth * height];
            for (int i = 0; i < placements.size(); i++) {
                Placement p = placements.get(i);
                if (p.sheet() != sheet) {
                    continue;
                }
                BitmapCue cue = subtitle.cues().get(i);
                for (int row = 0; row < cue.h(); row++) {
                    System.arraycopy(cue.argb(), row * cue.w(), pixels, (p.sy() + row) * sheetWidth + p.sx(), cue.w());
                }
            }
            String name = String.format(Locale.ROOT, "%s_%02d.png", baseName, sheet);
            Path file = dir.resolve(name);
            Files.write(file, PngEncoder.encodeRgba(sheetWidth, height, pixels));
            written.add(file);
            sheetNames.add(name);
        }

        Path index = dir.resolve(baseName + ".json");
        Files.writeString(index, indexJson(subtitle, sheetNames, placements), StandardCharsets.UTF_8);
        written.add(index);
        return written;
    }

    /** Shelf packing in display order; returns the height of every sheet. */
    static List<Integer> pack(List<BitmapCue> cues, int sheetWidth, List<Placement> placements) {
        List<Integer> heights = new ArrayList<>();
        int sheet = 0;
        int x = 0;
        int y = 0;
        int shelf = 0;
        for (BitmapCue cue : cues) {
            if (x > 0 && x + cue.w() > sheetWidth) { // next shelf
                x = 0;
                y += shelf + GAP;
                shelf = 0;
            }
            if (y > 0 && y + cue.h() > MAX_SHEET_HEIGHT) { // next sheet
                heights.add(shelf > 0 ? y + shelf : y - GAP);
                sheet++;
                x = 0;
                y = 0;
                shelf = 0;
            }
            placements.add(new Placement(sheet, x, y));
            x += cue.w() + GAP;
            shelf = Math.max(shelf, cue.h());
        }
        if (!cues.isEmpty()) {
            heights.add(y + shelf);
        }
        return heights;
    }

    private static String indexJson(BitmapSubtitle subtitle, List<String> sheetNames, List<Placement> placements) {
        StringBuilder json = new StringBuilder(64 + subtitle.cues().size() * 96);
        json.append("{\"version\":").append(FORMAT_VERSION)
                .append(",\"width\":").append(subtitle.width())
                .append(",\"height\":").append(subtitle.height())
                .append(",\"sheets\":[");
        for (int i = 0; i < sheetNames.size(); i++) {
            json.append(i == 0 ? "\"" : ",\"").append(sheetNames.get(i)).append('"');
        }
        json.append("],\"cues\":[");
        for (int i = 0; i < subtitle.cues().size(); i++) {
            BitmapCue cue = subtitle.cues().get(i);
            Placement p = placements.get(i);
            json.append(i == 0 ? "{" : ",{")
                    .append("\"s\":").append(cue.startMs())
                    .append(",\"e\":").append(cue.endMs())
                    .append(",\"x\":").append(cue.x())
                    .append(",\"y\":").append(cue.y())
                    .append(",\"w\":").append(cue.w())
                    .append(",\"h\":").append(cue.h())
                    .append(",\"sheet\":").append(p.sheet())
                    .append(",\"sx\":").append(p.sx())
                    .append(",\"sy\":").append(p.sy())
                    .append(",\"forced\":").append(cue.forced())
                    .append('}');
        }
        return json.append("]}").toString();
    }
}
