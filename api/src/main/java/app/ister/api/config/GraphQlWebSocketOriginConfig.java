package app.ister.api.config;

import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.web.socket.server.HandshakeInterceptor;
import org.springframework.web.socket.server.support.OriginHandshakeInterceptor;
import org.springframework.web.socket.server.support.WebSocketHandlerMapping;
import org.springframework.web.socket.server.support.WebSocketHttpRequestHandler;

import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Widens the origin check on the GraphQL websocket handshake to the server's public host.
 *
 * <p>Spring GraphQL always installs an {@link OriginHandshakeInterceptor} on the handshake, and
 * without {@code spring.graphql.cors.*} it carries an empty allow-list, which means "same origin
 * only". Behind a TLS-terminating proxy the app itself speaks plain HTTP, so a browser on
 * {@code https://<public host>} is not the same origin as the {@code http://…:8080} request the
 * app sees, and the handshake is rejected with 403 while every plain HTTP call keeps working.
 * Forwarded headers fix this only when the proxy sends them <em>and</em>
 * {@code server.forward-headers-strategy} is on, so we do not rely on them.
 *
 * <p>The public host is derived from {@code app.ister.server.url} — already required config — in
 * both schemes and on any port. {@code app.ister.server.websocket.allowed-origins} adds further
 * origins (or patterns, {@code *} for all) for deployments served under more than one name.
 * Clients that send no {@code Origin} at all (the Dart client) are unaffected: the check only
 * looks at an Origin header when there is one.
 */
@Configuration
public class GraphQlWebSocketOriginConfig {

    /**
     * Static so the post-processor is created before the beans it inspects; the properties are
     * read from the {@link Environment} rather than injected for the same reason.
     */
    @Bean
    static BeanPostProcessor graphQlWebSocketOriginPostProcessor(Environment environment) {
        List<String> patterns = allowedOriginPatterns(
                environment.getProperty("app.ister.server.url", ""),
                environment.getProperty("app.ister.server.websocket.allowed-origins", String[].class, new String[0]));
        return new OriginPostProcessor(patterns);
    }

    static List<String> allowedOriginPatterns(String serverUrl, String[] configuredOrigins) {
        Set<String> patterns = new LinkedHashSet<>(List.of(configuredOrigins));
        if (patterns.contains("*")) {
            return List.of("*");
        }
        String host = publicHost(serverUrl);
        if (host != null) {
            patterns.add("http://" + host);
            patterns.add("https://" + host);
            patterns.add("http://" + host + ":*");
            patterns.add("https://" + host + ":*");
        }
        return List.copyOf(patterns);
    }

    private static String publicHost(String serverUrl) {
        if (serverUrl == null || serverUrl.isBlank()) {
            return null;
        }
        try {
            return URI.create(serverUrl).getHost();
        } catch (IllegalArgumentException _) {
            return null;
        }
    }

    /** Replaces the origin interceptor Spring GraphQL installed with one that knows the public host. */
    record OriginPostProcessor(List<String> allowedOriginPatterns) implements BeanPostProcessor {

        @Override
        public Object postProcessAfterInitialization(Object bean, String beanName) {
            if (allowedOriginPatterns.isEmpty() || !(bean instanceof WebSocketHandlerMapping mapping)) {
                return bean;
            }
            mapping.getUrlMap().values().stream()
                    .filter(WebSocketHttpRequestHandler.class::isInstance)
                    .map(WebSocketHttpRequestHandler.class::cast)
                    .forEach(this::widenOriginCheck);
            return bean;
        }

        private void widenOriginCheck(WebSocketHttpRequestHandler handler) {
            List<HandshakeInterceptor> interceptors = new ArrayList<>(handler.getHandshakeInterceptors());
            interceptors.replaceAll(interceptor -> interceptor instanceof OriginHandshakeInterceptor origin
                    ? widen(origin) : interceptor);
            handler.setHandshakeInterceptors(interceptors);
        }

        /** Keeps whatever {@code spring.graphql.cors.*} already allowed and adds our patterns. */
        private OriginHandshakeInterceptor widen(OriginHandshakeInterceptor current) {
            OriginHandshakeInterceptor interceptor = new OriginHandshakeInterceptor();
            interceptor.setAllowedOrigins(current.getAllowedOrigins());
            Set<String> patterns = new LinkedHashSet<>(current.getAllowedOriginPatterns());
            patterns.addAll(allowedOriginPatterns);
            interceptor.setAllowedOriginPatterns(patterns);
            return interceptor;
        }
    }
}
