package app.ister.api.controller;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class WellKnownControllerTest {

    @Test
    void wellKnownReturnsCorrectFormat() {
        WellKnownController controller = new WellKnownController(
                "my-cluster",
                "https://server.local",
                "https://auth.local/.well-known/openid-configuration");

        String result = controller.wellKnown();

        assertEquals("my-cluster\nhttps://auth.local/.well-known/openid-configuration\nhttps://server.local", result);
    }

    @Test
    void wellKnownLineOrderIsClusterNameThenOidcThenServerUrl() {
        WellKnownController controller = new WellKnownController(
                "cluster-A",
                "https://my-server.example.com",
                "https://oidc.example.com/auth");

        String result = controller.wellKnown();
        String[] lines = result.split("\n");

        assertEquals(3, lines.length);
        assertEquals("cluster-A", lines[0]);
        assertEquals("https://oidc.example.com/auth", lines[1]);
        assertEquals("https://my-server.example.com", lines[2]);
    }
}
