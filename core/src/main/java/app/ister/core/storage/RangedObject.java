package app.ister.core.storage;

import java.io.InputStream;

/**
 * A (partial) object body. {@code start}/{@code end} are the inclusive byte offsets actually
 * served, {@code totalSize} the object's full length — enough to build a {@code Content-Range}.
 * The caller owns the stream.
 */
public record RangedObject(InputStream body, long start, long end, long totalSize, String etag, String contentType)
        implements AutoCloseable {

    public long length() {
        return end - start + 1;
    }

    public boolean partial() {
        return start != 0 || end != totalSize - 1;
    }

    @Override
    public void close() throws Exception {
        body.close();
    }
}
