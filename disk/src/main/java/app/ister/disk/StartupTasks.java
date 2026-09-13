package app.ister.disk;

import app.ister.core.config.DirectoryQueueNames;
import app.ister.core.config.S3Properties;
import app.ister.core.config.SharedStorageProperties;
import app.ister.core.enums.StorageKind;
import app.ister.core.storage.ObjectRef;
import app.ister.core.entity.DirectoryEntity;
import app.ister.core.entity.LibraryEntity;
import app.ister.core.entity.NodeEntity;
import app.ister.core.enums.DirectoryType;
import app.ister.core.repository.DirectoryRepository;
import app.ister.core.repository.LibraryRepository;
import app.ister.core.service.NodeService;
import app.ister.disk.config.AppIsterServerConfig;
import app.ister.disk.config.DirectoryConfigClass;
import app.ister.disk.config.LibraryConfigClass;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationListener;
import org.springframework.context.event.ContextRefreshedEvent;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;

import java.util.Optional;

@Slf4j
@Component
public class StartupTasks implements ApplicationListener<ContextRefreshedEvent> {

    private final NodeService nodeService;

    private final AppIsterServerConfig appIsterServerConfig;

    private final DirectoryRepository directoryRepository;

    private final LibraryRepository libraryRepository;

    private final DirectoryQueueNames directoryQueueNames;

    private final S3Properties s3Properties;

    private final SharedStorageProperties sharedStorage;

    @Value("${app.ister.server.cache-dir}")
    private String cacheDir;

    public StartupTasks(NodeService nodeService, AppIsterServerConfig appIsterServerConfig, DirectoryRepository directoryRepository,
                        LibraryRepository libraryRepository, DirectoryQueueNames directoryQueueNames,
                        S3Properties s3Properties, SharedStorageProperties sharedStorage) {
        this.nodeService = nodeService;
        this.appIsterServerConfig = appIsterServerConfig;
        this.directoryRepository = directoryRepository;
        this.libraryRepository = libraryRepository;
        this.directoryQueueNames = directoryQueueNames;
        this.s3Properties = s3Properties;
        this.sharedStorage = sharedStorage;
    }

    /**
     * Create the node, libraries and directories (als the cache directory) entities in the database if they not exist there.
     */
    @Override
    public void onApplicationEvent(ContextRefreshedEvent event) {
        // Skip events propagated from child contexts (e.g., Feign client named contexts),
        // since ContextRefreshedEvent bubbles up to parent and would run startup tasks twice.
        if (event.getApplicationContext().getParent() != null) {
            return;
        }
        log.debug("Server started with the appIsterServerConfig: {}", appIsterServerConfig);
        NodeEntity nodeEntity = nodeService.updateOrCreateNodeEntityForThisNode();

        appIsterServerConfig.getLibraries().forEach(this::handleLibrariesFromConfig);
        appIsterServerConfig.getDirectories().forEach(directoryConfigClass -> handleDirectoriesFromConfig(directoryConfigClass, nodeEntity));

        createCacheDirectoryIfNotExistForThisNode(nodeEntity);
        createSharedCacheDirectoryIfConfigured(nodeEntity);
        validateHelperDisks(nodeEntity);
    }

    /**
     * Helper disk names are used verbatim as queue suffixes, so a typo silently produces a queue
     * nobody publishes to. Warn rather than fail: in a compose/cluster start the helper may come
     * up before the owning node has registered its directories.
     */
    private void validateHelperDisks(NodeEntity nodeEntity) {
        for (String name : directoryQueueNames.allHelperDirectoryNames()) {
            Optional<DirectoryEntity> directory = directoryRepository.findByName(name);
            if (directory.isEmpty()) {
                log.warn("Helper disk '{}' is not a known directory in the cluster (typo, or the owning node has not started yet)", name);
            } else if (directory.get().isS3()) {
                log.warn("Helper disk '{}' is an S3 directory; attach to it with app.ister.disk.directories instead of app.ister.helper.disks", name);
            } else if (directory.get().getNodeEntity().getId().equals(nodeEntity.getId())) {
                log.warn("Helper disk '{}' is owned by this node itself; listing it under app.ister.helper.disks has no effect", name);
            }
        }
    }


