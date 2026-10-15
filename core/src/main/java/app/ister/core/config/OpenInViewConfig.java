package app.ister.core.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.orm.jpa.support.OpenEntityManagerInViewInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Open-in-view, minus the streaming endpoints.
 *
 * <p>The GraphQL resolvers navigate lazy associations after their query returned, so the
 * application keeps {@code spring.jpa.open-in-view=true}. The price is that a request's Hibernate
 * session, and with the vendor adapter's hold-until-close connection handling its JDBC connection,
 * live until the response is written. For a normal request that is milliseconds; for the HLS
 * endpoints, which poll for a playlist or segment for up to the transcode timeout, and for the
 * node-to-node media downloads, which stream a file for minutes, it is one pooled connection per
 * waiting client. A handful of players starting at once then emptied the pool for everything
 * else (the 2026-09 helper-download incident was the same mechanism).
 *
 * <p>Those paths are excluded here. They do not rely on the session: {@code HlsService} reads its
 * entities in short read-only transactions and hands plain values to the poll loops, and the
 * download endpoints read only basic columns. Defining the interceptor bean makes Spring Boot's
 * own {@code JpaWebConfiguration} back off, so this is the one place open-in-view is wired.
 *
 * <p>The condition mirrors Boot's: it is a build-time setting, not a runtime toggle, so it is
 * safe in the native image.
 */
@Configuration
@ConditionalOnProperty(prefix = "spring.jpa", name = "open-in-view", havingValue = "true", matchIfMissing = true)
public class OpenInViewConfig {

    /** Request paths that wait or stream for long and never navigate a lazy association. */
    static final String[] STREAMING_PATHS = {
            "/hls/**",
            "/mediaFile/*/download",
            "/mediaFileStream/*/download",
    };

    @Bean
    OpenEntityManagerInViewInterceptor openEntityManagerInViewInterceptor() {
        return new OpenEntityManagerInViewInterceptor();
    }

    @Bean
    WebMvcConfigurer openEntityManagerInViewInterceptorConfigurer(OpenEntityManagerInViewInterceptor interceptor) {
        return new WebMvcConfigurer() {
            @Override
            public void addInterceptors(InterceptorRegistry registry) {
                registry.addWebRequestInterceptor(interceptor).excludePathPatterns(STREAMING_PATHS);
            }
        };
    }
}
