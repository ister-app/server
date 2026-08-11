package app.ister.disk.events.albumfound;

import app.ister.core.entity.AlbumEntity;
import app.ister.core.entity.PersonEntity;
import app.ister.core.entity.DirectoryEntity;
import app.ister.core.entity.LibraryEntity;
import app.ister.core.entity.MetadataEntity;
import app.ister.core.entity.NodeEntity;
import app.ister.core.entity.OtherPathFileEntity;
import app.ister.core.enums.DirectoryType;
import app.ister.core.enums.EventType;
import app.ister.core.enums.LibraryType;
import app.ister.core.eventdata.AlbumFoundData;
import app.ister.core.repository.AlbumRepository;
import app.ister.core.repository.DirectoryRepository;
import app.ister.core.repository.MetadataRepository;
import app.ister.core.repository.OtherPathFileRepository;
import app.ister.core.service.MessageSender;
import app.ister.core.service.NodeService;
import app.ister.core.service.ServerEventService;
import app.ister.core.eventdata.FileScanRequestedData;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class HandleAlbumFoundTest {

    @Mock
    private ServerEventService serverEventServiceMock;

    @InjectMocks
    private HandleAlbumFound subject;

    @Mock
    private AlbumRepository albumRepository;
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
        assertEquals(EventType.ALBUM_FOUND, subject.handles());
    }

    @Test
    void listenerThrowsOnWrongEventType() {
        AlbumFoundData data = AlbumFoundData.builder()
                .eventType(EventType.MEDIA_FILE_FOUND)
                .build();
        assertThrows(IllegalArgumentException.class, () -> subject.listener(data));
    }

    @Test
    void handleReturnsTrueWhenAlbumNotFound() {
        UUID albumId = UUID.randomUUID();
        AlbumFoundData data = AlbumFoundData.builder()
                .eventType(EventType.ALBUM_FOUND)
                .albumId(albumId)
                .build();

        when(albumRepository.findById(albumId)).thenReturn(Optional.empty());

        subject.handle(data);
        verify(metadataRepository, never()).deleteAll(any());
    }

    @Test
    void handleDeletesMetadataAndSendsNfoEventWhenNfoExists() {
        UUID albumId = UUID.randomUUID();
        UUID libraryId = UUID.randomUUID();
        UUID dirId = UUID.randomUUID();

        LibraryEntity library = LibraryEntity.builder()
                .libraryType(LibraryType.MUSIC)
                .name("Music")
                .build();
        ReflectionTestUtils.setField(library, "id", libraryId);

        PersonEntity artist = PersonEntity.builder()
                .libraryEntity(library)
                .name("ArtistName")
                .build();

        MetadataEntity meta = MetadataEntity.builder().build();
        AlbumEntity album = AlbumEntity.builder()
                .libraryEntity(library)
                .personEntity(artist)
                .name("AlbumName")
                .releaseYear(2024)
                .metadataEntities(List.of(meta))
                .build();
        ReflectionTestUtils.setField(album, "id", albumId);

        NodeEntity node = NodeEntity.builder().name("node1").url("http://localhost").build();
        DirectoryEntity dir = DirectoryEntity.builder()
                .name("music-dir")
                .path("/music")
                .directoryType(DirectoryType.LIBRARY)
                .libraryEntity(library)
                .nodeEntity(node)
                .build();
        ReflectionTestUtils.setField(dir, "id", dirId);

        String expectedNfoPath = "/music/ArtistName/AlbumName (2024)/album.nfo";
        OtherPathFileEntity nfoFile = new OtherPathFileEntity();

        when(albumRepository.findById(albumId)).thenReturn(Optional.of(album));
        when(metadataRepository.findByAlbumEntityId(albumId)).thenReturn(List.of(meta));
        when(nodeService.getOrCreateNodeEntityForThisNode()).thenReturn(node);
        when(directoryRepository.findByDirectoryTypeAndNodeEntity(DirectoryType.LIBRARY, node))
                .thenReturn(List.of(dir));
        when(otherPathFileRepository.findByDirectoryEntityAndPath(dir, expectedNfoPath))
                .thenReturn(Optional.of(nfoFile));

        subject.handle(AlbumFoundData.builder()
                .eventType(EventType.ALBUM_FOUND)
                .albumId(albumId)
                .build());

        verify(metadataRepository).deleteAll(List.of(meta));
        verify(messageSender).sendNfoFileFound(any(), anyString());
    }

    @Test
    void handleAlbumWithNoReleaseYearUsesNameOnly() {
        UUID albumId = UUID.randomUUID();
        UUID libraryId = UUID.randomUUID();

        LibraryEntity library = LibraryEntity.builder()
                .libraryType(LibraryType.MUSIC)
                .name("Music")
                .build();
        ReflectionTestUtils.setField(library, "id", libraryId);

        PersonEntity artist = PersonEntity.builder()
                .libraryEntity(library)
                .name("ArtistName")
                .build();

        AlbumEntity album = AlbumEntity.builder()
                .libraryEntity(library)
                .personEntity(artist)
                .name("AlbumName")
                .releaseYear(0)
                .metadataEntities(List.of())
                .build();
        ReflectionTestUtils.setField(album, "id", albumId);

        NodeEntity node = NodeEntity.builder().name("node1").url("http://localhost").build();
        DirectoryEntity dir = DirectoryEntity.builder()
                .name("music-dir")
                .path("/music")
                .directoryType(DirectoryType.LIBRARY)
                .libraryEntity(library)
                .nodeEntity(node)
                .build();

        String expectedNfoPath = "/music/ArtistName/AlbumName/album.nfo";

        when(albumRepository.findById(albumId)).thenReturn(Optional.of(album));
        when(nodeService.getOrCreateNodeEntityForThisNode()).thenReturn(node);
        when(directoryRepository.findByDirectoryTypeAndNodeEntity(DirectoryType.LIBRARY, node))
                .thenReturn(List.of(dir));
        when(otherPathFileRepository.findByDirectoryEntityAndPath(dir, expectedNfoPath))
                .thenReturn(Optional.empty());

        subject.handle(AlbumFoundData.builder()
                .eventType(EventType.ALBUM_FOUND)
                .albumId(albumId)
                .build());

        verify(messageSender, never()).sendNfoFileFound(any(), any());
    }

    @Test
    void handleSendsFileScanRequestedForLocalCoverImages(@TempDir Path tempDir) throws IOException {
        UUID albumId = UUID.randomUUID();
        UUID dirId = UUID.randomUUID();

        LibraryEntity library = LibraryEntity.builder()
                .libraryType(LibraryType.MUSIC)
                .name("Music")
                .build();
        ReflectionTestUtils.setField(library, "id", UUID.randomUUID());

        PersonEntity artist = PersonEntity.builder()
                .libraryEntity(library)
                .name("ArtistName")
                .build();

        AlbumEntity album = AlbumEntity.builder()
                .libraryEntity(library)
                .personEntity(artist)
                .name("AlbumName")
                .releaseYear(2024)
                .metadataEntities(List.of())
                .build();
        ReflectionTestUtils.setField(album, "id", albumId);

        NodeEntity node = NodeEntity.builder().name("node1").url("http://localhost").build();
        DirectoryEntity dir = DirectoryEntity.builder()
                .name("music-dir")
                .path(tempDir.toString())
                .directoryType(DirectoryType.LIBRARY)
                .libraryEntity(library)
                .nodeEntity(node)
                .build();
        ReflectionTestUtils.setField(dir, "id", dirId);

        Path albumPath = tempDir.resolve("ArtistName").resolve("AlbumName (2024)");
        Files.createDirectories(albumPath);
        Path cover = albumPath.resolve("cover.jpg");
        Files.write(cover, new byte[]{1, 2, 3});
        Files.writeString(albumPath.resolve("01 - Track.mp3"), "not an image");
        Files.writeString(albumPath.resolve("album.nfo"), "<album/>");

        when(albumRepository.findById(albumId)).thenReturn(Optional.of(album));
        when(nodeService.getOrCreateNodeEntityForThisNode()).thenReturn(node);
        when(directoryRepository.findByDirectoryTypeAndNodeEntity(DirectoryType.LIBRARY, node))
                .thenReturn(List.of(dir));
        when(otherPathFileRepository.findByDirectoryEntityAndPath(any(), any())).thenReturn(Optional.empty());

        subject.handle(AlbumFoundData.builder()
                .eventType(EventType.ALBUM_FOUND)
                .albumId(albumId)
                .build());

        ArgumentCaptor<FileScanRequestedData> captor = ArgumentCaptor.forClass(FileScanRequestedData.class);
        verify(messageSender).sendFileScanRequested(captor.capture(), eq("music-dir"));
        assertEquals(cover, captor.getValue().getPath());
        assertTrue(captor.getValue().getRegularFile());
        assertEquals(3, captor.getValue().getSize());
        assertEquals(dirId, captor.getValue().getDirectoryEntityUUID());
    }

    @Test
    void handleSendsNoFileScanRequestedWithoutLocalImages(@TempDir Path tempDir) throws IOException {
        UUID albumId = UUID.randomUUID();

        LibraryEntity library = LibraryEntity.builder()
                .libraryType(LibraryType.MUSIC)
                .name("Music")
                .build();
        ReflectionTestUtils.setField(library, "id", UUID.randomUUID());

        PersonEntity artist = PersonEntity.builder()
                .libraryEntity(library)
                .name("ArtistName")
                .build();

        AlbumEntity album = AlbumEntity.builder()
                .libraryEntity(library)
                .personEntity(artist)
                .name("AlbumName")
                .releaseYear(2024)
                .metadataEntities(List.of())
                .build();
        ReflectionTestUtils.setField(album, "id", albumId);

        NodeEntity node = NodeEntity.builder().name("node1").url("http://localhost").build();
        DirectoryEntity dir = DirectoryEntity.builder()
                .name("music-dir")
                .path(tempDir.toString())
                .directoryType(DirectoryType.LIBRARY)
                .libraryEntity(library)
                .nodeEntity(node)
                .build();

        Path albumPath = tempDir.resolve("ArtistName").resolve("AlbumName (2024)");
        Files.createDirectories(albumPath);
        Files.writeString(albumPath.resolve("01 - Track.mp3"), "not an image");

        when(albumRepository.findById(albumId)).thenReturn(Optional.of(album));
        when(nodeService.getOrCreateNodeEntityForThisNode()).thenReturn(node);
        when(directoryRepository.findByDirectoryTypeAndNodeEntity(DirectoryType.LIBRARY, node))
                .thenReturn(List.of(dir));
        when(otherPathFileRepository.findByDirectoryEntityAndPath(any(), any())).thenReturn(Optional.empty());

        subject.handle(AlbumFoundData.builder()
                .eventType(EventType.ALBUM_FOUND)
                .albumId(albumId)
                .build());

        verify(messageSender, never()).sendFileScanRequested(any(), any());
    }

    @Test
    void handleDefersSendsUntilAfterCommit(@TempDir Path tempDir) throws IOException {
        UUID albumId = UUID.randomUUID();

        LibraryEntity library = LibraryEntity.builder()
                .libraryType(LibraryType.MUSIC)
                .name("Music")
                .build();
        ReflectionTestUtils.setField(library, "id", UUID.randomUUID());

        PersonEntity artist = PersonEntity.builder()
                .libraryEntity(library)
                .name("ArtistName")
                .build();

        AlbumEntity album = AlbumEntity.builder()
                .libraryEntity(library)
                .personEntity(artist)
                .name("AlbumName")
                .releaseYear(2024)
                .metadataEntities(List.of())
                .build();
        ReflectionTestUtils.setField(album, "id", albumId);

        NodeEntity node = NodeEntity.builder().name("node1").url("http://localhost").build();
        DirectoryEntity dir = DirectoryEntity.builder()
                .name("music-dir")
                .path(tempDir.toString())
                .directoryType(DirectoryType.LIBRARY)
                .libraryEntity(library)
                .nodeEntity(node)
                .build();

        Path albumPath = tempDir.resolve("ArtistName").resolve("AlbumName (2024)");
        Files.createDirectories(albumPath);
        Files.write(albumPath.resolve("cover.jpg"), new byte[]{1});
        String nfoPath = albumPath.resolve("album.nfo").toString();

        when(albumRepository.findById(albumId)).thenReturn(Optional.of(album));
        when(nodeService.getOrCreateNodeEntityForThisNode()).thenReturn(node);
        when(directoryRepository.findByDirectoryTypeAndNodeEntity(DirectoryType.LIBRARY, node))
                .thenReturn(List.of(dir));
        when(otherPathFileRepository.findByDirectoryEntityAndPath(dir, nfoPath))
                .thenReturn(Optional.of(new OtherPathFileEntity()));

        TransactionSynchronizationManager.initSynchronization();
        try {
            subject.handle(AlbumFoundData.builder()
                    .eventType(EventType.ALBUM_FOUND)
                    .albumId(albumId)
                    .build());

            // This handler deletes the album metadata in its own transaction: the NFO re-parse and
            // cover re-ingest must not run against the pre-delete state.
            verify(messageSender, never()).sendNfoFileFound(any(), any());
            verify(messageSender, never()).sendFileScanRequested(any(), any());

            TransactionSynchronizationManager.getSynchronizations().forEach(TransactionSynchronization::afterCommit);
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }

        verify(messageSender).sendNfoFileFound(any(), eq("music-dir"));
        verify(messageSender).sendFileScanRequested(any(), eq("music-dir"));
    }

    @Test
    void handleSkipsDirectoriesFromOtherLibraries() {
        UUID albumId = UUID.randomUUID();
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
                .build();

        AlbumEntity album = AlbumEntity.builder()
                .libraryEntity(library)
                .personEntity(artist)
                .name("AlbumName")
                .releaseYear(2024)
                .metadataEntities(List.of())
                .build();
        ReflectionTestUtils.setField(album, "id", albumId);

        NodeEntity node = NodeEntity.builder().name("node1").url("http://localhost").build();
        DirectoryEntity wrongLibDir = DirectoryEntity.builder()
                .name("movies-dir")
                .path("/movies")
                .directoryType(DirectoryType.LIBRARY)
                .libraryEntity(otherLibrary)
                .nodeEntity(node)
                .build();
        DirectoryEntity nullLibDir = DirectoryEntity.builder()
                .name("other-dir")
                .path("/other")
                .directoryType(DirectoryType.LIBRARY)
                .libraryEntity(null)
                .nodeEntity(node)
                .build();

        when(albumRepository.findById(albumId)).thenReturn(Optional.of(album));
        when(nodeService.getOrCreateNodeEntityForThisNode()).thenReturn(node);
        when(directoryRepository.findByDirectoryTypeAndNodeEntity(DirectoryType.LIBRARY, node))
                .thenReturn(List.of(wrongLibDir, nullLibDir));

        subject.handle(AlbumFoundData.builder()
                .eventType(EventType.ALBUM_FOUND)
                .albumId(albumId)
                .build());

        verify(otherPathFileRepository, never()).findByDirectoryEntityAndPath(any(), any());
        verify(messageSender, never()).sendNfoFileFound(any(), any());
    }
}
