package app.ister.disk.events.newdirectoriesscanrequested;

import app.ister.core.entity.DirectoryEntity;
import app.ister.core.enums.EventType;
import app.ister.core.eventdata.NewDirectoriesScanRequestedData;
import app.ister.core.repository.DirectoryRepository;
import app.ister.core.Handle;
import app.ister.disk.scanner.LibraryScanner;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;

@Slf4j
@Service
@Transactional
public class HandleNewDirectoriesScanRequested implements Handle<NewDirectoriesScanRequestedData> {
    @Autowired
    private DirectoryRepository directoryRepository;
    @Autowired
    private LibraryScanner libraryScanner;

    @Override
    public EventType handles() {
        return EventType.NEW_DIRECTORIES_SCAN_REQUEST;
    }

    @RabbitListener(queues = "#{@diskQueueNamingConfig.getNewDirectoriesScanRequestedQueues()}")
    @Override
    public void listener(NewDirectoriesScanRequestedData newDirectoriesScanRequestedData) {
        Handle.super.listener(newDirectoriesScanRequestedData);
    }

    @Override
    public Boolean handle(NewDirectoriesScanRequestedData messageData) {
        log.debug("handle HandleNewDirectoriesScanRequested: {}", messageData);
        DirectoryEntity directoryEntity = directoryRepository.findById(messageData.getDirectoryEntityUUID()).orElseThrow();
        try {
            libraryScanner.scanDirectory(directoryEntity);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        return true;
    }
}
