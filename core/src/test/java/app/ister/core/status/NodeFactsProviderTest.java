package app.ister.core.status;

import app.ister.core.config.HelperJob;
import app.ister.core.config.HelperProperties;
import app.ister.core.entity.DirectoryEntity;
import app.ister.core.entity.LibraryEntity;
import app.ister.core.entity.NodeEntity;
import app.ister.core.enums.DirectoryType;
import app.ister.core.eventdata.NodeActivityStatusData.NodeFacts;
import app.ister.core.repository.DirectoryRepository;
import app.ister.core.repository.NodeRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.file.Path;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class NodeFactsProviderTest {

    @Mock
    private DirectoryRepository directoryRepository;

    @Mock
    private NodeRepository nodeRepository;

    private HelperProperties helperProperties;
    private NodeFactsProvider subject;

    @BeforeEach
    void setUp() {
        helperProperties = new HelperProperties();
        subject = new NodeFactsProvider(directoryRepository, nodeRepository, helperProperties, "node1");
    }

    @Test
    void reportsDirectoriesWithDiskSpaceAndHelperSetup(@TempDir Path mounted) {
        NodeEntity node = NodeEntity.builder().id(UUID.randomUUID()).name("node1").build();
        LibraryEntity library = LibraryEntity.builder().name("Series").build();
        when(nodeRepository.findByName("node1")).thenReturn(Optional.of(node));
        when(directoryRepository.findByNodeEntity(node)).thenReturn(List.of(
                DirectoryEntity.builder().name("node1-cache-directory").path("/nowhere/cache")
                        .directoryType(DirectoryType.CACHE).build(),
                DirectoryEntity.builder().name("disk1").path(mounted.toString()).libraryEntity(library)
                        .directoryType(DirectoryType.LIBRARY).build()));
        HelperProperties.DiskEntry helped = new HelperProperties.DiskEntry();
        helped.setName("disk9");
        helped.setJobs(EnumSet.of(HelperJob.TRANSCODE));
        helperProperties.getDisks().add(helped);
        helperProperties.setOffloadJobs(EnumSet.of(HelperJob.TRANSCODE));

        NodeFacts facts = subject.facts();

        assertNotNull(facts.getStartedAt());
        assertEquals(2, facts.getDirectories().size());
        // Sorted by name: disk1 before node1-cache-directory.
        var disk1 = facts.getDirectories().get(0);
        assertEquals("disk1", disk1.getName());
        assertEquals("LIBRARY", disk1.getType());
        assertEquals("Series", disk1.getLibrary());
        assertTrue(disk1.getTotalBytes() > 0);
        assertTrue(disk1.getFreeBytes() >= 0);
        var cache = facts.getDirectories().get(1);
        assertEquals("CACHE", cache.getType());
        assertNull(cache.getLibrary());
        assertNull(cache.getTotalBytes(), "an unmounted path has no space figures");
        assertEquals(List.of("disk9"), facts.getHelperDisks().stream().map(d -> d.getName()).toList());
        assertEquals(List.of("TRANSCODE"), facts.getHelperDisks().getFirst().getJobs());
        assertEquals(List.of("TRANSCODE"), facts.getOffloadJobs());
    }

    @Test
    void cachesTheFactsBetweenCalls() {
        when(nodeRepository.findByName("node1")).thenReturn(Optional.empty());

        NodeFacts first = subject.facts();
        NodeFacts second = subject.facts();

        assertSame(first, second);
        verify(nodeRepository, times(1)).findByName("node1");
    }

    @Test
    void keepsTheLastFactsWhenTheLookupFails() {
        when(nodeRepository.findByName("node1")).thenThrow(new IllegalStateException("db down"));

        assertNull(subject.facts());
        verify(directoryRepository, times(0)).findByNodeEntity(any());
    }
}
