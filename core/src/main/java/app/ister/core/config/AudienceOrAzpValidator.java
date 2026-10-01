package app.ister.core.config;

import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2ErrorCodes;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.util.Assert;

import java.util.List;

/**
 * Accepts an access token only when it was minted for this server's client: {@code aud}
 * contains the expected value <em>or</em> {@code azp} equals it. The {@code azp} fallback is
 * what makes this work with a stock Keycloak client — Keycloak puts the requesting client id
 * in {@code azp} of every access token but only fills {@code aud} through an audience mapper.
 * Without this validator any token from the issuer is accepted, including one issued to a
 * different client in the same realm.
 */
public final class AudienceOrAzpValidator implements OAuth2TokenValidator<Jwt> {

    private final String expected;
    private final OAuth2Error error;

    public AudienceOrAzpValidator(String expected) {
        Assert.hasText(expected, "expected audience must not be blank");
        this.expected = expected;
        this.error = new OAuth2Error(OAuth2ErrorCodes.INVALID_TOKEN,
                "The token is not intended for this server (expected aud or azp '" + expected + "')",
                "https://tools.ietf.org/html/rfc6750#section-3.1");
    }

    @Override
    public OAuth2TokenValidatorResult validate(Jwt jwt) {
        List<String> audience = jwt.getAudience();
        if (audience != null && audience.contains(expected)) {
            return OAuth2TokenValidatorResult.success();
        }
        if (expected.equals(jwt.getClaimAsString("azp"))) {
            return OAuth2TokenValidatorResult.success();
        }
        return OAuth2TokenValidatorResult.failure(error);
    }
}
