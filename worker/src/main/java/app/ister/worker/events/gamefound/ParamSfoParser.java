package app.ister.worker.events.gamefound;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Reads a PlayStation {@code PARAM.SFO} (PSF format): a little-endian header, an index table and
 * two data tables. Only the string and integer entries matter here — {@code TITLE},
 * {@code TITLE_ID}, {@code VERSION}, {@code CATEGORY} — so everything else is kept as its raw type.
 *
 * <pre>
 *   0x00  magic "\0PSF"      0x04  version
 *   0x08  key table start    0x0C  data table start    0x10  entry count
 *   index entry (16 bytes): key offset u16, data format u16, data length u32, data max length u32, data offset u32
 *   formats: 0x0004 utf8 (not null terminated), 0x0204 utf8 null terminated, 0x0404 uint32
 * </pre>
 */
public final class ParamSfoParser {

    private static final int MAGIC = 0x46535000; // "\0PSF" read little-endian
    private static final int FORMAT_UTF8 = 0x0004;
    private static final int FORMAT_UTF8_NUL = 0x0204;
    private static final int FORMAT_INT32 = 0x0404;
    private static final int HEADER_SIZE = 0x14;
    private static final int INDEX_ENTRY_SIZE = 0x10;

    private ParamSfoParser() {
    }

    /** The decoded entries by key (strings as String, integers as Long). */
    public static Map<String, Object> parse(byte[] bytes) {
        if (bytes.length < HEADER_SIZE) {
            throw new IllegalArgumentException("PARAM.SFO too short: " + bytes.length + " bytes");
        }
        ByteBuffer buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        if (buffer.getInt(0) != MAGIC) {
            throw new IllegalArgumentException("Not a PARAM.SFO (bad magic)");
        }
        int keyTableStart = buffer.getInt(0x08);
        int dataTableStart = buffer.getInt(0x0C);
        int entries = buffer.getInt(0x10);
        if (entries < 0 || entries > 4096 || keyTableStart < HEADER_SIZE || dataTableStart < keyTableStart
                || HEADER_SIZE + (long) entries * INDEX_ENTRY_SIZE > bytes.length) {
            throw new IllegalArgumentException("PARAM.SFO header out of bounds");
        }
        Map<String, Object> result = new LinkedHashMap<>();
        for (int i = 0; i < entries; i++) {
            int entry = HEADER_SIZE + i * INDEX_ENTRY_SIZE;
            int keyOffset = Short.toUnsignedInt(buffer.getShort(entry));
            int format = Short.toUnsignedInt(buffer.getShort(entry + 2));
            int dataLength = buffer.getInt(entry + 4);
            int dataOffset = buffer.getInt(entry + 12);
            String key = readKey(bytes, keyTableStart + keyOffset);
            int start = dataTableStart + dataOffset;
            if (key.isEmpty() || start < 0 || dataLength < 0 || start + (long) dataLength > bytes.length) {
                continue; // A damaged entry must not hide the readable ones.
            }
            if (format == FORMAT_INT32 && dataLength >= 4) {
                result.put(key, Integer.toUnsignedLong(buffer.getInt(start)));
            } else if (format == FORMAT_UTF8 || format == FORMAT_UTF8_NUL) {
                result.put(key, readString(bytes, start, dataLength));
            }
            // binary blobs and truncated ints are not needed
        }
        return result;
    }

    public static Optional<String> string(Map<String, Object> entries, String key) {
        return Optional.ofNullable(entries.get(key))
                .filter(String.class::isInstance)
                .map(String.class::cast)
                .map(String::strip)
                .filter(value -> !value.isEmpty());
    }

    private static String readKey(byte[] bytes, int offset) {
        if (offset < 0 || offset >= bytes.length) {
            return "";
        }
        int end = offset;
        while (end < bytes.length && bytes[end] != 0) {
            end++;
        }
        return new String(bytes, offset, end - offset, StandardCharsets.US_ASCII);
    }

    private static String readString(byte[] bytes, int start, int length) {
        int end = start;
        int limit = start + length;
        while (end < limit && bytes[end] != 0) {
            end++;
        }
        return new String(bytes, start, end - start, StandardCharsets.UTF_8);
    }
}
