package app.ister.core.storage;

import app.ister.core.config.SharedStorageProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * The cluster-shared HLS tmp store when {@code app.ister.server.tmp-s3-connection} is set, else
 * empty — decided at startup from config (no bean condition: the native image bakes those in).
 */
@Slf4j
@Component
public class TmpStoreProvider {

    private final TmpStore shared;

    @Autowired
    public TmpStoreProvider(SharedStorageProperties properties, ObjectStoreRegistry registry) {
        if (!properties.isSharedTmp()) {
            this.shared = null;
            return;
        }
        ObjectStore store = registry.byConnection(properties.getTmpS3Connection())
                .orElseThrow(() -> new IllegalStateException("app.ister.server.tmp-s3-connection refers to S3 connection '"
                        + properties.getTmpS3Connection() + "' which is not configured (app.ister.s3.connections)"));
        String prefix = properties.getTmpS3Prefix() == null ? "" : properties.getTmpS3Prefix().strip().replaceAll("^/+|/+$", "");
        this.shared = new S3TmpStore(store, prefix);
        log.info("HLS transcode output is shared through s3://{}/{}", store.bucket(), prefix);
    }

    /** Test seam. */
    public TmpStoreProvider(TmpStore shared) {
        this.shared = shared;
    }

    public Optional<TmpStore> shared() {
        return Optional.ofNullable(shared);
    }
}
