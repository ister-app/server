package app.ister.server.config;

import com.github.kokorin.jaffree.ffmpeg.FFmpeg;
import com.github.kokorin.jaffree.ffprobe.FFprobe;
import info.movito.themoviedbapi.TmdbApi;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.nio.file.Paths;

@Configuration
@EnableScheduling
public class SpringConfig {
    @Value("${app.ister.server.TMDB.apikey:'No api key available'}")
    private String apikey;

    @Bean
    public TmdbApi tmdbApi() {
        return new TmdbApi(apikey);
    }
}
