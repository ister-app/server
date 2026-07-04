package app.ister.disk.events.personfound;

import app.ister.core.entity.PersonEntity;
import app.ister.core.entity.DirectoryEntity;
import app.ister.core.entity.LibraryEntity;
import app.ister.core.entity.MetadataEntity;
import app.ister.core.entity.NodeEntity;
import app.ister.core.entity.OtherPathFileEntity;
import app.ister.core.enums.DirectoryType;
import app.ister.core.enums.EventType;
import app.ister.core.enums.LibraryType;
import app.ister.core.eventdata.PersonFoundData;
import app.ister.core.repository.PersonRepository;
import app.ister.core.repository.DirectoryRepository;
import app.ister.core.repository.MetadataRepository;
import app.ister.core.repository.OtherPathFileRepository;
import app.ister.core.service.MessageSender;
import app.ister.core.service.NodeService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class HandlePersonFoundTest {

    @InjectMocks
    private HandlePersonFound subject;

    @Mock
    private PersonRepository personRepository;
    @Mock
    private MetadataRepository metadataRepository;
    @Mock
    private DirectoryRepository directoryRepository;
    @Mock
    private OtherPathFileRepository otherPathFileRepository;
    @Mock
    private MessageSender messageSender;
    @Mock
    private NodeService nodeService;

    @Test
    void handles() {
        assertEquals(EventType.PERSON_FOUND, subject.handles());
    }

    @Test
    void listenerThrowsOnWrongEventType() {
        PersonFoundData data = PersonFoundData.builder()
                .eventType(EventType.MEDIA_FILE_FOUND)
                .build();
        assertThrows(IllegalArgumentException.class, () -> subject.listener(data));
    }

    @Test
    void handleReturnsTrueWhenArtistNotFound() {
        UUID personId = UUID.randomUUID();
        PersonFoundData data = PersonFoundData.builder()
                .eventType(EventType.PERSON_FOUND)
                .personId(personId)
                .build();

        when(personRepository.findById(personId)).thenReturn(Optional.empty());

        subject.handle(data);
        verify(metadataRepository, never()).deleteAll(any());
    }

    @Test
    void handleDeletesMetadataAndSendsNfoEventWhenNfoExists() {
        UUID personId = UUID.randomUUID();
        UUID libraryId = UUID.randomUUID();
        UUID dirId = UUID.randomUUID();

        LibraryEntity library = LibraryEntity.builder()
                .libraryType(LibraryType.MUSIC)
                .name("Music")
                .build();
        ReflectionTestUtils.setField(library, "id", libraryId);

        MetadataEntity meta = MetadataEntity.builder().build();
        PersonEntity artist = PersonEntity.builder()
                .libraryEntity(library)
                .name("ArtistName")
                .metadataEntities(List.of(meta))
                .build();
        ReflectionTestUtils.setField(artist, "id", personId);

        NodeEntity node = NodeEntity.builder().name("node1").url("http://localhost").build();
        DirectoryEntity dir = DirectoryEntity.builder()
                .name("music-dir")
                .path("/music")
                .directoryType(DirectoryType.LIBRARY)
                .libraryEntity(library)
                .nodeEntity(node)
                .build();
        ReflectionTestUtils.setField(dir, "id", dirId);

        String expectedNfoPath = "/music/ArtistName/artist.nfo";
        OtherPathFileEntity nfoFile = new OtherPathFileEntity();

        when(personRepository.findById(personId)).thenReturn(Optional.of(artist));
        when(nodeService.getOrCreateNodeEntityForThisNode()).thenReturn(node);
        when(directoryRepository.findByDirectoryTypeAndNodeEntity(DirectoryType.LIBRARY, node))
                .thenReturn(List.of(dir));
        when(otherPathFileRepository.findByDirectoryEntityAndPath(dir, expectedNfoPath))
                .thenReturn(Optional.of(nfoFile));

        subject.handle(PersonFoundData.builder()
                .eventType(EventType.PERSON_FOUND)
                .personId(personId)
                .build());

        verify(metadataRepository).deleteAll(List.of(meta));
        verify(messageSender).sendNfoFileFound(any(), anyString());
    }

    @Test
    void handleSkipsWhenNfoNotFound() {
        UUID personId = UUID.randomUUID();
        UUID libraryId = UUID.randomUUID();

        LibraryEntity library = LibraryEntity.builder()
                .libraryType(LibraryType.MUSIC)
                .name("Music")
                .build();
        ReflectionTestUtils.setField(library, "id", libraryId);

        PersonEntity artist = PersonEntity.builder()
                .libraryEntity(library)
                .name("BandName")
                .metadataEntities(List.of())
                .build();
        ReflectionTestUtils.setField(artist, "id", personId);

        NodeEntity node = NodeEntity.builder().name("node1").url("http://localhost").build();
        DirectoryEntity dir = DirectoryEntity.builder()
                .name("music-dir")
                .path("/music")
                .directoryType(DirectoryType.LIBRARY)
                .libraryEntity(library)
                .nodeEntity(node)
                .build();

        String expectedNfoPath = "/music/BandName/artist.nfo";

        when(personRepository.findById(personId)).thenReturn(Optional.of(artist));
        when(nodeService.getOrCreateNodeEntityForThisNode()).thenReturn(node);
        when(directoryRepository.findByDirectoryTypeAndNodeEntity(DirectoryType.LIBRARY, node))
                .thenReturn(List.of(dir));
        when(otherPathFileRepository.findByDirectoryEntityAndPath(dir, expectedNfoPath))
                .thenReturn(Optional.empty());

        subject.handle(PersonFoundData.builder()
                .eventType(EventType.PERSON_FOUND)
                .personId(personId)
                .build());

        verify(messageSender, never()).sendNfoFileFound(any(), any());
    }

    @Test
    void handleSkipsDirectoriesFromOtherLibraries() {
        UUID personId = UUID.randomUUID();
        UUID libraryId = UUID.randomUUID();
        UUID otherLibraryId = UUID.randomUUID();

        LibraryEntity library = LibraryEntity.builder()
                .libraryType(LibraryType.MUSIC)
                .name("Music")
                .build();
        ReflectionTestUtils.setField(library, "id", libraryId);

        LibraryEntity otherLibrary = LibraryEntity.builder()
                .libraryType(LibraryType.MOVIE)
                .name("Movies")
                .build();
        ReflectionTestUtils.setField(otherLibrary, "id", otherLibraryId);

        PersonEntity artist = PersonEntity.builder()
                .libraryEntity(library)
                .name("ArtistName")
                .metadataEntities(List.of())
                .build();
        ReflectionTestUtils.setField(artist, "id", personId);

        NodeEntity node = NodeEntity.builder().name("node1").url("http://localhost").build();
        DirectoryEntity wrongLibDir = DirectoryEntity.builder()
                .name("movies-dir")
                .path("/movies")
                .directoryType(DirectoryType.LIBRARY)
                .libraryEntity(otherLibrary)
                .nodeEntity(node)
                .build();
        DirectoryEntity nullLibDir = DirectoryEntity.builder()
                .name("cache-dir")
                .path("/cache")
                .directoryType(DirectoryType.LIBRARY)
                .libraryEntity(null)
                .nodeEntity(node)
                .build();

        when(personRepository.findById(personId)).thenReturn(Optional.of(artist));
        when(nodeService.getOrCreateNodeEntityForThisNode()).thenReturn(node);
        when(directoryRepository.findByDirectoryTypeAndNodeEntity(DirectoryType.LIBRARY, node))
                .thenReturn(List.of(wrongLibDir, nullLibDir));

        subject.handle(PersonFoundData.builder()
                .eventType(EventType.PERSON_FOUND)
                .personId(personId)
                .build());

        verify(otherPathFileRepository, never()).findByDirectoryEntityAndPath(any(), any());
        verify(messageSender, never()).sendNfoFileFound(any(), any());
    }
}
