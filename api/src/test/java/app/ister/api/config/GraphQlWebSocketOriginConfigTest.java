package app.ister.api.config;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GraphQlWebSocketOriginConfigTest {

    @Test
    void derivesBothSchemesAndAnyPortFromThePublicUrl() {
        assertEquals(
                List.of(
                        "http://media.droogers.cloud",
                        "https://media.droogers.cloud",
                        "http://media.droogers.cloud:*",
                        "https://media.droogers.cloud:*"),
                GraphQlWebSocketOriginConfig.allowedOriginPatterns(
                        "https://media.droogers.cloud/api", new String[0]));
    }

    @Test
    void keepsConfiguredOriginsNextToTheDerivedOnes() {
        var patterns = GraphQlWebSocketOriginConfig.allowedOriginPatterns(
                "http://localhost:8080", new String[]{"http://localhost:5000"});

        assertTrue(patterns.contains("http://localhost:5000"));
        assertTrue(patterns.contains("https://localhost:*"));
    }

    @Test
    void wildcardShortCircuitsEverythingElse() {
        assertEquals(List.of("*"), GraphQlWebSocketOriginConfig.allowedOriginPatterns(
                "https://media.droogers.cloud", new String[]{"*"}));
    }

    @Test
    void withoutAPublicUrlNothingIsWidened() {
        assertTrue(GraphQlWebSocketOriginConfig.allowedOriginPatterns("", new String[0]).isEmpty());
    }
}
