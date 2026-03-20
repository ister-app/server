package app.ister.api.controller;

import app.ister.core.repository.NodeRepository;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.graphql.data.method.annotation.QueryMapping;
import org.springframework.stereotype.Controller;

import java.util.ArrayList;
import java.util.List;

@Slf4j
@Controller
public class ServerInfoController {

    private final String clusterName;
    private final String serverUrl;
    private final String openIdConnectUrl;
    private final NodeRepository nodeRepository;

    public ServerInfoController(
            @Value("${app.ister.cluster.name}") String clusterName,
            @Value("${app.ister.server.url}") String serverUrl,
            @Value("${springdoc.oAuthFlow.openIdConnectUrl}") String openIdConnectUrl,
            NodeRepository nodeRepository) {
        this.clusterName = clusterName;
        this.serverUrl = serverUrl;
        this.openIdConnectUrl = openIdConnectUrl;
        this.nodeRepository = nodeRepository;
    }

    @QueryMapping
    public ServerInfo getServerInfo() {
        List<Node> nodes = new ArrayList<>();
        nodeRepository.findAll().forEach(e -> nodes.add(new Node(e.getId().toString(), e.getName(), e.getUrl(), e.getVersion())));
        return ServerInfo.builder()
                .name(clusterName)
                .url(serverUrl)
                .openIdUrl(openIdConnectUrl)
                .nodes(nodes)
                .build();
    }

    public record Node(String id, String name, String url, String version) {}

    @EqualsAndHashCode
    @Getter
    @Builder
    public static class ServerInfo {
        private String name;
        private String url;
        private String openIdUrl;
        private List<Node> nodes;
    }
}
