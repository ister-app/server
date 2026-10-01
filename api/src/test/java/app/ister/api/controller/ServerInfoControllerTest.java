package app.ister.api.controller;

import app.ister.core.entity.NodeEntity;
import app.ister.core.repository.NodeRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ServerInfoControllerTest {

    @Mock
    private NodeRepository nodeRepository;

    @Mock
    private Authentication user;

    private static Authentication anonymous() {
        return new AnonymousAuthenticationToken("key", "anonymousUser",
                List.of(new SimpleGrantedAuthority("ROLE_ANONYMOUS")));
    }

    private void loggedInAs(String role) {
        when(user.isAuthenticated()).thenReturn(true);
        doReturn(List.of(new SimpleGrantedAuthority(role))).when(user).getAuthorities();
    }

    @Test
    void getServerInfoReturnsConfiguredValues() {
        NodeEntity node = NodeEntity.builder().name("main").url("http://localhost:8080").version("1.0.0").build();
        node.setId(UUID.randomUUID());
        when(nodeRepository.findAll()).thenReturn(List.of(node));
        loggedInAs("ROLE_user");

        ServerInfoController subject = new ServerInfoController("MyServer", "http://server.local", "https://auth.local/.well-known/openid-configuration", nodeRepository);

        ServerInfoController.ServerInfo result = subject.getServerInfo(user);

        assertEquals("MyServer", result.getName());
        assertEquals("http://server.local", result.getUrl());
        assertEquals("https://auth.local/.well-known/openid-configuration", result.getOpenIdUrl());
        assertEquals(1, result.getNodes().size());
        assertEquals("main", result.getNodes().get(0).name());
        assertEquals("http://localhost:8080", result.getNodes().get(0).url());
        assertEquals("1.0.0", result.getNodes().get(0).version());
    }

    @Test
    void getServerInfoReturnsEmptyNodesWhenNoneExist() {
        when(nodeRepository.findAll()).thenReturn(List.of());
        loggedInAs("ROLE_admin");

        ServerInfoController subject = new ServerInfoController("MyServer", "http://server.local", "https://auth.local/.well-known/openid-configuration", nodeRepository);

        ServerInfoController.ServerInfo result = subject.getServerInfo(user);

        assertTrue(result.getNodes().isEmpty());
    }

    @Test
    void anonymousCallerGetsDiscoveryFieldsButNoNodes() {
        ServerInfoController subject = new ServerInfoController("MyServer", "http://server.local", "https://auth.local/.well-known/openid-configuration", nodeRepository);

        ServerInfoController.ServerInfo result = subject.getServerInfo(anonymous());

        assertEquals("MyServer", result.getName());
        assertEquals("http://server.local", result.getUrl());
        assertEquals("https://auth.local/.well-known/openid-configuration", result.getOpenIdUrl());
        assertTrue(result.getNodes().isEmpty());
        verifyNoInteractions(nodeRepository);
    }

    @Test
    void missingAuthenticationGetsNoNodes() {
        ServerInfoController subject = new ServerInfoController("MyServer", "http://server.local", "https://auth.local/.well-known/openid-configuration", nodeRepository);

        assertTrue(subject.getServerInfo(null).getNodes().isEmpty());
        verifyNoInteractions(nodeRepository);
    }
}
