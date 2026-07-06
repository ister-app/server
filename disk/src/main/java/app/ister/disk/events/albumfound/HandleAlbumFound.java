package app.ister.disk.events.albumfound;

import app.ister.core.Handle;
import app.ister.core.enums.DirectoryType;
import app.ister.core.enums.EventType;
import app.ister.core.enums.SearchEntityType;
import app.ister.core.eventdata.AlbumFoundData;
import app.ister.core.eventdata.NfoFileFoundData;
import app.ister.core.repository.AlbumRepository;
import app.ister.core.repository.DirectoryRepository;
import app.ister.core.repository.MetadataRepository;
import app.ister.core.repository.OtherPathFileRepository;
import app.ister.core.service.MessageSender;
import app.ister.core.service.NodeService;
import app.ister.core.service.ServerEventService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
                        String nfoPath = java.nio.file.Path.of(dir.getPath(), album.getPersonEntity().getName(), albumDir, "album.nfo").toString();
                        otherPathFileRepository.findByDirectoryEntityAndPath(dir, nfoPath)
                                .ifPresent(nfo -> messageSender.sendNfoFileFound(
                                        NfoFileFoundData.builder()
                                                .eventType(EventType.NFO_FILE_FOUND)
                                                .directoryEntityUUID(dir.getId())
                                                .path(nfoPath)
                                                .build(),
                                        dir.getName()));
                    });
        });
    }
}
