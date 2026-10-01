package app.ister.api.controller;

import app.ister.core.repository.NodeRepository;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.graphql.data.method.annotation.QueryMapping;
import org.jspecify.annotations.Nullable;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.stereotype.Controller;

import java.util.ArrayList;
import java.util.List;

/**
 * Deliberately not {@code @PreAuthorize}d: a player that has not logged in yet needs the
 * name, url and OIDC url to start the login (the GraphQL twin of {@code /.well-known/ister}).
 * Everything beyond that — the nodes with their internal urls and versions — is only for
 * authenticated users; anonymous callers get an empty node list.
 */
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
    public ServerInfo getServerInfo(@Nullable Authentication authentication) {
        List<Node> nodes = new ArrayList<>();
        if (isUser(authentication)) {
            nodeRepository.findAll().forEach(e -> nodes.add(new Node(e.getId().toString(), e.getName(), e.getUrl(), e.getVersion())));
        }
        return ServerInfo.builder()
                .name(clusterName)
                .url(serverUrl)
                .openIdUrl(openIdConnectUrl)
                .nodes(nodes)
                .build();
    }

    /** Any authenticated Ister user; the anonymous token Spring Security injects carries no role. */
    private static boolean isUser(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()) {
            return false;
        }
        return authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .anyMatch(a -> a.equals("ROLE_user") || a.equals("ROLE_admin"));
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
