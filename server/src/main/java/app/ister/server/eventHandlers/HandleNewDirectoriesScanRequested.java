package app.ister.server.eventHandlers;

import app.ister.server.entitiy.NodeEntity;
import app.ister.server.entitiy.ServerEventEntity;
import app.ister.server.enums.DirectoryType;
import app.ister.server.enums.EventType;
import app.ister.server.repository.DirectoryRepository;
import app.ister.server.scanner.LibraryScanner;
import app.ister.server.service.NodeService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;

@Component
public class HandleNewDirectoriesScanRequested implements Handle {
    @Autowired
    private NodeService nodeService;
    @Autowired
    private DirectoryRepository directoryRepository;
    @Autowired
    private LibraryScanner libraryScanner;

    @Override
    public EventType handles() {
        return EventType.NEW_DIRECTORIES_SCAN_REQUEST;
    }

    @Override
    public Boolean handle(ServerEventEntity serverEventEntity) {
        NodeEntity nodeEntity = nodeService.getOrCreateNodeEntityForThisNode();
        directoryRepository.findByDirectoryTypeAndNodeEntity(DirectoryType.LIBRARY, nodeEntity).forEach(directoryEntity -> {
            try {
                libraryScanner.scanDirectory(directoryEntity);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        });
        return true;
    }
}
