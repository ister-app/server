package app.ister.disk.config;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class DirectoryConfigClass {
    private String name;
    private String path;
    private String library;
    /** Name of an {@code app.ister.s3.connections[n]} entry: makes this an S3 directory (then {@code path} must be empty). */
    private String s3Connection;
    /** Key prefix inside the connection's bucket, without leading/trailing slash; empty = bucket root. */
    private String prefix;

    public boolean isS3() {
        return s3Connection != null && !s3Connection.isBlank();
    }
}
