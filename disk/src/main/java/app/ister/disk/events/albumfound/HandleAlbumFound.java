package app.ister.disk.events.albumfound;

import app.ister.core.Handle;
import app.ister.core.enums.DirectoryType;
import app.ister.core.enums.EventType;
import app.ister.core.enums.SearchEntityType;
import app.ister.core.entity.DirectoryEntity;
import app.ister.core.eventdata.AlbumFoundData;
import app.ister.core.eventdata.FileScanRequestedData;
import app.ister.core.eventdata.NfoFileFoundData;
import app.ister.core.repository.AlbumRepository;
import app.ister.core.repository.DirectoryRepository;
import app.ister.core.repository.MetadataRepository;
import app.ister.core.repository.OtherPathFileRepository;
import app.ister.core.service.MessageSender;
import app.ister.core.service.NodeService;
import app.ister.core.service.ServerEventService;
import app.ister.disk.scanner.MusicPathObject;
import app.ister.disk.scanner.enums.FileType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static app.ister.core.utils.AfterCommitPublisher.publishAfterCommit;

@Slf4j
@Service
@Transactional
@RequiredArgsConstructor
public class HandleAlbumFound implements Handle<AlbumFoundData> {

    private final AlbumRepository albumRepository;
    private final MetadataRepository metadataRepository;
    private final DirectoryRepository directoryRepository;
    private final OtherPathFileRepository otherPathFileRepository;
    private final MessageSender messageSender;
    private final NodeService nodeService;
    private final ServerEventService serverEventService;

    @Override
    public EventType handles() {
        return EventType.ALBUM_FOUND;
    }

    @RabbitListener(queues = "#{@diskQueueNamingConfig.getAlbumFoundQueue()}")
    @Override
    public void listener(AlbumFoundData data) {
        Handle.super.listener(data);
    }

    @Override
    public void handle(AlbumFoundData data) {
        albumRepository.findById(data.getAlbumId()).ifPresent(album -> {
            metadataRepository.deleteAll(metadataRepository.findByAlbumEntityId(album.getId()));
            // Keep the search index in line with the removed metadata; the NFO re-parse below re-enriches it.
            serverEventService.createSearchIndexEvent(SearchEntityType.ALBUM, album.getId());

            String albumDir = album.getReleaseYear() > 0
                    ? album.getName() + " (" + album.getReleaseYear() + ")"
                    : album.getName();

            var node = nodeService.getOrCreateNodeEntityForThisNode();
            directoryRepository.findByDirectoryTypeAndNodeEntity(DirectoryType.LIBRARY, node).stream()
                    .filter(dir -> dir.getLibraryEntity() != null &&
                            dir.getLibraryEntity().getId().equals(album.getLibraryEntity().getId()))
                    .forEach(dir -> {
                        Path albumPath = Path.of(dir.getPath(), album.getPersonEntity().getName(), albumDir);
                        String nfoPath = albumPath.resolve("album.nfo").toString();
                        // This handler deletes album metadata in its own transaction, so both sends
                        // below must wait for the commit or the consumers race the delete.
                        otherPathFileRepository.findByDirectoryEntityAndPath(dir, nfoPath)
                                .ifPresent(nfo -> publishAfterCommit(() -> messageSender.sendNfoFileFound(
                                        NfoFileFoundData.builder()
                                                .eventType(EventType.NFO_FILE_FOUND)
                                                .directoryEntityUUID(dir.getId())
                                                .path(nfoPath)
                                                .build(),
                                        dir.getName())));
                        rescanLocalAlbumImages(dir, albumPath);
                    });
        });
    }

    /**
     * Re-ingests local artwork (cover.jpg/folder.jpg) after an album analysis: the analysis wiped
     * the album's image rows, and unlike movies/episodes nothing else rescans the directory. The
     * re-emitted {@code FILE_SCAN_REQUESTED} runs {@code ImageScanner}, which dedups on the
     * existing (directory, path) row and relinks the file via the sibling-tracks album lookup.
     */
    private void rescanLocalAlbumImages(DirectoryEntity dir, Path albumPath) {
        if (!Files.isDirectory(albumPath)) {
            return;
        }
        try (var files = Files.list(albumPath)) {
            files.filter(file -> new MusicPathObject(dir.getPath(), file.toString(), false)
                            .getFileType() == FileType.IMAGE)
                    .forEach(file -> {
                        long size;
                        try {
                            size = Files.size(file);
                        } catch (IOException e) {
                            log.warn("Could not read size of {}: {}", file, e.getMessage());
                            return;
                        }
                        publishAfterCommit(() -> messageSender.sendFileScanRequested(
                                FileScanRequestedData.builder()
                                        .eventType(EventType.FILE_SCAN_REQUESTED)
                                        .path(file)
                                        .regularFile(true)
                                        .size(size)
                                        .directoryEntityUUID(dir.getId())
                                        .build(),
                                dir.getName()));
                    });
        } catch (IOException e) {
            log.warn("Could not list album directory {}: {}", albumPath, e.getMessage());
        }
    }
}
