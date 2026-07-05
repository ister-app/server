package app.ister.search.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * The enabled flag is checked at runtime (not via bean conditions): GraalVM native images
 * evaluate bean conditions at build time, so conditional beans would be baked out of the
 * image and {@code TYPESENSE_ENABLED=true} on a deployment would do nothing.
 */
@Getter
@Setter
@Configuration
@ConfigurationProperties(prefix = "app.ister.typesense")
public class TypesenseProperties {
    private boolean enabled;
    private String host;
    private int port;
    private String protocol;
    private String apiKey;
    /** Alias name; physical collections are named {@code <collection>_v<epochMillis>}. */
    private String collection;

    public String baseUrl() {
        return protocol + "://" + host + ":" + port;
    }
}
