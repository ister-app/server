package app.ister.core.config;

import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AudienceOrAzpValidatorTest {

    private final AudienceOrAzpValidator subject = new AudienceOrAzpValidator("ister");

    private static Jwt.Builder jwt() {
        return Jwt.withTokenValue("t").header("alg", "none").claim("sub", "someone");
    }

    @Test
    void audienceListContainingExpectedPasses() {
        assertFalse(subject.validate(jwt().claim("aud", List.of("account", "ister")).build()).hasErrors());
    }

    @Test
    void audienceAsSingleStringPasses() {
        assertFalse(subject.validate(jwt().claim("aud", "ister").build()).hasErrors());
    }

    @Test
    void azpAloneSuffices() {
        // Stock Keycloak: aud only carries "account", azp carries the requesting client.
        assertFalse(subject.validate(jwt().claim("aud", List.of("account")).claim("azp", "ister").build()).hasErrors());
    }

    @Test
    void tokenForAnotherClientFails() {
        OAuth2TokenValidatorResult result =
                subject.validate(jwt().claim("aud", List.of("account")).claim("azp", "other-app").build());
        assertTrue(result.hasErrors());
        assertEquals("invalid_token", result.getErrors().iterator().next().getErrorCode());
    }

    @Test
    void tokenWithoutAudienceOrAzpFails() {
        assertTrue(subject.validate(jwt().build()).hasErrors());
    }

    @Test
    void emptyConfigurationMeansNoCheck() {
        assertFalse(OIDCSecurityConfig.audienceValidatorFor("").validate(jwt().claim("azp", "other-app").build()).hasErrors());
        assertFalse(OIDCSecurityConfig.audienceValidatorFor(null).validate(jwt().build()).hasErrors());
        assertTrue(OIDCSecurityConfig.audienceValidatorFor(" ister ").validate(jwt().build()).hasErrors());
    }

    @Test
    void blankExpectedValueIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> new AudienceOrAzpValidator(" "));
    }
}
