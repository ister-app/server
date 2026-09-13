package app.ister.core.storage;

/** The {@code sourceUri} recorded on derived rows (metadata, images): {@code file://path} or the {@code s3://} uri itself. */
public final class SourceUris {

    private SourceUris() {
    }

    public static String of(String path) {
        return ObjectRef.isS3Uri(path) ? path : "file://" + path;
    }
}
