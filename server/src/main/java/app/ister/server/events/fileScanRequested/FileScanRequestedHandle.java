package app.ister.server.events.fileScanRequested;

import app.ister.server.entitiy.DirectoryEntity;
import app.ister.server.enums.EventType;
import app.ister.server.events.Handle;
import app.ister.server.events.MessageQueue;
import app.ister.server.repository.DirectoryRepository;
import app.ister.server.scanner.scanners.ImageScanner;
import app.ister.server.scanner.scanners.MediaFileScanner;
import app.ister.server.scanner.scanners.NfoScanner;
import app.ister.server.scanner.scanners.Scanner;
import app.ister.server.scanner.scanners.SubtitleScanner;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static app.ister.server.enums.EventType.FILE_SCAN_REQUESTED;

@Slf4j
@Service
@Transactional
public class FileScanRequestedHandle implements Handle<FileScanRequestedData> {
    @Autowired
    private DirectoryRepository directoryRepository;
    @Autowired
    private MediaFileScanner mediaFileScanner;
    @Autowired
    private ImageScanner imageScanner;
    @Autowired
    private NfoScanner nfoScanner;
    @Autowired
    private SubtitleScanner subtitleScanner;

    @RabbitListener(queues = MessageQueue.APP_ISTER_SERVER_FILE_SCAN_REQUESTED)
    @Override
    public void listener(FileScanRequestedData fileScanRequestedData) {
        Handle.super.listener(fileScanRequestedData);
    }

    @Override
    public EventType handles() {
        return FILE_SCAN_REQUESTED;
    }

    @Override
    public Boolean handle(FileScanRequestedData messageData) {
        DirectoryEntity directoryEntity = directoryRepository.findById(messageData.getDirectoryEntityUUID()).orElseThrow();
        for (Scanner scanner : List.of(mediaFileScanner, imageScanner, nfoScanner, subtitleScanner)) {
            if (scanner.analyzable(messageData.getPath(), messageData.isRegularFile(), messageData.getSize())) {
                log.debug("Scanning file: {}, with scanner: {}", messageData.getPath(), scanner);
                scanner.analyze(directoryEntity, messageData.getPath(), messageData.isRegularFile(), messageData.getSize());
            }
        }
        return true;
    }
}
