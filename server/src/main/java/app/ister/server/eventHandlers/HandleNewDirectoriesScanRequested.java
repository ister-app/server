package app.ister.server.eventHandlers;

import app.ister.server.entitiy.NodeEntity;
import app.ister.server.enums.DirectoryType;
import app.ister.server.enums.EventType;
import app.ister.server.eventHandlers.data.NewDirectoriesScanRequestedData;
import app.ister.server.eventHandlers.data.NfoFileFoundData;
import app.ister.server.repository.DirectoryRepository;
import app.ister.server.scanner.LibraryScanner;
import app.ister.server.service.NodeService;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;

import static app.ister.server.eventHandlers.MessageQueue.APP_ISTER_SERVER_NEW_DIRECTORIES_SCAN_REQUESTED;
import static app.ister.server.eventHandlers.MessageQueue.APP_ISTER_SERVER_NFO_FILE_FOUND;

@Service
@Transactional
public class HandleNewDirectoriesScanRequested implements Handle<NewDirectoriesScanRequestedData> {
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

    @RabbitListener(queues = APP_ISTER_SERVER_NEW_DIRECTORIES_SCAN_REQUESTED)
    @Override
    public void listener(NewDirectoriesScanRequestedData newDirectoriesScanRequestedData) {
        Handle.super.listener(newDirectoriesScanRequestedData);
    }

    @Override
    public Boolean handle(NewDirectoriesScanRequestedData serverEventEntity) {
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
