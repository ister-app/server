package app.ister.core.config;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.security.oauth2.server.resource.autoconfigure.OAuth2ResourceServerAutoConfiguration;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.util.Date;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Proves that the auto-configured {@link JwtDecoder} fetches the JWKS through the JDK HTTP client:
 * a throwaway HTTP server serves the key set and records the User-Agent, which the JDK client sets
 * to {@code Java-http-client/...} while Spring Security's default RestTemplate sends {@code Java/...}.
 */
class JwkSetHttpClientConfigTest {

    private HttpServer server;
    private final AtomicReference<String> userAgent = new AtomicReference<>();
    private KeyPair keyPair;

    @BeforeEach
    void startJwksServer() throws Exception {
        keyPair = KeyPairGenerator.getInstance("RSA").generateKeyPair();
        RSAKey key = new RSAKey.Builder((RSAPublicKey) keyPair.getPublic()).keyID("k1").build();
        byte[] jwks = new JWKSet(key).toPublicJWKSet().toString().getBytes(StandardCharsets.UTF_8);

        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/jwks", exchange -> {
            userAgent.set(exchange.getRequestHeaders().getFirst("User-Agent"));
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, jwks.length);
            try (var body = exchange.getResponseBody()) {
                body.write(jwks);
            }
        });
        server.start();
    }

    @AfterEach
    void stopJwksServer() {
        server.stop(0);
    }

    @Test
    void jwksIsFetchedWithTheJdkHttpClient() throws Exception {
        String token = sign(keyPair);
        String jwkSetUri = "http://127.0.0.1:" + server.getAddress().getPort() + "/jwks";

        new WebApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(OAuth2ResourceServerAutoConfiguration.class))
                .withUserConfiguration(JwkSetHttpClientConfig.class)
                .withPropertyValues("spring.security.oauth2.resourceserver.jwt.jwk-set-uri=" + jwkSetUri)
                .run(context -> {
                    JwtDecoder decoder = context.getBean(JwtDecoder.class);
                    Jwt jwt = assertDoesNotThrow(() -> decoder.decode(token));
                    assertEquals("alice", jwt.getSubject());
                    String agent = userAgent.get();
                    assertNotNull(agent, "the JWKS endpoint was not called");
                    assertTrue(agent.startsWith("Java-http-client"), "expected the JDK HTTP client, got User-Agent " + agent);
                });
    }

    private static String sign(KeyPair keyPair) throws Exception {
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .subject("alice")
                .expirationTime(new Date(System.currentTimeMillis() + 60_000))
                .build();
        SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID("k1").build(), claims);
        jwt.sign(new RSASSASigner((RSAPrivateKey) keyPair.getPrivate()));
        return jwt.serialize();
    }
}
