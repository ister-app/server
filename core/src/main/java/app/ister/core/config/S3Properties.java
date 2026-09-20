package app.ister.core.config;

import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Named S3 connections ({@code app.ister.s3.connections[n]}). A directory refers to one by name
 * ({@code app.ister.disk.directories[n].s3-connection}); every node that attaches to that
 * directory needs an entry with that name, pointing at the same bucket. Credentials live only
 * here (config/env), never in the database.
 */
@Getter
@Setter
@Configuration
@ConfigurationProperties(prefix = "app.ister.s3")
public class S3Properties {

    private final List<Connection> connections = new ArrayList<>();

    /**
     * Hand ffmpeg a presigned S3 URL instead of the node's own proxy endpoint. Off by default: the
     * proxy keeps S3 unreachable from the transcoding hosts and reuses the node-token auth path.
     */
    private boolean ffmpegDirect = false;

    /** Lifetime of presigned URLs; must exceed the longest transcode pass. */
    private Duration presignTtl = Duration.ofHours(2);

    /** Scratch directory for objects that must be read as a local file (epub/cbz/pdf). */
    private String localCopyDir;

    /** Size cap of that scratch directory; the least recently used copies go first. */
    private long localCopyMaxBytes = 2L * 1024 * 1024 * 1024;

    public Optional<Connection> connection(String name) {
        return connections.stream().filter(c -> c.getName().equals(name)).findFirst();
    }

    @Getter
    @Setter
    @ToString(exclude = {"accessKey", "secretKey"})
    public static class Connection {
        private String name;
        /** Empty = AWS (region-based endpoint); otherwise e.g. {@code http://minio:9000}. */
        private String endpoint;
        private String region = "us-east-1";
        private String bucket;
        /** Path-style addressing ({@code host/bucket/key}); required by most self-hosted S3 servers. */
        private boolean pathStyle = true;
        private String accessKey;
        private String secretKey;

        public boolean isAws() {
            return endpoint == null || endpoint.isBlank();
        }
    }
}
