package app.ister.server;

import io.swagger.v3.oas.annotations.enums.SecuritySchemeType;
import io.swagger.v3.oas.annotations.security.SecurityScheme;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

import static app.ister.server.events.MessageQueue.APP_ISTER_SERVER_EPISODE_FOUND;
import static app.ister.server.events.MessageQueue.APP_ISTER_SERVER_MEDIA_FILE_FOUND;
import static app.ister.server.events.MessageQueue.APP_ISTER_SERVER_NEW_DIRECTORIES_SCAN_REQUESTED;
import static app.ister.server.events.MessageQueue.APP_ISTER_SERVER_NFO_FILE_FOUND;
import static app.ister.server.events.MessageQueue.APP_ISTER_SERVER_SHOW_FOUND;
import static app.ister.server.events.MessageQueue.APP_ISTER_SERVER_SUBTITLE_FILE_FOUND;

@SpringBootApplication(scanBasePackages = "app.ister.server")
@SecurityScheme(
        name = "oidc_auth",
        type = SecuritySchemeType.OPENIDCONNECT,
        openIdConnectUrl = "${springdoc.oAuthFlow.openIdConnectUrl}"
)
public class IsterServerApplication {
    public static void main(String[] args) {
        SpringApplication.run(IsterServerApplication.class, args);
    }

    @Bean
    Jackson2JsonMessageConverter converter() {
        Jackson2JsonMessageConverter converter = new Jackson2JsonMessageConverter();
        converter.setNullAsOptionalEmpty(true);
        return converter;
    }

    @Bean
    public Queue queueEpisodeFound() {
        return new Queue(APP_ISTER_SERVER_EPISODE_FOUND);
    }

    @Bean
    public Queue queueMediaFileFound() {
        return new Queue(APP_ISTER_SERVER_MEDIA_FILE_FOUND);
    }

    @Bean
    public Queue queueNewDirectoriesScanRequested() {
        return new Queue(APP_ISTER_SERVER_NEW_DIRECTORIES_SCAN_REQUESTED);
    }

    @Bean
    public Queue queueNfoFileFound() {
        return new Queue(APP_ISTER_SERVER_NFO_FILE_FOUND);
    }

    @Bean
    public Queue queueShowFound() {
        return new Queue(APP_ISTER_SERVER_SHOW_FOUND);
    }

    @Bean
    public Queue queueSubtitleFileFound() {
        return new Queue(APP_ISTER_SERVER_SUBTITLE_FILE_FOUND);
    }

}
