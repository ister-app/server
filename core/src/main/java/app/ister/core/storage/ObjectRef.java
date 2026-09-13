package app.ister.core.storage;

/**
 * An object address as stored in the database: {@code s3://bucket/key}. Directory roots are
 * {@code s3://bucket/prefix} (no trailing slash); the bucket root is {@code s3://bucket}.
 */
public record ObjectRef(String bucket, String key) {

    public static final String SCHEME = "s3://";

    public static boolean isS3Uri(String path) {
        return path != null && path.startsWith(SCHEME);
    }

    public static ObjectRef parse(String uri) {
        if (!isS3Uri(uri)) {
            throw new IllegalArgumentException("Not an s3:// uri: " + uri);
        }
        String rest = uri.substring(SCHEME.length());
        int slash = rest.indexOf('/');
        if (slash < 0) {
            return new ObjectRef(rest, "");
        }
        return new ObjectRef(rest.substring(0, slash), rest.substring(slash + 1));
    }

    public String uri() {
        return key.isEmpty() ? SCHEME + bucket : SCHEME + bucket + "/" + key;
    }

    /** Last segment of the key, i.e. the file name. */
    public String fileName() {
        return PathStrings.fileName(key);
    }

    @Override
    public String toString() {
        return uri();
    }
}
