package app.ister.disk.events.artistfound;

import app.ister.core.entity.ArtistEntity;
import app.ister.core.entity.DirectoryEntity;
import app.ister.core.entity.LibraryEntity;
import app.ister.core.entity.MetadataEntity;
import app.ister.core.entity.NodeEntity;
import app.ister.core.entity.OtherPathFileEntity;
import app.ister.core.enums.DirectoryType;
import app.ister.core.enums.EventType;
import app.ister.core.enums.LibraryType;
import app.ister.core.eventdata.ArtistFoundData;
import app.ister.core.repository.ArtistRepository;
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
class HandleArtistFoundTest {

    @InjectMocks
    private HandleArtistFound subject;

    @Mock
    private ArtistRepository artistRepository;
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
        assertEquals(EventType.ARTIST_FOUND, subject.handles());
    }

    @Test
    void listenerThrowsOnWrongEventType() {
        ArtistFoundData data = ArtistFoundData.builder()
                .eventType(EventType.MEDIA_FILE_FOUND)
                .build();
        assertThrows(IllegalArgumentException.class, () -> subject.listener(data));
    }

    @Test
    void handleReturnsTrueWhenArtistNotFound() {
        UUID artistId = UUID.randomUUID();
        ArtistFoundData data = ArtistFoundData.builder()
                .eventType(EventType.ARTIST_FOUND)
                .artistId(artistId)
                .build();

        when(artistRepository.findById(artistId)).thenReturn(Optional.empty());

        subject.handle(data);
        verify(metadataRepository, never()).deleteAll(any());
    }

    @Test
    void handleDeletesMetadataAndSendsNfoEventWhenNfoExists() {
        UUID artistId = UUID.randomUUID();
        UUID libraryId = UUID.randomUUID();
        UUID dirId = UUID.randomUUID();

        LibraryEntity library = LibraryEntity.builder()
                .libraryType(LibraryType.MUSIC)
                .name("Music")
                .build();
        ReflectionTestUtils.setField(library, "id", libraryId);

        MetadataEntity meta = MetadataEntity.builder().build();
        ArtistEntity artist = ArtistEntity.builder()
                .libraryEntity(library)
                .name("ArtistName")
                .metadataEntities(List.of(meta))
                .build();
        ReflectionTestUtils.setField(artist, "id", artistId);

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

        when(artistRepository.findById(artistId)).thenReturn(Optional.of(artist));
        when(nodeService.getOrCreateNodeEntityForThisNode()).thenReturn(node);
        when(directoryRepository.findByDirectoryTypeAndNodeEntity(DirectoryType.LIBRARY, node))
                .thenReturn(List.of(dir));
        when(otherPathFileRepository.findByDirectoryEntityAndPath(dir, expectedNfoPath))
                .thenReturn(Optional.of(nfoFile));

        subject.handle(ArtistFoundData.builder()
                .eventType(EventType.ARTIST_FOUND)
                .artistId(artistId)
                .build());

        verify(metadataRepository).deleteAll(List.of(meta));
        verify(messageSender).sendNfoFileFound(any(), anyString());
    }

    @Test
    void handleSkipsWhenNfoNotFound() {
        UUID artistId = UUID.randomUUID();
        UUID libraryId = UUID.randomUUID();

        LibraryEntity library = LibraryEntity.builder()
                .libraryType(LibraryType.MUSIC)
                .name("Music")
                .build();
        ReflectionTestUtils.setField(library, "id", libraryId);

        ArtistEntity artist = ArtistEntity.builder()
                .libraryEntity(library)
                .name("BandName")
                .metadataEntities(List.of())
                .build();
        ReflectionTestUtils.setField(artist, "id", artistId);

        NodeEntity node = NodeEntity.builder().name("node1").url("http://localhost").build();
        DirectoryEntity dir = DirectoryEntity.builder()
                .name("music-dir")
                .path("/music")
                .directoryType(DirectoryType.LIBRARY)
                .libraryEntity(library)
                .nodeEntity(node)
                .build();

        String expectedNfoPath = "/music/BandName/artist.nfo";

        when(artistRepository.findById(artistId)).thenReturn(Optional.of(artist));
        when(nodeService.getOrCreateNodeEntityForThisNode()).thenReturn(node);
        when(directoryRepository.findByDirectoryTypeAndNodeEntity(DirectoryType.LIBRARY, node))
                .thenReturn(List.of(dir));
        when(otherPathFileRepository.findByDirectoryEntityAndPath(dir, expectedNfoPath))
                .thenReturn(Optional.empty());

        subject.handle(ArtistFoundData.builder()
                .eventType(EventType.ARTIST_FOUND)
                .artistId(artistId)
                .build());

        verify(messageSender, never()).sendNfoFileFound(any(), any());
    }

    @Test
    void handleSkipsDirectoriesFromOtherLibraries() {
        UUID artistId = UUID.randomUUID();
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

        ArtistEntity artist = ArtistEntity.builder()
                .libraryEntity(library)
                .name("ArtistName")
                .metadataEntities(List.of())
                .build();
        ReflectionTestUtils.setField(artist, "id", artistId);

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

        when(artistRepository.findById(artistId)).thenReturn(Optional.of(artist));
        when(nodeService.getOrCreateNodeEntityForThisNode()).thenReturn(node);
        when(directoryRepository.findByDirectoryTypeAndNodeEntity(DirectoryType.LIBRARY, node))
                .thenReturn(List.of(wrongLibDir, nullLibDir));

        subject.handle(ArtistFoundData.builder()
                .eventType(EventType.ARTIST_FOUND)
                .artistId(artistId)
                .build());

        verify(otherPathFileRepository, never()).findByDirectoryEntityAndPath(any(), any());
        verify(messageSender, never()).sendNfoFileFound(any(), any());
    }
}
