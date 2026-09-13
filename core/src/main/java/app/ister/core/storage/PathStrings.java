package app.ister.core.storage;

/**
 * Path helpers that work on both local paths and {@code s3://} uris. Both use {@code /} as the
 * separator, so the scanners and path parsers can stay string-based instead of needing a
 * {@link java.nio.file.Path}, which cannot represent an object key.
 */
public final class PathStrings {

    private PathStrings() {
    }

    /** The last path segment ({@code /a/b/c.mkv} → {@code c.mkv}); the input itself when it has no separator. */
    public static String fileName(String path) {
        if (path == null) {
            return null;
        }
        String trimmed = path.endsWith("/") ? path.substring(0, path.length() - 1) : path;
        int idx = trimmed.lastIndexOf('/');
        return idx < 0 ? trimmed : trimmed.substring(idx + 1);
    }

    /** The parent ({@code /a/b/c.mkv} → {@code /a/b}); {@code null} when there is none. */
    public static String parent(String path) {
        if (path == null) {
            return null;
        }
        String trimmed = path.endsWith("/") ? path.substring(0, path.length() - 1) : path;
        int idx = trimmed.lastIndexOf('/');
        if (idx < 0) {
            return null;
        }
        // keep the scheme+bucket for s3://bucket/key and the root slash for /file
        if (idx == 0) {
            return "/";
        }
        String parent = trimmed.substring(0, idx);
        return parent.endsWith(":/") ? null : parent;
    }

    /** {@code base + "/" + child} without doubling the separator. */
    public static String join(String base, String child) {
        if (base == null || base.isEmpty()) {
            return child;
        }
        if (child == null || child.isEmpty()) {
            return base;
        }
        boolean baseSlash = base.endsWith("/");
        boolean childSlash = child.startsWith("/");
        if (baseSlash && childSlash) {
            return base + child.substring(1);
        }
        if (baseSlash || childSlash) {
            return base + child;
        }
        return base + "/" + child;
    }

    /** Whether {@code path} lies under {@code root} (or equals it). */
    public static boolean isUnder(String root, String path) {
        if (root == null || path == null) {
            return false;
        }
        String r = root.endsWith("/") ? root : root + "/";
        return path.equals(root) || path.startsWith(r);
    }
}