    private void handleLibrariesFromConfig(LibraryConfigClass libraryConfigClass) {
        if (libraryRepository.findByName(libraryConfigClass.getName()).isEmpty()) {
            try {
                LibraryEntity libraryEntity = LibraryEntity.builder()
                        .libraryType(libraryConfigClass.getType())
                        .name(libraryConfigClass.getName()).build();
                libraryRepository.save(libraryEntity);
            } catch (DataIntegrityViolationException _) {
                log.debug("Library '{}' was already created by another node", libraryConfigClass.getName());
            }
        }
    }

    private void handleDirectoriesFromConfig(DirectoryConfigClass directoryConfigClass, NodeEntity nodeEntity) {
        if (directoryConfigClass.isS3()) {
            handleS3DirectoryFromConfig(directoryConfigClass, nodeEntity);
            return;
        }
        Optional<DirectoryEntity> directoryEntityOptional = directoryRepository.findByName(directoryConfigClass.getName());
        if (directoryEntityOptional.isPresent()) {
            DirectoryEntity directoryEntity = directoryEntityOptional.get();
            if (directoryEntity.isS3()) {
                throw new IllegalStateException("Directory " + directoryConfigClass.getName()
                        + " is an S3 directory (" + directoryEntity.getPath() + ") in the cluster but configured with a local path here");
            }
            // Check that the directory is used by the correct node
            if (!directoryEntity.getNodeEntity().getId().equals(nodeEntity.getId())) {
                throw new IllegalStateException("Directory " + directoryConfigClass.getName() + " name is already used by an other node");
            }
            // Check if the path of the directory is changed and if so change it in the database
            if (!directoryEntity.getPath().equals(directoryConfigClass.getPath())) {
                directoryEntity.setPath(directoryConfigClass.getPath());
                directoryRepository.save(directoryEntity);
            }
            attach(directoryEntity, nodeEntity);
        } else {
            LibraryEntity libraryEntity = libraryRepository.findByName(directoryConfigClass.getLibrary()).orElseThrow();
            DirectoryEntity created = DirectoryEntity.builder()
                    .name(directoryConfigClass.getName())
                    .nodeEntity(nodeEntity)
                    .libraryEntity(libraryEntity)
                    .path(directoryConfigClass.getPath())
                    .directoryType(DirectoryType.LIBRARY).build();
            created.getAttachedNodes().add(nodeEntity);
            directoryRepository.save(created);
        }
    }

    /**
     * An S3 directory has no owner: the first node to start creates it, every node that lists it
     * with the same connection/bucket/prefix attaches to it (and thereby consumes its queues), and
     * a node whose config points the same name at another bucket or prefix refuses to start —
     * two nodes scanning different prefixes into one directory would delete each other's rows.
     */
    private void handleS3DirectoryFromConfig(DirectoryConfigClass config, NodeEntity nodeEntity) {
        if (config.getPath() != null && !config.getPath().isBlank()) {
            throw new IllegalStateException("Directory " + config.getName() + " has both path and s3-connection; an S3 directory takes only s3-connection + prefix");
        }
        S3Properties.Connection connection = s3Properties.connection(config.getS3Connection())
                .orElseThrow(() -> new IllegalStateException("Directory " + config.getName() + " refers to S3 connection '"
                        + config.getS3Connection() + "' which is not configured (app.ister.s3.connections)"));
        String prefix = normalizePrefix(config.getPrefix());
        String uri = new ObjectRef(connection.getBucket(), prefix).uri();

        Optional<DirectoryEntity> existing = directoryRepository.findByName(config.getName());
        if (existing.isEmpty()) {
            LibraryEntity libraryEntity = libraryRepository.findByName(config.getLibrary()).orElseThrow();
            DirectoryEntity created = DirectoryEntity.builder()
                    .name(config.getName())
                    .libraryEntity(libraryEntity)
                    .storageKind(StorageKind.S3)
                    .s3Connection(connection.getName())
                    .s3Bucket(connection.getBucket())
                    .s3Prefix(prefix)
                    .path(uri)
                    .directoryType(DirectoryType.LIBRARY).build();
            created.getAttachedNodes().add(nodeEntity);
            try {
                directoryRepository.save(created);
                log.info("Created S3 directory {} at {}", config.getName(), uri);
                return;
            } catch (DataIntegrityViolationException _) {
                // another node created it in the meantime: fall through to the attach path
                existing = directoryRepository.findByName(config.getName());
            }
        }
        DirectoryEntity directory = existing.orElseThrow();
        if (!directory.isS3()) {
            throw new IllegalStateException("Directory " + config.getName() + " is a local directory of node "
                    + directory.getNodeEntity().getName() + " but configured as S3 here");
        }
        if (!uri.equals(directory.getPath()) || !connection.getName().equals(directory.getS3Connection())) {
            throw new IllegalStateException("Directory " + config.getName() + " is " + directory.getPath()
                    + " (connection " + directory.getS3Connection() + ") in the cluster, but this node configured "
                    + uri + " (connection " + connection.getName() + ")");
        }
        attach(directory, nodeEntity);
    }

