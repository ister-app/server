package app.ister.transcoder;

import app.ister.core.entity.MediaFileStreamEntity;
import app.ister.core.enums.StreamCodecType;
import app.ister.core.utils.Jaffree;
import com.github.kokorin.jaffree.ffmpeg.FFmpeg;
import com.github.kokorin.jaffree.ffmpeg.UrlInput;
import com.github.kokorin.jaffree.ffmpeg.UrlOutput;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HlsBitmapSubtitleServiceTest {

    @TempDir
    Path tempDir;

    private HlsBitmapSubtitleService subject;

    @BeforeEach
    void setUp() {
        Jaffree jaffree = new Jaffree();
        ReflectionTestUtils.setField(jaffree, "dirOfFFmpeg", "/usr/bin");
        subject = new HlsBitmapSubtitleService(jaffree, "/usr/bin/mkvextract");
    }

    private static MediaFileStreamEntity stream(StreamCodecType type, String codec, int index) {
        MediaFileStreamEntity stream = MediaFileStreamEntity.builder()
                .codecType(type).codecName(codec).streamIndex(index).path("/x.mkv").build();
        ReflectionTestUtils.setField(stream, "id", UUID.randomUUID());
        return stream;
    }

    @ParameterizedTest
    @CsvSource({
            "SUBTITLE, hdmv_pgs_subtitle, true",
            "SUBTITLE, dvd_subtitle, true",
            "SUBTITLE, DVD_SUBTITLE, true",
            "SUBTITLE, dvb_subtitle, false",
            "SUBTITLE, subrip, false",
            "EXTERNAL_SUBTITLE, hdmv_pgs_subtitle, false",
    })
    void supportsPgsAndVobSubOnly(StreamCodecType type, String codec, boolean expected) {
        assertEquals(expected, HlsBitmapSubtitleService.isSupported(stream(type, codec, 2)));
    }

    @Test
    void streamIdComesOutOfIndexAndSheetNames() {
        UUID id = UUID.randomUUID();
        assertEquals(id, HlsBitmapSubtitleService.streamIdOf("bsub_" + id + ".json"));
        assertEquals(id, HlsBitmapSubtitleService.streamIdOf("bsub_" + id + "_03.png"));
        assertThrows(IllegalArgumentException.class, () -> HlsBitmapSubtitleService.streamIdOf("bsub_x.json"));
    }

    @Test
    void generatesIndexSheetsAndMarkerFromAPgsStreamInAContainer() throws IOException {
        // A real container with a PGS track, muxed by ffmpeg from a synthetic .sup:
        // one 300x40 picture from 1.0 s to 3.0 s.
        Path sup = Files.write(tempDir.resolve("in.sup"), syntheticSup());
        Path mkv = tempDir.resolve("in.mkv");
        FFmpeg.atPath(Paths.get("/usr/bin"))
                .addInput(UrlInput.fromPath(sup))
                .addOutput(UrlOutput.toPath(mkv).addArguments("-c:s", "copy"))
                .setOverwriteOutput(true)
                .execute();
        MediaFileStreamEntity pgs = stream(StreamCodecType.SUBTITLE, "hdmv_pgs_subtitle", 0);
        Path cacheDir = tempDir.resolve("cache");

        assertFalse(subject.isGenerationCurrent(cacheDir, pgs.getId()));
        List<Path> written = subject.generate(mkv.toString(), List.of(pgs), cacheDir);

        assertEquals(List.of("bsub_" + pgs.getId() + "_00.png", "bsub_" + pgs.getId() + ".json",
                        "bsub_" + pgs.getId() + ".gen"),
                written.stream().map(p -> p.getFileName().toString()).toList());
        assertTrue(subject.isGenerationCurrent(cacheDir, pgs.getId()));
        String json = Files.readString(cacheDir.resolve("bsub_" + pgs.getId() + ".json"));
        assertTrue(json.contains("\"width\":1920,\"height\":1080"), json);
        // ffmpeg rebases to the container's start time, exactly like the transcode passes do, so
        // cue times share the player's timeline. This container holds nothing but the subtitle,
        // so its start is the first cue.
        assertTrue(json.contains("{\"s\":0,\"e\":2000,\"x\":100,\"y\":900,\"w\":300,\"h\":40,"), json);
        try (var files = Files.list(cacheDir)) {
            assertEquals(3, files.count(), "the raw extraction is scratch and must not stay behind");
        }
    }

    @Test
    void nothingToDoWithoutSupportedStreams() throws IOException {
        assertTrue(subject.generate("/does/not/exist.mkv",
                List.of(stream(StreamCodecType.SUBTITLE, "subrip", 2)), tempDir.resolve("cache")).isEmpty());
    }

    /** Minimal PGS: a display set with one solid rectangle, then a clearing display set. */
    private static byte[] syntheticSup() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int w = 300;
        int h = 40;
        ByteBuffer pcs = ByteBuffer.allocate(19);
        pcs.putShort((short) 1920).putShort((short) 1080).put((byte) 0x10).putShort((short) 0)
                .put((byte) 0x80).put((byte) 0).put((byte) 0).put((byte) 1)
                .putShort((short) 0).put((byte) 0).put((byte) 0).putShort((short) 100).putShort((short) 900);
        segment(out, 0x16, 1000, pcs.array());
        ByteBuffer wds = ByteBuffer.allocate(10);
        wds.put((byte) 1).put((byte) 0).putShort((short) 100).putShort((short) 900).putShort((short) w).putShort((short) h);
        segment(out, 0x17, 1000, wds.array());
        segment(out, 0x14, 1000, new byte[]{0, 0, 1, (byte) 235, (byte) 128, (byte) 128, (byte) 255});
        ByteArrayOutputStream rle = new ByteArrayOutputStream();
        for (int row = 0; row < h; row++) {
            rle.writeBytes(new byte[]{0, (byte) (0xC0 | (w >> 8)), (byte) w, 1, 0, 0});
        }
        ByteBuffer ods = ByteBuffer.allocate(11 + rle.size());
        int total = rle.size() + 4;
        ods.putShort((short) 0).put((byte) 0).put((byte) 0xC0)
                .put((byte) (total >> 16)).putShort((short) total).putShort((short) w).putShort((short) h)
                .put(rle.toByteArray());
        segment(out, 0x15, 1000, ods.array());
        segment(out, 0x80, 1000, new byte[0]);

        ByteBuffer clear = ByteBuffer.allocate(11);
        clear.putShort((short) 1920).putShort((short) 1080).put((byte) 0x10).putShort((short) 1)
                .put((byte) 0).put((byte) 0).put((byte) 0).put((byte) 0);
        segment(out, 0x16, 3000, clear.array());
        ByteBuffer wds2 = ByteBuffer.allocate(10);
        wds2.put((byte) 1).put((byte) 0).putShort((short) 100).putShort((short) 900).putShort((short) w).putShort((short) h);
        segment(out, 0x17, 3000, wds2.array());
        segment(out, 0x80, 3000, new byte[0]);
        return out.toByteArray();
    }

    private static void segment(ByteArrayOutputStream out, int type, long ptsMs, byte[] payload) {
        ByteBuffer header = ByteBuffer.allocate(13);
        header.put((byte) 'P').put((byte) 'G').putInt((int) (ptsMs * 90)).putInt(0)
                .put((byte) type).putShort((short) payload.length);
        out.writeBytes(header.array());
        out.writeBytes(payload);
    }
}
