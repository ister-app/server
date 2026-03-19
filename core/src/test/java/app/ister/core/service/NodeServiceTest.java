package app.ister.core.service;

import app.ister.core.entity.NodeEntity;
import app.ister.core.repository.NodeRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class NodeServiceTest {

    @InjectMocks
    private NodeService subject;

    @Mock
    private NodeRepository nodeRepository;

    @Test
    void getOrCreateNodeEntityForThisNodeReturnsExisting() {
        NodeEntity existing = NodeEntity.builder().id(UUID.randomUUID()).name("node1").build();
        ReflectionTestUtils.setField(subject, "nodeName", "node1");
        ReflectionTestUtils.setField(subject, "nodeUrl", "http://localhost");
        ReflectionTestUtils.setField(subject, "buildVersion", "1.0.0");

        when(nodeRepository.findByName("node1")).thenReturn(Optional.of(existing));

        NodeEntity result = subject.getOrCreateNodeEntityForThisNode();

        assertEquals(existing, result);
    }

    @Test
    void getOrCreateNodeEntityForThisNodeCreatesNew() {
        ReflectionTestUtils.setField(subject, "nodeName", "node1");
        ReflectionTestUtils.setField(subject, "nodeUrl", "http://localhost");
        ReflectionTestUtils.setField(subject, "buildVersion", "1.0.0");

        when(nodeRepository.findByName("node1")).thenReturn(Optional.empty());

        NodeEntity result = subject.getOrCreateNodeEntityForThisNode();

        assertEquals("node1", result.getName());
        verify(nodeRepository).save(any(NodeEntity.class));
    }

    @Test
    void updateOrCreateNodeEntityForThisNodeUpdatesExisting() {
        NodeEntity existing = NodeEntity.builder().id(UUID.randomUUID()).name("node1").build();
        ReflectionTestUtils.setField(subject, "nodeName", "node1");
        ReflectionTestUtils.setField(subject, "nodeUrl", "http://updated");
        ReflectionTestUtils.setField(subject, "buildVersion", "2.0.0");

        when(nodeRepository.findByName("node1")).thenReturn(Optional.of(existing));

        NodeEntity result = subject.updateOrCreateNodeEntityForThisNode();

        assertEquals("http://updated", result.getUrl());
        assertEquals("2.0.0", result.getVersion());
        verify(nodeRepository).save(existing);
    }

    @Test
    void updateOrCreateNodeEntityForThisNodeCreatesNew() {
        ReflectionTestUtils.setField(subject, "nodeName", "node2");
        ReflectionTestUtils.setField(subject, "nodeUrl", "http://new");
        ReflectionTestUtils.setField(subject, "buildVersion", "1.0.0");

        when(nodeRepository.findByName("node2")).thenReturn(Optional.empty());

        NodeEntity result = subject.updateOrCreateNodeEntityForThisNode();

        assertEquals("node2", result.getName());
        assertEquals("http://new", result.getUrl());
        verify(nodeRepository).save(any(NodeEntity.class));
    }
}
