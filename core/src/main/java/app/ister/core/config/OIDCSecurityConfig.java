package app.ister.core.config;

import app.ister.core.service.LibraryAccessService;
import app.ister.core.service.MediaLibraryResolver;
import app.ister.core.service.StreamTokenService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;

import java.net.URI;

import static org.springframework.security.config.Customizer.withDefaults;

@Configuration
@EnableMethodSecurity(prePostEnabled = true, securedEnabled = true)
@EnableWebSecurity
public class OIDCSecurityConfig {

    private final StreamTokenService streamTokenService;
    private final MediaLibraryResolver mediaLibraryResolver;
    private final LibraryAccessService libraryAccessService;

    public OIDCSecurityConfig(StreamTokenService streamTokenService,
                              MediaLibraryResolver mediaLibraryResolver,
                              LibraryAccessService libraryAccessService) {
        this.streamTokenService = streamTokenService;
        this.mediaLibraryResolver = mediaLibraryResolver;
        this.libraryAccessService = libraryAccessService;
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http,
            @Value("${app.ister.server.url}") String serverUrl) throws Exception {
        String pathPrefix = extractPathPrefix(serverUrl);
        http
                .oauth2ResourceServer(oauth2 -> oauth2.jwt(jwt -> jwt
                        .jwtAuthenticationConverter(jwtAuthenticationConverter()))
                )
                .addFilterBefore(new StreamTokenAuthenticationFilter(streamTokenService, pathPrefix), BearerTokenAuthenticationFilter.class)
                .addFilterAfter(new MediaAccessEnforcementFilter(mediaLibraryResolver, libraryAccessService, pathPrefix), BearerTokenAuthenticationFilter.class)
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers("/v3/api-docs/**").permitAll()
                        .requestMatchers("/swagger-ui/**").permitAll()
                        .requestMatchers("/graphiql/**").permitAll()
                        .requestMatchers("/graphiql").permitAll()
                        .requestMatchers("/graphql").permitAll()
                        .requestMatchers("/actuator/**").permitAll()
                        .requestMatchers("/.well-known/ister").permitAll()
                        .anyRequest().authenticated()
                )
                .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .csrf(AbstractHttpConfigurer::disable)
                .exceptionHandling(eh -> eh.authenticationEntryPoint((request, response, authException) ->
                    response.sendError(HttpStatus.UNAUTHORIZED.value(), HttpStatus.UNAUTHORIZED.getReasonPhrase())))
                .cors(withDefaults());
        return http.build();
    }

    private static String extractPathPrefix(String url) {
        String path = URI.create(url).getPath();
        if (path == null || path.equals("/")) return "";
        return path.endsWith("/") ? path.substring(0, path.length() - 1) : path;
    }

    @Bean
    public JwtAuthenticationConverter jwtAuthenticationConverter() {
        JwtGrantedAuthoritiesConverter grantedAuthoritiesConverter = new JwtGrantedAuthoritiesConverter();
        grantedAuthoritiesConverter.setAuthorityPrefix("ROLE_");
        grantedAuthoritiesConverter.setAuthoritiesClaimName("roles");

        JwtAuthenticationConverter jwtAuthenticationConverter = new JwtAuthenticationConverter();
        jwtAuthenticationConverter.setJwtGrantedAuthoritiesConverter(grantedAuthoritiesConverter);
        return jwtAuthenticationConverter;
    }
}