    private void attach(DirectoryEntity directory, NodeEntity nodeEntity) {
        boolean attached = directoryRepository.findAttachedNodes(directory.getId()).stream()
                .anyMatch(n -> java.util.Objects.equals(n.getId(), nodeEntity.getId())
                        || java.util.Objects.equals(n.getName(), nodeEntity.getName()));
        if (!attached) {
            directory.getAttachedNodes().add(nodeEntity);
            directoryRepository.save(directory);
            log.info("Node {} attached to directory {}", nodeEntity.getName(), directory.getName());
        }
    }

    /**
     * The cluster-shared S3 cache ({@code app.ister.server.cache-s3-connection}): one CACHE
     * directory named {@code <cluster>-s3-cache}, created by the first node and attached to by
     * every node that configures it. The node-local cache directory stays: its rows are still
     * served from there, only new derived files go to the shared one.
     */
    private void createSharedCacheDirectoryIfConfigured(NodeEntity nodeEntity) {
        if (!sharedStorage.isSharedCache()) {
            return;
        }
        S3Properties.Connection connection = s3Properties.connection(sharedStorage.getCacheS3Connection())
                .orElseThrow(() -> new IllegalStateException("app.ister.server.cache-s3-connection refers to S3 connection '"
                        + sharedStorage.getCacheS3Connection() + "' which is not configured (app.ister.s3.connections)"));
        String prefix = normalizePrefix(sharedStorage.getCacheS3Prefix());
        String uri = new ObjectRef(connection.getBucket(), prefix).uri();
        String name = directoryQueueNames.cacheDirName();
        Optional<DirectoryEntity> existing = directoryRepository.findByName(name);
        if (existing.isEmpty()) {
            DirectoryEntity created = DirectoryEntity.builder()
                    .name(name)
                    .storageKind(StorageKind.S3)
                    .s3Connection(connection.getName())
                    .s3Bucket(connection.getBucket())
                    .s3Prefix(prefix)
                    .path(uri)
                    .directoryType(DirectoryType.CACHE).build();
            created.getAttachedNodes().add(nodeEntity);
            try {
                directoryRepository.save(created);
                log.info("Created shared S3 cache directory {} at {}", name, uri);
                return;
            } catch (DataIntegrityViolationException _) {
                existing = directoryRepository.findByName(name);
            }
        }
        DirectoryEntity directory = existing.orElseThrow();
        if (!directory.isS3() || !uri.equals(directory.getPath()) || !connection.getName().equals(directory.getS3Connection())) {
            throw new IllegalStateException("Shared cache directory " + name + " is " + directory.getPath()
                    + " (connection " + directory.getS3Connection() + ") in the cluster, but this node configured "
                    + uri + " (connection " + connection.getName() + ")");
        }
        attach(directory, nodeEntity);
    }

    static String normalizePrefix(String prefix) {
        if (prefix == null) {
            return "";
        }
        String p = prefix.strip();
        while (p.startsWith("/")) {
            p = p.substring(1);
        }
        while (p.endsWith("/")) {
            p = p.substring(0, p.length() - 1);
        }
        return p;
    }

    private void createCacheDirectoryIfNotExistForThisNode(NodeEntity nodeEntity) {
        Optional<DirectoryEntity> cache = directoryRepository.findByDirectoryTypeAndNodeEntity(DirectoryType.CACHE, nodeEntity)
                .stream().findFirst();
        if (cache.isEmpty()) {
            DirectoryEntity created = DirectoryEntity.builder()
                    .name(nodeEntity.getName() + "-cache-directory")
                    .nodeEntity(nodeEntity)
                    .path(cacheDir)
                    .directoryType(DirectoryType.CACHE).build();
            created.getAttachedNodes().add(nodeEntity);
            directoryRepository.save(created);
        } else {
            attach(cache.get(), nodeEntity);
        }
    }
}
