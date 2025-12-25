package app.ister.worker.events.filescanrequested;

import app.ister.core.MessageQueue;
import app.ister.core.entitiy.DirectoryEntity;
import app.ister.core.enums.EventType;
import app.ister.core.eventdata.FileScanRequestedData;
import app.ister.core.repository.DirectoryRepository;
import app.ister.worker.events.Handle;
import app.ister.worker.scanner.scanners.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static app.ister.core.enums.EventType.FILE_SCAN_REQUESTED;

@Slf4j
@Service
@Transactional
public class FileScanRequestedHandle implements Handle<FileScanRequestedData> {
    private final DirectoryRepository directoryRepository;
    private final MediaFileScanner mediaFileScanner;
    private final ImageScanner imageScanner;
    private final NfoScanner nfoScanner;
    private final SubtitleScanner subtitleScanner;

    public FileScanRequestedHandle(DirectoryRepository directoryRepository, MediaFileScanner mediaFileScanner, ImageScanner imageScanner, NfoScanner nfoScanner, SubtitleScanner subtitleScanner) {
        this.directoryRepository = directoryRepository;
        this.mediaFileScanner = mediaFileScanner;
        this.imageScanner = imageScanner;
        this.nfoScanner = nfoScanner;
        this.subtitleScanner = subtitleScanner;
    }

    @RabbitListener(queues = MessageQueue.APP_ISTER_SERVER_FILE_SCAN_REQUESTED)
    @Override
    public void listener(app.ister.core.eventdata.FileScanRequestedData fileScanRequestedData) {
        Handle.super.listener(fileScanRequestedData);
    }

    @Override
    public EventType handles() {
        return FILE_SCAN_REQUESTED;
    }

    @Override
    public Boolean handle(app.ister.core.eventdata.FileScanRequestedData messageData) {
        DirectoryEntity directoryEntity = directoryRepository.findById(messageData.getDirectoryEntityUUID()).orElseThrow();
        for (Scanner scanner : List.of(mediaFileScanner, imageScanner, nfoScanner, subtitleScanner)) {
            if (scanner.analyzable(messageData.getPath(), messageData.getRegularFile(), messageData.getSize())) {
                log.debug("Scanning file: {}, with scanner: {}", messageData.getPath(), scanner);
                scanner.analyze(directoryEntity, messageData.getPath(), messageData.getRegularFile(), messageData.getSize());
            }
        }
        return true;
    }
}
