package app.ister.core.storage;

import app.ister.core.entity.DirectoryEntity;
import app.ister.core.entity.NodeEntity;
import app.ister.core.enums.DirectoryType;
import app.ister.core.repository.DirectoryRepository;
import app.ister.core.service.NodeService;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * The {@link LibraryWriteStore} for a library directory, and the check that THIS node is the one
 * to write it: a LOCAL directory is a path on exactly one node, so a write that arrives anywhere
 * else would land on a disk that does not hold the library. Uploads are therefore sent to the
 * directory's serving node; this is where a request that went to the wrong one is turned away.
 */
@Component
public class LibraryWriteStoreResolver {

    private final NodeService nodeService;
    private final ObjectStoreRegistry objectStoreRegistry;
    private final DirectoryRepository directoryRepository;

    public LibraryWriteStoreResolver(NodeService nodeService, ObjectStoreRegistry objectStoreRegistry,
                                     DirectoryRepository directoryRepository) {
        this.nodeService = nodeService;
        this.objectStoreRegistry = objectStoreRegistry;
        this.directoryRepository = directoryRepository;
    }

    /**
     * The node a client talks to for this directory's files: the owner for LOCAL; for S3 every
     * attached node can, and the node the client is already talking to is the natural choice when
     * it is one of them.
     */
    public NodeEntity servingNode(DirectoryEntity directory) {
        if (directory.getNodeEntity() != null) {
            return directory.getNodeEntity();
        }
        NodeEntity self = nodeService.getOrCreateNodeEntityForThisNode();
        List<NodeEntity> attached = directoryRepository.findAttachedNodes(directory.getId());
        return attached.stream().filter(n -> n.getId().equals(self.getId())).findFirst()
                .or(() -> attached.stream().findFirst())
                .orElse(self);
    }

    /** Whether this node can write the directory: it owns it (LOCAL) or has its S3 connection. */
    public boolean canWrite(DirectoryEntity directory) {
        if (directory.isS3()) {
            return objectStoreRegistry.byConnection(directory.getS3Connection()).isPresent();
        }
        return directory.getNodeEntity() != null
                && directory.getNodeEntity().equals(nodeService.getOrCreateNodeEntityForThisNode());
    }

    /**
     * @throws IllegalArgumentException for a cache directory: only libraries take uploads
     * @throws IllegalStateException    when another node has to take this write
     */
    public LibraryWriteStore storeFor(DirectoryEntity directory) {
        if (directory.getDirectoryType() != DirectoryType.LIBRARY) {
            throw new IllegalArgumentException("Directory " + directory.getName() + " is not a library directory");
        }
        if (!canWrite(directory)) {
            throw new IllegalStateException("Directory " + directory.getName()
                    + " is not writable from this node; send the upload to its serving node");
        }
        if (directory.isS3()) {
            return new S3LibraryWriteStore(directory, objectStoreRegistry.forDirectory(directory));
        }
        return new LocalLibraryWriteStore(directory);
    }
}
