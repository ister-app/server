package app.ister.core.utils;

import java.util.regex.Pattern;

/**
 * Validates filenames that are resolved against a directory on disk. Restricts to a safe
 * charset (no path separators or "..") so a request path variable can never escape the
 * directory it is resolved against.
 */
public final class SafeFilename {

    private static final Pattern SAFE = Pattern.compile("[a-zA-Z0-9._-]+");

    private SafeFilename() {
    }

    /**
     * @return the given filename, unchanged
     * @throws IllegalArgumentException when the filename contains anything outside
     *                                  {@code [a-zA-Z0-9._-]} or a {@code ..} sequence
     */
    public static String require(String filename) {
        if (filename == null || !SAFE.matcher(filename).matches() || filename.contains("..")) {
            throw new IllegalArgumentException("Invalid filename: " + filename);
        }
        return filename;
    }
}
