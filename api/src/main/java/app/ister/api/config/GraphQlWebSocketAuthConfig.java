package app.ister.api.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.graphql.server.support.BearerTokenAuthenticationExtractor;
import org.springframework.graphql.server.webmvc.AuthenticationWebSocketInterceptor;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationProvider;

/**
 * Authenticates GraphQL-over-WebSocket connections. Browsers cannot send an
 * Authorization header on a websocket handshake, so the JWT is passed in the
 * connection_init payload instead ({"Authorization": "Bearer <jwt>"}). The
 * interceptor stores the resulting SecurityContext in the websocket session and
 * propagates it to every subscribe message, so @PreAuthorize works unchanged on
 * subscription controllers.
 */
@Configuration
public class GraphQlWebSocketAuthConfig {

    @Bean
    public AuthenticationWebSocketInterceptor authenticationWebSocketInterceptor(
            JwtDecoder jwtDecoder, JwtAuthenticationConverter jwtAuthenticationConverter) {
        JwtAuthenticationProvider jwtAuthenticationProvider = new JwtAuthenticationProvider(jwtDecoder);
        jwtAuthenticationProvider.setJwtAuthenticationConverter(jwtAuthenticationConverter);
        return new AuthenticationWebSocketInterceptor(
                new BearerTokenAuthenticationExtractor(),
                new ProviderManager(jwtAuthenticationProvider));
    }
}
