package app.ister.core.service;

import app.ister.core.entity.NodeEntity;
import app.ister.core.repository.NodeRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Optional;

@Service
public class NodeService {
    @Autowired
    private NodeRepository nodeRepository;

    @Value("${app.ister.server.name}")
    private String nodeName;

    @Value("${app.ister.server.url}")
    private String nodeUrl;

    @Value("${git.build.version:?.?.?-development}")
    private String buildVersion;

    public NodeEntity getOrCreateNodeEntityForThisNode() {
        Optional<NodeEntity> nodeEntityOptional = nodeRepository.findByName(nodeName);
        if (nodeEntityOptional.isPresent()) {
            return nodeEntityOptional.get();
        } else {
            NodeEntity nodeEntity = NodeEntity.builder().name(nodeName).url(nodeUrl).version(buildVersion).build();
            nodeRepository.save(nodeEntity);
            return nodeEntity;
        }
    }

    public NodeEntity updateOrCreateNodeEntityForThisNode() {
        NodeEntity nodeEntity = nodeRepository.findByName(nodeName)
                .orElseGet(() -> NodeEntity.builder().name(nodeName).build());
        nodeEntity.setUrl(nodeUrl);
        nodeEntity.setVersion(buildVersion);
        nodeRepository.save(nodeEntity);
        return nodeEntity;
    }

}
