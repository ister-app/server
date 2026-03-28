package app.ister.worker.config;

import org.springframework.amqp.core.Queue;
import org.springframework.amqp.support.converter.JacksonJsonMessageConverter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import static app.ister.core.MessageQueue.*;

@Configuration
public class QueueConfig {

    @Value("${app.ister.server.name}")
    private String nodeName;

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
    public Queue queueShowFound() {
        return new Queue(APP_ISTER_SERVER_SHOW_FOUND);
    }

    @Bean
    public Queue queueAnalyzeLibraryRequested() {
        return new Queue(APP_ISTER_SERVER_ANALYZE_LIBRARY_REQUESTED + "." + nodeName);
    }

    @Bean
    public Queue queueAnalyzeData() {
        return new Queue(APP_ISTER_SERVER_ANALYZE_DATA);
    }

}
