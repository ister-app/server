package app.ister.search.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Getter
@Setter
@Configuration
@ConfigurationProperties(prefix = "app.ister.typesense")
@ConditionalOnProperty(prefix = "app.ister.typesense", name = "enabled", havingValue = "true")
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
