package app.ister.worker.events.gamefound;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ParamSfoParserTest {

    @Test
    void readsStringsAndIntegers() {
        byte[] sfo = sfo(Map.of("TITLE", "Demon's Souls", "TITLE_ID", "BLUS30443", "PARENTAL_LEVEL", 9L));
        Map<String, Object> entries = ParamSfoParser.parse(sfo);
        assertEquals("Demon's Souls", ParamSfoParser.string(entries, "TITLE").orElseThrow());
        assertEquals("BLUS30443", ParamSfoParser.string(entries, "TITLE_ID").orElseThrow());
        assertEquals(9L, entries.get("PARENTAL_LEVEL"));
    }

    @Test
    void rejectsGarbage() {
        assertThrows(IllegalArgumentException.class, () -> ParamSfoParser.parse(new byte[8]));
        byte[] text = "not a psf file at all".getBytes(StandardCharsets.US_ASCII);
        assertThrows(IllegalArgumentException.class, () -> ParamSfoParser.parse(text));
    }

    /** Builds a PSF file the way the PS3 SDK lays it out: header, index, key table, data table. */
    static byte[] sfo(Map<String, Object> values) {
        Map<String, Object> ordered = new LinkedHashMap<>(values);
        ByteArrayOutputStream keys = new ByteArrayOutputStream();
        ByteArrayOutputStream data = new ByteArrayOutputStream();
        ByteBuffer index = ByteBuffer.allocate(16 * ordered.size()).order(ByteOrder.LITTLE_ENDIAN);
        for (Map.Entry<String, Object> entry : ordered.entrySet()) {
            int keyOffset = keys.size();
            keys.writeBytes(entry.getKey().getBytes(StandardCharsets.US_ASCII));
            keys.write(0);
            int dataOffset = data.size();
            int format;
            int length;
            if (entry.getValue() instanceof Long number) {
                format = 0x0404;
                length = 4;
                data.writeBytes(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(number.intValue()).array());
            } else {
                format = 0x0204;
                byte[] text = entry.getValue().toString().getBytes(StandardCharsets.UTF_8);
                length = text.length + 1;
                data.writeBytes(text);
                data.write(0);
            }
            // Data entries are 4-byte aligned in real files.
            while (data.size() % 4 != 0) {
                data.write(0);
            }
            index.putShort((short) keyOffset).putShort((short) format).putInt(length).putInt(length).putInt(dataOffset);
        }
        int keyTableStart = 0x14 + index.capacity();
        int dataTableStart = keyTableStart + keys.size();
        ByteBuffer header = ByteBuffer.allocate(0x14).order(ByteOrder.LITTLE_ENDIAN);
        header.put((byte) 0).put((byte) 'P').put((byte) 'S').put((byte) 'F');
        header.putInt(0x0101).putInt(keyTableStart).putInt(dataTableStart).putInt(ordered.size());
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(header.array());
        out.writeBytes(index.array());
        out.writeBytes(keys.toByteArray());
        out.writeBytes(data.toByteArray());
        return out.toByteArray();
    }
}
