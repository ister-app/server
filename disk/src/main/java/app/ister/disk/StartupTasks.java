package app.ister.disk;

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

    @Value("${app.ister.server.cache-dir}")
    private String cacheDir;

    public StartupTasks(NodeService nodeService, AppIsterServerConfig appIsterServerConfig, DirectoryRepository directoryRepository, LibraryRepository libraryRepository) {
        this.nodeService = nodeService;
        this.appIsterServerConfig = appIsterServerConfig;
        this.directoryRepository = directoryRepository;
        this.libraryRepository = libraryRepository;
    }

    /**
     * Create the node, libraries and directories (als the cache directory) entities in the database if they not exist there.
     */
    @Override
    public void onApplicationEvent(ContextRefreshedEvent event) {
        log.debug("Server started with the appIsterServerConfig: {}", appIsterServerConfig);
        NodeEntity nodeEntity = nodeService.updateOrCreateNodeEntityForThisNode();

        appIsterServerConfig.getLibraries().forEach(this::handleLibrariesFromConfig);
        appIsterServerConfig.getDirectories().forEach(directoryConfigClass -> handleDirectoriesFromConfig(directoryConfigClass, nodeEntity));

        createCacheDirectoryIfNotExistForThisNode(nodeEntity);
    }


    private void handleLibrariesFromConfig(LibraryConfigClass libraryConfigClass) {
        if (libraryRepository.findByName(libraryConfigClass.getName()).isEmpty()) {
            try {
                LibraryEntity libraryEntity = LibraryEntity.builder()
                        .libraryType(libraryConfigClass.getType())
                        .name(libraryConfigClass.getName()).build();
                libraryRepository.save(libraryEntity);
            } catch (DataIntegrityViolationException e) {
                log.debug("Library '{}' was already created by another node", libraryConfigClass.getName());
            }
        }
    }

    private void handleDirectoriesFromConfig(DirectoryConfigClass directoryConfigClass, NodeEntity nodeEntity) {
        Optional<DirectoryEntity> directoryEntityOptional = directoryRepository.findByName(directoryConfigClass.getName());
        if (directoryEntityOptional.isPresent()) {
            DirectoryEntity directoryEntity = directoryEntityOptional.get();
            // Check that the directory is used by the correct node
            if (!directoryEntity.getNodeEntity().equals(nodeEntity)) {
                throw new RuntimeException("Directory " + directoryConfigClass.getName() + " name is already used by an other node");
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
