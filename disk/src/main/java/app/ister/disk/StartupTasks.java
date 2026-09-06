package app.ister.disk;

import app.ister.core.config.DirectoryQueueNames;
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

    @Value("${app.ister.server.cache-dir}")
    private String cacheDir;

    public StartupTasks(NodeService nodeService, AppIsterServerConfig appIsterServerConfig, DirectoryRepository directoryRepository,
                        LibraryRepository libraryRepository, DirectoryQueueNames directoryQueueNames) {
        this.nodeService = nodeService;
        this.appIsterServerConfig = appIsterServerConfig;
        this.directoryRepository = directoryRepository;
        this.libraryRepository = libraryRepository;
        this.directoryQueueNames = directoryQueueNames;
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
        Optional<DirectoryEntity> directoryEntityOptional = directoryRepository.findByName(directoryConfigClass.getName());
        if (directoryEntityOptional.isPresent()) {
            DirectoryEntity directoryEntity = directoryEntityOptional.get();
            // Check that the directory is used by the correct node
            if (!directoryEntity.getNodeEntity().getId().equals(nodeEntity.getId())) {
                throw new IllegalStateException("Directory " + directoryConfigClass.getName() + " name is already used by an other node");
            }
            // Check if the path of the directory is changed and if so change it in the database
            if (!directoryEntity.getPath().equals(directoryConfigClass.getPath())) {
                directoryEntity.setPath(directoryConfigClass.getPath());
                directoryRepository.save(directoryEntity);
            }
        } else {
            LibraryEntity libraryEntity = libraryRepository.findByName(directoryConfigClass.getLibrary()).orElseThrow();
            directoryRepository.save(DirectoryEntity.builder()
                    .name(directoryConfigClass.getName())
                    .nodeEntity(nodeEntity)
                    .libraryEntity(libraryEntity)
                    .path(directoryConfigClass.getPath())
                    .directoryType(DirectoryType.LIBRARY).build());
        }
    }

    private void createCacheDirectoryIfNotExistForThisNode(NodeEntity nodeEntity) {
        if (directoryRepository.findByDirectoryTypeAndNodeEntity(DirectoryType.CACHE, nodeEntity).isEmpty()) {
            directoryRepository.save(DirectoryEntity.builder()
                    .name(nodeEntity.getName() + "-cache-directory")
                    .nodeEntity(nodeEntity)
                    .path(cacheDir)
                    .directoryType(DirectoryType.CACHE).build());
        }
    }
}
