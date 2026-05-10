package app.ister.disk;

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
    @Mock private ApplicationContext applicationContext;
    @Mock private ApplicationContext parentContext;

    private StartupTasks startupTasks;

    @BeforeEach
    void setUp() {
        startupTasks = new StartupTasks(nodeService, config, directoryRepository, libraryRepository);
        ReflectionTestUtils.setField(startupTasks, "cacheDir", "/tmp/ister-cache");
        lenient().when(config.getLibraries()).thenReturn(List.of());
        lenient().when(config.getDirectories()).thenReturn(List.of());
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
}
