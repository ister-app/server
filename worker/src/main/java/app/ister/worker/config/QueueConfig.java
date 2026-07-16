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
        // Only event DTOs may be resolved from the inbound __TypeId__ header; replaces the
        // former global spring.amqp.deserialization.trust.all=true (deserialization gadget risk).
        converter.getJavaTypeMapper().addTrustedPackages("app.ister.core.eventdata");
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

    @Bean
    public Queue queuePersonFound() {
        return new Queue(APP_ISTER_SERVER_PERSON_FOUND);
    }

    @Bean
    public Queue queueAlbumFound() {
        return new Queue(APP_ISTER_SERVER_ALBUM_FOUND);
    }

    @Bean
    public Queue queueBookFound() {
        return new Queue(APP_ISTER_SERVER_BOOK_FOUND);
    }

    @Bean
    public Queue queueComicSeriesFound() {
        return new Queue(APP_ISTER_SERVER_COMIC_SERIES_FOUND);
    }

    @Bean
    public Queue queuePodcastRefreshRequested() {
        return new Queue(APP_ISTER_SERVER_PODCAST_REFRESH_REQUESTED);
    }

    @Bean
    public Queue queueContinueWatchingRebuildRequested() {
        return new Queue(APP_ISTER_SERVER_CONTINUE_WATCHING_REBUILD_REQUESTED);
    }

}
