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
import app.ister.core.storage.ObjectRef;
import app.ister.core.storage.ObjectStore;
import app.ister.core.storage.ObjectStoreRegistry;
import app.ister.core.storage.PathStrings;
import app.ister.disk.scanner.MusicPathObject;
import app.ister.disk.scanner.ScanEntry;
import app.ister.disk.scanner.enums.FileType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.util.Objects;
import java.util.List;
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
    private final ObjectStoreRegistry objectStoreRegistry;

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
            directoryRepository.findAttachedTo(node, DirectoryType.LIBRARY).stream()
                    .filter(dir -> dir.getLibraryEntity() != null &&
                            dir.getLibraryEntity().getId().equals(album.getLibraryEntity().getId()))
                    .forEach(dir -> {
                        String albumPath = PathStrings.join(PathStrings.join(dir.getPath(), album.getPersonEntity().getName()), albumDir);
                        String nfoPath = PathStrings.join(albumPath, "album.nfo");
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
    private void rescanLocalAlbumImages(DirectoryEntity dir, String albumPath) {
        for (ScanEntry file : listAlbumFiles(dir, albumPath)) {
            if (new MusicPathObject(dir.getPath(), file.path(), false).getFileType() != FileType.IMAGE) {
                continue;
            }
            publishAfterCommit(() -> messageSender.sendFileScanRequested(
                    FileScanRequestedData.builder()
                            .eventType(EventType.FILE_SCAN_REQUESTED)
                            .path(file.path())
                            .regularFile(true)
                            .size(file.size())
                            .lastModified(file.lastModified())
                            .directoryEntityUUID(dir.getId())
                            .build(),
                    dir.getName()));
        }
    }

    private List<ScanEntry> listAlbumFiles(DirectoryEntity dir, String albumPath) {
        if (dir.isS3()) {
            try {
                ObjectStore store = objectStoreRegistry.forDirectory(dir);
                return store.listShallow(ObjectRef.parse(albumPath).key() + "/").objects().stream()
                        .map(o -> new ScanEntry(store.uri(o.key()), true, o.size(), o.lastModified()))
                        .toList();
            } catch (RuntimeException e) {
                log.warn("Could not list album prefix {}: {}", albumPath, e.getMessage());
                return List.of();
            }
        }
        Path local = Path.of(albumPath);
        if (!Files.isDirectory(local)) {
            return List.of();
        }
        try (var files = Files.list(local)) {
            return files.map(file -> {
                try {
                    return new ScanEntry(file.toString(), Files.isRegularFile(file), Files.size(file),
                            Files.getLastModifiedTime(file).toInstant());
                } catch (IOException e) {
                    log.warn("Could not read {}: {}", file, e.getMessage());
                    return null;
                }
            }).filter(Objects::nonNull).toList();
        } catch (IOException e) {
            log.warn("Could not list album directory {}: {}", albumPath, e.getMessage());
            return List.of();
        }
    }
}
