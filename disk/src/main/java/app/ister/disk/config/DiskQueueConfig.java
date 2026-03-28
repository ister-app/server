package app.ister.disk.config;

import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.Queue;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;
import java.util.stream.Stream;

import static app.ister.core.MessageQueue.*;

@Configuration
public class DiskQueueConfig {

    @Value("${app.ister.server.name}")
    private String nodeName;

    @Bean
    public Declarables diskQueueDeclarables(AppIsterServerConfig config) {
        List<String> bases = List.of(
                APP_ISTER_SERVER_FILE_SCAN_REQUESTED,
                APP_ISTER_SERVER_MEDIA_FILE_FOUND,
                APP_ISTER_SERVER_NEW_DIRECTORIES_SCAN_REQUESTED,
                APP_ISTER_SERVER_NFO_FILE_FOUND,
                APP_ISTER_SERVER_SUBTITLE_FILE_FOUND,
                APP_ISTER_SERVER_IMAGE_FOUND,
                APP_ISTER_SERVER_UPDATE_IMAGES_REQUESTED,
                APP_ISTER_SERVER_ANALYZE_DATA
        );
        String cacheDirName = nodeName + "-cache-directory";
        return new Declarables(
                Stream.concat(
                        config.getDirectories().stream()
                                .flatMap(dir -> bases.stream().map(base -> new Queue(base + "." + dir.getName()))),
                        bases.stream().map(base -> new Queue(base + "." + cacheDirName))
                ).toList()
        );
    }

}
