package app.ister.disk.scanner;

import app.ister.core.entity.DirectoryEntity;
import app.ister.core.enums.EventType;
import app.ister.core.eventdata.FileScanRequestedData;
import app.ister.core.service.MessageSender;
import app.ister.disk.scanner.scanners.AudioScanner;
import app.ister.disk.scanner.scanners.MediaFileScanner;
import app.ister.disk.scanner.scanners.Scanner;
import lombok.extern.slf4j.Slf4j;

/**
 * The per-file half of a scan: asks each scanner of the library type whether it wants the file,
 * skips what the {@link ScannedCache} already knows, and publishes a {@code FILE_SCAN_REQUESTED}
 * on the directory's queue for the rest. Storage-agnostic; the walkers only produce
 * {@link ScanEntry}s.
 */
@Slf4j
class ScanEntryDispatcher {
    private final DirectoryEntity directoryEntity;
    private final ScannedCache scannedCache;
    private final MessageSender messageSender;
    private final Scanners scanners;

    ScanEntryDispatcher(DirectoryEntity directoryEntity, ScannedCache scannedCache,
                        MessageSender messageSender, Scanners scanners) {
        this.directoryEntity = directoryEntity;
        this.scannedCache = scannedCache;
        this.messageSender = messageSender;
        this.scanners = scanners;
    }

    void dispatch(ScanEntry entry) {
        boolean directoryScoped = Scanners.directoryScoped(Scanners.libraryTypeOf(directoryEntity));
        for (Scanner scanner : scanners.analyzableBy(directoryEntity, entry.path(), entry.regularFile(), entry.size())) {
            if (!alreadyScanned(scanner, directoryScoped, entry.path())) {
                log.debug("Found file: {}, for scanner: {}", entry.path(), scanner);
                messageSender.sendFileScanRequested(FileScanRequestedData.builder()
                        .path(entry.path())
                        .regularFile(entry.regularFile())
                        .size(entry.size())
                        .lastModified(entry.lastModified())
                        .directoryEntityUUID(directoryEntity.getId())
                        .eventType(EventType.FILE_SCAN_REQUESTED)
                        .build(), directoryEntity.getName());
            }
        }
    }

    private boolean alreadyScanned(Scanner scanner, boolean directoryScoped, String path) {
        if (directoryScoped && scanner instanceof AudioScanner) {
            return scannedCache.foundMusicAudioPath(path);
        }
        if (scanner instanceof MediaFileScanner) {
            // Media files re-run analyze on every rescan (cheap when
            // up-to-date) so its backfills can fire; see foundMediaFilePath.
            return scannedCache.foundMediaFilePath(path);
        }
        return scannedCache.foundPath(path);
    }
}
