package app.ister.core.utils;

import app.ister.core.storage.PathStrings;

import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Validates client-supplied paths that end up inside a library directory. Unlike
 * {@link SafeFilename} it has to accept what real media is called (spaces, parentheses, unicode),
 * so it rejects by rule instead of whitelisting a charset: nothing that can leave the directory,
 * nothing the scanner would skip, nothing a filesystem on the other end cannot store.
 */
public final class LibraryPathValidator {

    private static final int MAX_SEGMENT_BYTES = 255;
    private static final Set<String> WINDOWS_RESERVED = Set.of(
            "con", "prn", "aux", "nul",
            "com1", "com2", "com3", "com4", "com5", "com6", "com7", "com8", "com9",
            "lpt1", "lpt2", "lpt3", "lpt4", "lpt5", "lpt6", "lpt7", "lpt8", "lpt9");

    private LibraryPathValidator() {
    }

    /**
     * @return the path NFC-normalized, {@code /}-separated, without leading or trailing separator
     * @throws IllegalArgumentException when the path is empty or any segment is unacceptable
     */
    public static String requireRelative(String relativePath) {
        String normalized = optionalRelative(relativePath);
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("Empty path");
        }
        return normalized;
    }

    /** As {@link #requireRelative}, but {@code null}/blank is allowed and means "the directory root" ({@code ""}). */
    public static String optionalRelative(String relativePath) {
        if (relativePath == null || relativePath.isBlank()) {
            return "";
        }
        if (relativePath.indexOf('\\') >= 0) {
            throw new IllegalArgumentException("Backslash in path: " + relativePath);
        }
        if (relativePath.startsWith("/")) {
            throw new IllegalArgumentException("Absolute path: " + relativePath);
        }
        String trimmed = relativePath.endsWith("/") ? relativePath.substring(0, relativePath.length() - 1) : relativePath;
        List<String> segments = new ArrayList<>();
        for (String segment : trimmed.split("/", -1)) {
            segments.add(requireSegment(segment));
        }
        return String.join("/", segments);
    }

    /**
     * @return one path segment (a file or folder name), NFC-normalized
     * @throws IllegalArgumentException when it could escape the directory, would be skipped by the
     *                                  scanner (leading dot) or cannot be stored portably
     */
    public static String requireSegment(String segment) {
        if (segment == null || segment.isEmpty()) {
            throw new IllegalArgumentException("Empty path segment");
        }
        String name = Normalizer.normalize(segment, Normalizer.Form.NFC);
        if (name.startsWith(".")) {
            // covers "." and "..", and the scanner never descends into a dot-prefixed name
            throw new IllegalArgumentException("Path segment starts with a dot: " + segment);
        }
        // A trailing dot stays legal: "R.E.M." is a real artist folder.
        if (name.endsWith(" ") || name.startsWith(" ")) {
            throw new IllegalArgumentException("Path segment starts or ends with a space: " + segment);
        }
        name.chars().forEach(c -> {
            if (c == '/' || c == '\\' || c < 0x20 || c == 0x7f) {
                throw new IllegalArgumentException("Illegal character in path segment: " + segment);
            }
        });
        if (name.getBytes(StandardCharsets.UTF_8).length > MAX_SEGMENT_BYTES) {
            throw new IllegalArgumentException("Path segment too long: " + segment);
        }
        int dot = name.indexOf('.');
        String stem = (dot < 0 ? name : name.substring(0, dot)).toLowerCase(Locale.ROOT);
        if (WINDOWS_RESERVED.contains(stem)) {
            throw new IllegalArgumentException("Reserved name: " + segment);
        }
        return name;
    }

    /**
     * Joins a validated relative path onto a directory path (local or {@code s3://}) and asserts the
     * result still lies inside it. The containment check cannot fail after {@link #requireRelative},
     * it is here so that a future change to the rules above fails closed.
     */
    public static String resolve(String directoryPath, String relativePath) {
        String target = PathStrings.join(directoryPath, requireRelative(relativePath));
        if (!PathStrings.isUnder(directoryPath, target) || target.equals(directoryPath)) {
            throw new IllegalArgumentException("Path escapes the directory: " + relativePath);
        }
        return target;
    }
}
