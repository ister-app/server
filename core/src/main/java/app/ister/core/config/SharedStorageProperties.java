package app.ister.core.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Optional cluster-shared cache and HLS tmp storage on S3 ({@code app.ister.server.cache-s3-*},
 * {@code app.ister.server.tmp-s3-*}). When a connection name is set, that store replaces the
 * node-local directory for <em>everything</em> on this node — derived files of local-disk
 * libraries included — so every node that configures the same connection serves the same cache
 * and the same transcoded segments. Unset = the node-local directories, exactly as before.
 */
@Getter
@Setter
@Configuration
@ConfigurationProperties(prefix = "app.ister.server")
public class SharedStorageProperties {

    private String cacheS3Connection;
    private String cacheS3Prefix = "cache";
    private String tmpS3Connection;
    private String tmpS3Prefix = "tmp";

    public boolean isSharedCache() {
        return cacheS3Connection != null && !cacheS3Connection.isBlank();
    }

    public boolean isSharedTmp() {
        return tmpS3Connection != null && !tmpS3Connection.isBlank();
    }
}
