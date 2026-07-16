package app.ister.disk.http;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Single-range HTTP byte-range plumbing shared by the epub and comic resource controllers.
 */
public final class ByteRanges {

    public record Range(long start, long end) {
        public long length() {
            return end - start + 1;
        }
    }

    private ByteRanges() {
    }

    /** Parses a single bytes range ("bytes=a-b", "bytes=a-", "bytes=-suffix"); null = no/invalid range. */
    public static Range parseRange(String header, long size) {
        if (header == null || !header.startsWith("bytes=") || header.contains(",") || size <= 0) {
            return null;
        }
        String spec = header.substring("bytes=".length()).trim();
        int dash = spec.indexOf('-');
        if (dash < 0) {
            return null;
        }
        try {
            String startPart = spec.substring(0, dash).trim();
            String endPart = spec.substring(dash + 1).trim();
            if (startPart.isEmpty()) {
                long suffix = Long.parseLong(endPart);
                if (suffix <= 0) return null;
                return new Range(Math.max(0, size - suffix), size - 1);
            }
            long start = Long.parseLong(startPart);
            long end = endPart.isEmpty() ? size - 1 : Math.min(Long.parseLong(endPart), size - 1);
            if (start > end || start >= size) return null;
            return new Range(start, end);
        } catch (NumberFormatException _) {
            return null;
        }
    }

    public static void skipFully(InputStream in, long toSkip) throws IOException {
        long remaining = toSkip;
        while (remaining > 0) {
            long skipped = in.skip(remaining);
            if (skipped <= 0) {
                if (in.read() == -1) {
                    return;
                }
                skipped = 1;
            }
            remaining -= skipped;
        }
    }

    public static void copy(InputStream in, OutputStream out, long length) throws IOException {
        byte[] buffer = new byte[8192];
        long remaining = length;
        while (remaining > 0) {
            int read = in.read(buffer, 0, (int) Math.min(buffer.length, remaining));
            if (read == -1) {
                return;
            }
            out.write(buffer, 0, read);
            remaining -= read;
        }
    }
}
