package app.ister.worker.events.newdirectoriesscanrequested;

import app.ister.core.MessageQueue;
import app.ister.core.entitiy.NodeEntity;
import app.ister.core.enums.DirectoryType;
import app.ister.core.enums.EventType;
import app.ister.core.eventdata.NewDirectoriesScanRequestedData;
import app.ister.core.repository.DirectoryRepository;
import app.ister.core.service.NodeService;
import app.ister.worker.events.Handle;
import app.ister.worker.scanner.LibraryScanner;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;

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

    @RabbitListener(queues = MessageQueue.APP_ISTER_SERVER_NEW_DIRECTORIES_SCAN_REQUESTED)
    @Override
    public void listener(app.ister.core.eventdata.NewDirectoriesScanRequestedData newDirectoriesScanRequestedData) {
        Handle.super.listener(newDirectoriesScanRequestedData);
    }

    @Override
    public Boolean handle(app.ister.core.eventdata.NewDirectoriesScanRequestedData serverEventEntity) {
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
