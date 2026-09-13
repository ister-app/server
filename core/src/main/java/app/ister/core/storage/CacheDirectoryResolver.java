package app.ister.core.storage;

import app.ister.core.config.SharedStorageProperties;
import app.ister.core.entity.DirectoryEntity;
import app.ister.core.enums.DirectoryType;
import app.ister.core.repository.DirectoryRepository;
import app.ister.core.service.NodeService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * The cache directory derived files (background stills, covers, extracted subtitles, podcast
 * downloads) are written to, and the {@link CacheStore} that writes them. By default the node's
 * own {@code <node>-cache-directory}; with {@code app.ister.server.cache-s3-connection} set, the
 * cluster-shared S3 cache {@code <cluster>-s3-cache} that every node configured the same way is
 * attached to. Readers never come here: they resolve a row's path through its directory's
 * storage kind, which is also how the rows of a retired node-local cache keep working.
 */
@Component
public class CacheDirectoryResolver {

    private final NodeService nodeService;
    private final DirectoryRepository directoryRepository;
    private final SharedStorageProperties sharedStorage;
    private final ObjectStoreRegistry objectStoreRegistry;
    private final String clusterName;

    public CacheDirectoryResolver(NodeService nodeService, DirectoryRepository directoryRepository,
                                  SharedStorageProperties sharedStorage, ObjectStoreRegistry objectStoreRegistry,
                                  @Value("${app.ister.cluster.name:${app.ister.server.name}}") String clusterName) {
        this.nodeService = nodeService;
        this.directoryRepository = directoryRepository;
        this.sharedStorage = sharedStorage;
        this.objectStoreRegistry = objectStoreRegistry;
        this.clusterName = clusterName;
    }

    /** Name of the cluster-shared S3 cache directory (also its queue suffix). */
    public static String sharedCacheDirName(String clusterName) {
        return clusterName + "-s3-cache";
    }

    public Optional<DirectoryEntity> forThisNodeIfAny() {
        if (sharedStorage.isSharedCache()) {
            return directoryRepository.findByName(sharedCacheDirName(clusterName));
        }
        return directoryRepository
                .findByDirectoryTypeAndNodeEntity(DirectoryType.CACHE, nodeService.getOrCreateNodeEntityForThisNode())
                .stream().findFirst();
    }

    public DirectoryEntity forThisNode() {
        return forThisNodeIfAny().orElseThrow(() -> new IllegalStateException("This node has no cache directory"));
    }

    /** The store writing into this node's cache directory. */
    public CacheStore store() {
        return storeFor(forThisNode());
    }

    public CacheStore storeFor(DirectoryEntity cacheDirectory) {
        if (cacheDirectory.isS3()) {
            return new S3CacheStore(cacheDirectory, objectStoreRegistry.forDirectory(cacheDirectory));
        }
        return new LocalCacheStore(cacheDirectory);
    }
}
