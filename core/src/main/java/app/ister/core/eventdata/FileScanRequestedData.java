package app.ister.core.eventdata;

import com.fasterxml.jackson.annotation.JsonSetter;
import lombok.AccessLevel;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

import java.net.URI;
import java.time.Instant;
import java.util.UUID;

@EqualsAndHashCode(callSuper = true)
@Getter
@SuperBuilder
@NoArgsConstructor(access = AccessLevel.PUBLIC)
public class FileScanRequestedData extends MessageData {
    /** Absolute local path, or {@code s3://bucket/key} for an S3 directory. */
    private String path;
    private Boolean regularFile;
    private long size;
    /** From the listing/walk; saves a HEAD per object for S3, may be null. */
    private Instant lastModified;
    private UUID directoryEntityUUID;

    /**
     * Older producers serialised a {@link java.nio.file.Path}, which Jackson wrote as a
     * {@code file:///...} URI. Accept that form during a rolling upgrade.
     */
    @JsonSetter("path")
    void setPathTolerant(String value) {
        if (value != null && value.startsWith("file:")) {
            this.path = java.nio.file.Path.of(URI.create(value)).toString();
        } else {
            this.path = value;
        }
    }
}
