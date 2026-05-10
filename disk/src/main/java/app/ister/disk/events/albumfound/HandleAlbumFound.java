package app.ister.disk.events.albumfound;

import app.ister.core.Handle;
import app.ister.core.enums.DirectoryType;
import app.ister.core.enums.EventType;
import app.ister.core.eventdata.AlbumFoundData;
import app.ister.core.eventdata.NfoFileFoundData;
import app.ister.core.repository.AlbumRepository;
import app.ister.core.repository.DirectoryRepository;
import app.ister.core.repository.MetadataRepository;
import app.ister.core.repository.OtherPathFileRepository;
import app.ister.core.service.MessageSender;
import app.ister.core.service.NodeService;
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
    public Boolean handle(AlbumFoundData data) {
        albumRepository.findById(data.getAlbumId()).ifPresent(album -> {
            metadataRepository.deleteAll(album.getMetadataEntities());

            String albumDir = album.getReleaseYear() > 0
                    ? album.getName() + " (" + album.getReleaseYear() + ")"
                    : album.getName();

            var node = nodeService.getOrCreateNodeEntityForThisNode();
            directoryRepository.findByDirectoryTypeAndNodeEntity(DirectoryType.LIBRARY, node).stream()
                    .filter(dir -> dir.getLibraryEntity() != null &&
                            dir.getLibraryEntity().getId().equals(album.getLibraryEntity().getId()))
                    .forEach(dir -> {
                        String nfoPath = java.nio.file.Path.of(dir.getPath(), album.getArtistEntity().getName(), albumDir, "album.nfo").toString();
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
        return true;
    }
}
