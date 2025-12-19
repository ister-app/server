package app.ister.server;

import org.springframework.amqp.core.Queue;
import org.springframework.amqp.support.converter.JacksonJsonMessageConverter;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.context.annotation.Bean;

import static app.ister.server.events.MessageQueue.*;

@SpringBootApplication(scanBasePackages = "app.ister.server")
@EnableFeignClients
//@SecurityScheme(
//        name = "oidc_auth",
//        type = SecuritySchemeType.OPENIDCONNECT,
//        openIdConnectUrl = "${springdoc.oAuthFlow.openIdConnectUrl}"
//)
public class IsterServerApplication {
    static void main(String[] args) {
        SpringApplication.run(IsterServerApplication.class, args);
    }

    @Bean
    JacksonJsonMessageConverter converter() {
        JacksonJsonMessageConverter converter = new JacksonJsonMessageConverter();
        converter.setNullAsOptionalEmpty(true);
        return converter;
    }

    @Bean
    public Queue queueMovieFound() {
        return new Queue(APP_ISTER_SERVER_MOVIE_FOUND);
    }

    @Bean
    public Queue queueEpisodeFound() {
        return new Queue(APP_ISTER_SERVER_EPISODE_FOUND);
    }

    @Bean
    Queue queueFileScanRequested() {
        return new Queue(APP_ISTER_SERVER_FILE_SCAN_REQUESTED);
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
    public Queue queueAnalyzeLibraryRequested() {
        return new Queue(APP_ISTER_SERVER_ANALYZE_LIBRARY_REQUESTED);
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

    @Bean
    public Queue queueImageFound() {
        return new Queue(APP_ISTER_SERVER_IMAGE_FOUND);
    }

}
