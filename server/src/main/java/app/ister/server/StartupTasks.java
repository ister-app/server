package app.ister.server;

import app.ister.server.config.AppIsterServerConfig;
import app.ister.server.config.DirectoryConfigClass;
import app.ister.server.config.LibraryConfigClass;
import app.ister.server.entitiy.DirectoryEntity;
import app.ister.server.entitiy.LibraryEntity;
import app.ister.server.entitiy.NodeEntity;
import app.ister.server.enums.DirectoryType;
import app.ister.server.repository.DirectoryRepository;
import app.ister.server.repository.LibraryRepository;
import app.ister.server.service.NodeService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.ContextRefreshedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.Optional;

@Slf4j
@Component
public class StartupTasks {

    @Autowired
    private NodeService nodeService;

    @Autowired
    private AppIsterServerConfig appIsterServerConfig;

    @Autowired
    private DirectoryRepository directoryRepository;

    @Autowired
    private LibraryRepository libraryRepository;

    @Value("${app.ister.server.cache-dir}")
    private String cacheDir;

    /**
     * Create the node, libraries and directories (als the cache directory) entities in the database if they not exist there.
     */
    @EventListener(ContextRefreshedEvent.class)
    public void contextRefreshedEvent() {
        NodeEntity nodeEntity = nodeService.getOrCreateNodeEntityForThisNode();

        appIsterServerConfig.getLibraries().forEach(this::handleLibrariesFromConfig);
        appIsterServerConfig.getDirectories().forEach(directoryConfigClass -> handleDirectoriesFromConfig(directoryConfigClass, nodeEntity));

        createCacheDirectoryIfNotExistForThisNode(nodeEntity);
    }


    private void handleLibrariesFromConfig(LibraryConfigClass libraryConfigClass) {
        if (libraryRepository.findByName(libraryConfigClass.getName()).isEmpty()) {
            LibraryEntity libraryEntity = LibraryEntity.builder()
                    .libraryType(libraryConfigClass.getType())
                    .name(libraryConfigClass.getName()).build();
            libraryRepository.save(libraryEntity);
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
