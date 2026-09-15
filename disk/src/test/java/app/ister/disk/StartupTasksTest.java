package app.ister.disk;

import app.ister.core.config.DirectoryQueueNames;
import app.ister.core.entity.DirectoryEntity;
import app.ister.core.entity.LibraryEntity;
import app.ister.core.entity.NodeEntity;
import app.ister.core.enums.DirectoryType;
import app.ister.core.enums.LibraryType;
import app.ister.core.repository.DirectoryRepository;
import app.ister.core.repository.LibraryRepository;
import app.ister.core.service.NodeService;
import app.ister.disk.config.AppIsterServerConfig;
import app.ister.disk.config.DirectoryConfigClass;
import app.ister.disk.config.LibraryConfigClass;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationContext;
import org.springframework.context.event.ContextRefreshedEvent;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class StartupTasksTest {

    @Mock private NodeService nodeService;
    @Mock private AppIsterServerConfig config;
    @Mock private DirectoryRepository directoryRepository;
    @Mock private LibraryRepository libraryRepository;
    @Mock private DirectoryQueueNames directoryQueueNames;
    @Mock private ApplicationContext applicationContext;
    @Mock private ApplicationContext parentContext;

    @InjectMocks
    private StartupTasks startupTasks;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(startupTasks, "sharedStorage", new app.ister.core.config.SharedStorageProperties());
        // every existing directory counts as attached to the node under test (no re-save on startup)
        lenient().when(directoryRepository.findAttachedNodes(any())).thenAnswer(inv ->
                Optional.ofNullable(nodeService.updateOrCreateNodeEntityForThisNode()).map(List::of).orElse(List.of()));
        ReflectionTestUtils.setField(startupTasks, "cacheDir", "/tmp/ister-cache");
        lenient().when(config.getLibraries()).thenReturn(List.of());
        lenient().when(config.getDirectories()).thenReturn(List.of());
        lenient().when(directoryQueueNames.allHelperDirectoryNames()).thenReturn(List.of());
    }

    private DirectoryEntity existingCacheDir(NodeEntity node) {
        return DirectoryEntity.builder()
                .directoryType(DirectoryType.CACHE).nodeEntity(node).path("/tmp/ister-cache").build();
    }

    private ContextRefreshedEvent rootEvent() {
        when(applicationContext.getParent()).thenReturn(null);
        return new ContextRefreshedEvent(applicationContext);
    }

    private ContextRefreshedEvent childEvent() {
        when(applicationContext.getParent()).thenReturn(parentContext);
        return new ContextRefreshedEvent(applicationContext);
    }

    private NodeEntity nodeWithId(UUID id) {
        NodeEntity node = NodeEntity.builder().name("test-node").url("http://localhost").build();
        ReflectionTestUtils.setField(node, "id", id);
        return node;
    }

    // ========== Child context ==========

    @Test
    void childContextEventIsSkipped() {
        startupTasks.onApplicationEvent(childEvent());

        verifyNoInteractions(nodeService);
    }

    // ========== Root context — cache directory ==========

    @Test
    void cacheDirectoryIsCreatedWhenAbsent() {
        UUID nodeId = UUID.randomUUID();
        NodeEntity node = nodeWithId(nodeId);
        when(nodeService.updateOrCreateNodeEntityForThisNode()).thenReturn(node);
        when(directoryRepository.findByDirectoryTypeAndNodeEntity(DirectoryType.CACHE, node))
                .thenReturn(List.of());

        startupTasks.onApplicationEvent(rootEvent());

        ArgumentCaptor<DirectoryEntity> captor = ArgumentCaptor.forClass(DirectoryEntity.class);
        verify(directoryRepository).save(captor.capture());
        assertEquals(DirectoryType.CACHE, captor.getValue().getDirectoryType());
        assertEquals("/tmp/ister-cache", captor.getValue().getPath());
        assertEquals(node, captor.getValue().getNodeEntity());
    }

    @Test
    void cacheDirectoryIsNotCreatedWhenAlreadyPresent() {
        UUID nodeId = UUID.randomUUID();
        NodeEntity node = nodeWithId(nodeId);
        when(nodeService.updateOrCreateNodeEntityForThisNode()).thenReturn(node);
        when(directoryRepository.findByDirectoryTypeAndNodeEntity(DirectoryType.CACHE, node))
                .thenReturn(List.of(DirectoryEntity.builder()
                        .directoryType(DirectoryType.CACHE).nodeEntity(node).path("/tmp/ister-cache").build()));

        startupTasks.onApplicationEvent(rootEvent());

        verify(directoryRepository, never()).save(any());
    }

    // ========== Libraries ==========

    @Test
    void newLibraryIsSaved() {
        UUID nodeId = UUID.randomUUID();
        NodeEntity node = nodeWithId(nodeId);
        when(nodeService.updateOrCreateNodeEntityForThisNode()).thenReturn(node);
        when(directoryRepository.findByDirectoryTypeAndNodeEntity(DirectoryType.CACHE, node))
                .thenReturn(List.of());

        LibraryConfigClass lib = libraryConfig("Movies", LibraryType.MOVIE);
        when(config.getLibraries()).thenReturn(List.of(lib));
        when(libraryRepository.findByName("Movies")).thenReturn(Optional.empty());

        startupTasks.onApplicationEvent(rootEvent());

        ArgumentCaptor<LibraryEntity> captor = ArgumentCaptor.forClass(LibraryEntity.class);
        verify(libraryRepository).save(captor.capture());
        assertEquals("Movies", captor.getValue().getName());
        assertEquals(LibraryType.MOVIE, captor.getValue().getLibraryType());
    }

    @Test
    void existingLibraryIsNotSavedAgain() {
        UUID nodeId = UUID.randomUUID();
        NodeEntity node = nodeWithId(nodeId);
        when(nodeService.updateOrCreateNodeEntityForThisNode()).thenReturn(node);
        when(directoryRepository.findByDirectoryTypeAndNodeEntity(DirectoryType.CACHE, node))
                .thenReturn(List.of());

        LibraryConfigClass lib = libraryConfig("Movies", LibraryType.MOVIE);
        when(config.getLibraries()).thenReturn(List.of(lib));
        when(libraryRepository.findByName("Movies"))
                .thenReturn(Optional.of(LibraryEntity.builder().name("Movies").build()));

        startupTasks.onApplicationEvent(rootEvent());

        verify(libraryRepository, never()).save(any());
    }

    @Test
    void dataIntegrityViolationOnLibrarySaveIsSwallowed() {
        UUID nodeId = UUID.randomUUID();
        NodeEntity node = nodeWithId(nodeId);
        when(nodeService.updateOrCreateNodeEntityForThisNode()).thenReturn(node);
        when(directoryRepository.findByDirectoryTypeAndNodeEntity(DirectoryType.CACHE, node))
                .thenReturn(List.of());

        LibraryConfigClass lib = libraryConfig("Movies", LibraryType.MOVIE);
        when(config.getLibraries()).thenReturn(List.of(lib));
        when(libraryRepository.findByName("Movies")).thenReturn(Optional.empty());
        when(libraryRepository.save(any())).thenThrow(new DataIntegrityViolationException("dup"));

        assertDoesNotThrow(() -> startupTasks.onApplicationEvent(rootEvent()));
    }

    // ========== Directories ==========

    @Test
    void newDirectoryIsCreated() {
        UUID nodeId = UUID.randomUUID();
        NodeEntity node = nodeWithId(nodeId);
        when(nodeService.updateOrCreateNodeEntityForThisNode()).thenReturn(node);
        when(directoryRepository.findByDirectoryTypeAndNodeEntity(DirectoryType.CACHE, node))
                .thenReturn(List.of());

        LibraryEntity library = LibraryEntity.builder().name("Movies").build();
        DirectoryConfigClass dir = directoryConfig("movies-dir", "/media/movies", "Movies");
        when(config.getDirectories()).thenReturn(List.of(dir));
        when(directoryRepository.findByName("movies-dir")).thenReturn(Optional.empty());
        when(libraryRepository.findByName("Movies")).thenReturn(Optional.of(library));

        startupTasks.onApplicationEvent(rootEvent());

        ArgumentCaptor<DirectoryEntity> captor = ArgumentCaptor.forClass(DirectoryEntity.class);
        // Two saves: library dir + cache dir
        verify(directoryRepository, times(2)).save(captor.capture());
        DirectoryEntity savedDir = captor.getAllValues().get(0);
        assertEquals("movies-dir", savedDir.getName());
        assertEquals("/media/movies", savedDir.getPath());
        assertEquals(DirectoryType.LIBRARY, savedDir.getDirectoryType());
        assertEquals(node, savedDir.getNodeEntity());
    }

    @Test
    void existingDirectoryWithUnchangedPathIsNotSaved() {
        UUID nodeId = UUID.randomUUID();
        NodeEntity node = nodeWithId(nodeId);
        when(nodeService.updateOrCreateNodeEntityForThisNode()).thenReturn(node);
        // Cache dir already exists → no save
        when(directoryRepository.findByDirectoryTypeAndNodeEntity(DirectoryType.CACHE, node))
                .thenReturn(List.of(existingCacheDir(node)));

        DirectoryConfigClass dir = directoryConfig("movies-dir", "/media/movies", "Movies");
        when(config.getDirectories()).thenReturn(List.of(dir));

        DirectoryEntity existing = DirectoryEntity.builder()
                .name("movies-dir").path("/media/movies")
                .nodeEntity(node).directoryType(DirectoryType.LIBRARY).build();
        when(directoryRepository.findByName("movies-dir")).thenReturn(Optional.of(existing));

        startupTasks.onApplicationEvent(rootEvent());

        verify(directoryRepository, never()).save(any());
    }

    @Test
    void existingDirectoryWithChangedPathIsUpdated() {
        UUID nodeId = UUID.randomUUID();
        NodeEntity node = nodeWithId(nodeId);
        when(nodeService.updateOrCreateNodeEntityForThisNode()).thenReturn(node);
        // Cache dir already exists → no save for cache dir
        when(directoryRepository.findByDirectoryTypeAndNodeEntity(DirectoryType.CACHE, node))
                .thenReturn(List.of(existingCacheDir(node)));

        DirectoryConfigClass dir = directoryConfig("movies-dir", "/new/movies", "Movies");
        when(config.getDirectories()).thenReturn(List.of(dir));

        DirectoryEntity existing = DirectoryEntity.builder()
                .name("movies-dir").path("/old/movies")
                .nodeEntity(node).directoryType(DirectoryType.LIBRARY).build();
        when(directoryRepository.findByName("movies-dir")).thenReturn(Optional.of(existing));

        startupTasks.onApplicationEvent(rootEvent());

        verify(directoryRepository).save(existing);
        assertEquals("/new/movies", existing.getPath());
    }

    @Test
    void directoryUsedByOtherNodeThrowsIllegalStateException() {
        UUID nodeId = UUID.randomUUID();
        UUID otherNodeId = UUID.randomUUID();
        NodeEntity node = nodeWithId(nodeId);
        NodeEntity otherNode = nodeWithId(otherNodeId);
        when(nodeService.updateOrCreateNodeEntityForThisNode()).thenReturn(node);

        DirectoryConfigClass dir = directoryConfig("movies-dir", "/media/movies", "Movies");
        when(config.getDirectories()).thenReturn(List.of(dir));

        DirectoryEntity existing = DirectoryEntity.builder()
                .name("movies-dir").path("/media/movies")
                .nodeEntity(otherNode).directoryType(DirectoryType.LIBRARY).build();
        when(directoryRepository.findByName("movies-dir")).thenReturn(Optional.of(existing));

        var event = rootEvent();
        assertThrows(IllegalStateException.class, () -> startupTasks.onApplicationEvent(event));
    }

    // ========== Helpers ==========

    private LibraryConfigClass libraryConfig(String name, LibraryType type) {
        LibraryConfigClass lib = new LibraryConfigClass();
        lib.setName(name);
        lib.setType(type);
        return lib;
    }

    private DirectoryConfigClass directoryConfig(String name, String path, String library) {
        DirectoryConfigClass dir = new DirectoryConfigClass();
        dir.setName(name);
        dir.setPath(path);
        dir.setLibrary(library);
        return dir;
    }

    // ========== Helper disks ==========

    /** An unknown helper disk name is a warning, not a failure: the owner may simply not be up yet. */
    @Test
    void unknownHelperDiskDoesNotFailStartup() {
        NodeEntity node = nodeWithId(UUID.randomUUID());
        when(nodeService.updateOrCreateNodeEntityForThisNode()).thenReturn(node);
        when(directoryRepository.findByDirectoryTypeAndNodeEntity(DirectoryType.CACHE, node)).thenReturn(List.of(existingCacheDir(node)));
        when(directoryQueueNames.allHelperDirectoryNames()).thenReturn(List.of("other-node-tv"));
        when(directoryRepository.findByName("other-node-tv")).thenReturn(Optional.empty());

        assertDoesNotThrow(() -> startupTasks.onApplicationEvent(rootEvent()));

        verify(directoryRepository).findByName("other-node-tv");
    }

    // ========== S3 directories ==========

    private DirectoryConfigClass s3DirectoryConfig(String name, String connection, String prefix, String library) {
        DirectoryConfigClass dir = new DirectoryConfigClass();
        dir.setName(name);
        dir.setS3Connection(connection);
        dir.setPrefix(prefix);
        dir.setLibrary(library);
        return dir;
    }

    private void s3Connection(String name, String bucket) {
        app.ister.core.config.S3Properties props = new app.ister.core.config.S3Properties();
        app.ister.core.config.S3Properties.Connection c = new app.ister.core.config.S3Properties.Connection();
        c.setName(name);
        c.setBucket(bucket);
        c.setEndpoint("http://minio:9000");
        props.getConnections().add(c);
        ReflectionTestUtils.setField(startupTasks, "s3Properties", props);
    }

    @Test
    void newS3DirectoryIsCreatedWithoutOwnerAndAttachedToThisNode() {
        NodeEntity node = nodeWithId(UUID.randomUUID());
        when(nodeService.updateOrCreateNodeEntityForThisNode()).thenReturn(node);
        when(directoryRepository.findByDirectoryTypeAndNodeEntity(DirectoryType.CACHE, node)).thenReturn(List.of());
        s3Connection("minio", "media");
        LibraryEntity library = LibraryEntity.builder().name("Shows").build();
        when(config.getDirectories()).thenReturn(List.of(s3DirectoryConfig("shows-s3", "minio", "/shows/", "Shows")));
        when(directoryRepository.findByName("shows-s3")).thenReturn(Optional.empty());
        when(libraryRepository.findByName("Shows")).thenReturn(Optional.of(library));

        startupTasks.onApplicationEvent(rootEvent());

        ArgumentCaptor<DirectoryEntity> captor = ArgumentCaptor.forClass(DirectoryEntity.class);
        verify(directoryRepository, times(2)).save(captor.capture());
        DirectoryEntity saved = captor.getAllValues().get(0);
        assertEquals("shows-s3", saved.getName());
        assertEquals(app.ister.core.enums.StorageKind.S3, saved.getStorageKind());
        assertNull(saved.getNodeEntity());
        assertEquals("s3://media/shows", saved.getPath());
        assertEquals("shows", saved.getS3Prefix());
        assertEquals("media", saved.getS3Bucket());
        assertEquals("minio", saved.getS3Connection());
        assertTrue(saved.getAttachedNodes().contains(node));
    }

    @Test
    void secondNodeWithTheSameS3DirectoryAttachesInsteadOfFailing() {
        NodeEntity node = nodeWithId(UUID.randomUUID());
        NodeEntity other = nodeWithId(UUID.randomUUID());
        other.setName("other");
        when(nodeService.updateOrCreateNodeEntityForThisNode()).thenReturn(node);
        when(directoryRepository.findByDirectoryTypeAndNodeEntity(DirectoryType.CACHE, node)).thenReturn(List.of(existingCacheDir(node)));
        s3Connection("minio", "media");
        DirectoryEntity existing = DirectoryEntity.builder().name("shows-s3").path("s3://media/shows")
                .storageKind(app.ister.core.enums.StorageKind.S3).s3Connection("minio").s3Bucket("media").s3Prefix("shows")
                .directoryType(DirectoryType.LIBRARY).build();
        ReflectionTestUtils.setField(existing, "id", UUID.randomUUID());
        when(config.getDirectories()).thenReturn(List.of(s3DirectoryConfig("shows-s3", "minio", "shows", "Shows")));
        when(directoryRepository.findByName("shows-s3")).thenReturn(Optional.of(existing));
        when(directoryRepository.findAttachedNodes(existing.getId())).thenReturn(List.of(other));

        assertDoesNotThrow(() -> startupTasks.onApplicationEvent(rootEvent()));

        verify(directoryRepository).save(existing);
        assertTrue(existing.getAttachedNodes().contains(node));
    }

    @Test
    void s3DirectoryPointedAtAnotherPrefixRefusesToStart() {
        NodeEntity node = nodeWithId(UUID.randomUUID());
        when(nodeService.updateOrCreateNodeEntityForThisNode()).thenReturn(node);
        s3Connection("minio", "media");
        DirectoryEntity existing = DirectoryEntity.builder().name("shows-s3").path("s3://media/shows")
                .storageKind(app.ister.core.enums.StorageKind.S3).s3Connection("minio").s3Bucket("media").s3Prefix("shows")
                .directoryType(DirectoryType.LIBRARY).build();
        when(config.getDirectories()).thenReturn(List.of(s3DirectoryConfig("shows-s3", "minio", "other-shows", "Shows")));
        when(directoryRepository.findByName("shows-s3")).thenReturn(Optional.of(existing));

        ContextRefreshedEvent event = rootEvent();
        IllegalStateException ex = assertThrows(IllegalStateException.class, () -> startupTasks.onApplicationEvent(event));
        assertTrue(ex.getMessage().contains("s3://media/other-shows"));
    }

    @Test
    void localConfigForAnS3DirectoryAndUnknownConnectionRefuseToStart() {
        NodeEntity node = nodeWithId(UUID.randomUUID());
        when(nodeService.updateOrCreateNodeEntityForThisNode()).thenReturn(node);
        DirectoryEntity existing = DirectoryEntity.builder().name("shows-s3").path("s3://media/shows")
                .storageKind(app.ister.core.enums.StorageKind.S3).s3Connection("minio").s3Bucket("media").s3Prefix("shows")
                .directoryType(DirectoryType.LIBRARY).build();
        when(config.getDirectories()).thenReturn(List.of(directoryConfig("shows-s3", "/media/shows", "Shows")));
        when(directoryRepository.findByName("shows-s3")).thenReturn(Optional.of(existing));
        ContextRefreshedEvent event = rootEvent();
        assertThrows(IllegalStateException.class, () -> startupTasks.onApplicationEvent(event));

        s3Connection("minio", "media");
        when(config.getDirectories()).thenReturn(List.of(s3DirectoryConfig("shows-s3", "nope", "shows", "Shows")));
        assertThrows(IllegalStateException.class, () -> startupTasks.onApplicationEvent(event));
    }

    @Test
    void prefixNormalizationStripsSlashesAndWhitespace() {
        assertEquals("", StartupTasks.normalizePrefix(null));
        assertEquals("", StartupTasks.normalizePrefix("/"));
        assertEquals("a/b", StartupTasks.normalizePrefix(" /a/b/ "));
    }
}
