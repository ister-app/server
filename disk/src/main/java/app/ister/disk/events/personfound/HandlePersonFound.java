package app.ister.disk.events.personfound;

import app.ister.core.Handle;
import app.ister.core.enums.DirectoryType;
import app.ister.core.enums.EventType;
import app.ister.core.eventdata.PersonFoundData;
import app.ister.core.eventdata.NfoFileFoundData;
import app.ister.core.repository.PersonRepository;
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
public class HandlePersonFound implements Handle<PersonFoundData> {

    private final PersonRepository personRepository;
    private final MetadataRepository metadataRepository;
    private final DirectoryRepository directoryRepository;
    private final OtherPathFileRepository otherPathFileRepository;
    private final MessageSender messageSender;
    private final NodeService nodeService;

    @Override
    public EventType handles() {
        return EventType.PERSON_FOUND;
    }

    @RabbitListener(queues = "#{@diskQueueNamingConfig.getPersonFoundQueue()}")
    @Override
    public void listener(PersonFoundData data) {
        Handle.super.listener(data);
    }

    @Override
    public void handle(PersonFoundData data) {
        personRepository.findById(data.getPersonId()).ifPresent(artist -> {
            metadataRepository.deleteAll(artist.getMetadataEntities());

            var node = nodeService.getOrCreateNodeEntityForThisNode();
            directoryRepository.findByDirectoryTypeAndNodeEntity(DirectoryType.LIBRARY, node).stream()
                    .filter(dir -> dir.getLibraryEntity() != null &&
                            dir.getLibraryEntity().getId().equals(artist.getLibraryEntity().getId()))
                    .forEach(dir -> {
                        String nfoPath = java.nio.file.Path.of(dir.getPath(), artist.getName(), "artist.nfo").toString();
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
