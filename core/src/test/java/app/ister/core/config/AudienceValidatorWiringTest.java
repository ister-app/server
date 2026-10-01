package app.ister.core.config;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.security.oauth2.server.resource.autoconfigure.OAuth2ResourceServerAutoConfiguration;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidationException;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.util.Base64;
import java.util.Date;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Proves the wiring the audience check relies on: Spring Boot's auto-configured
 * {@link JwtDecoder} picks up an {@code OAuth2TokenValidator<Jwt>} bean, so
 * {@link OIDCSecurityConfig#audienceValidator} needs no decoder of its own. Signs tokens with a
 * throwaway RSA key and points the decoder at its public half, so nothing touches the network.
 */
class AudienceValidatorWiringTest {

    @TempDir
    Path tmp;

    @Test
    void autoConfiguredDecoderAppliesTheAudienceValidator() throws Exception {
        KeyPair keyPair = KeyPairGenerator.getInstance("RSA").generateKeyPair();
        Path publicKey = tmp.resolve("public.pem");
        Files.writeString(publicKey, "-----BEGIN PUBLIC KEY-----\n"
                + Base64.getMimeEncoder(64, "\n".getBytes()).encodeToString(keyPair.getPublic().getEncoded())
                + "\n-----END PUBLIC KEY-----\n");
        String forIster = sign(keyPair, "ister");
        String forOther = sign(keyPair, "other-app");

        new WebApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(OAuth2ResourceServerAutoConfiguration.class))
                .withPropertyValues("spring.security.oauth2.resourceserver.jwt.public-key-location=file:" + publicKey)
                .withBean("audienceValidator", OAuth2TokenValidator.class, () -> OIDCSecurityConfig.audienceValidatorFor("ister"))
                .run(context -> {
                    JwtDecoder decoder = context.getBean(JwtDecoder.class);
                    Jwt accepted = assertDoesNotThrow(() -> decoder.decode(forIster));
                    assertTrue(accepted.getAudience().contains("account"));
                    JwtValidationException rejected =
                            assertThrows(JwtValidationException.class, () -> decoder.decode(forOther));
                    assertTrue(rejected.getMessage().contains("expected aud or azp 'ister'"), rejected.getMessage());
                });
    }

    private static String sign(KeyPair keyPair, String azp) throws Exception {
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .subject("alice")
                .audience("account")
                .claim("azp", azp)
                .expirationTime(new Date(System.currentTimeMillis() + 60_000))
                .build();
        SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.RS256), claims);
        jwt.sign(new RSASSASigner((RSAPrivateKey) keyPair.getPrivate()));
        return jwt.serialize();
    }
}
