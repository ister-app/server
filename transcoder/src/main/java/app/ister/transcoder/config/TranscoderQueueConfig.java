package app.ister.transcoder.config;

import lombok.RequiredArgsConstructor;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.Queue;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.stream.Stream;

import static app.ister.core.MessageQueue.APP_ISTER_SERVER_TRANSCODE_PASS_REQUESTED;
import static app.ister.core.MessageQueue.APP_ISTER_SERVER_TRANSCODE_REQUESTED;

@Configuration
@RequiredArgsConstructor
public class TranscoderQueueConfig {

    private final TranscoderDirectoryConfig config;

    @Bean
    public Declarables transcoderQueueDeclarables() {
        return new Declarables(
                config.getDirectories().stream()
                        .flatMap(dir -> Stream.of(
                                new Queue(APP_ISTER_SERVER_TRANSCODE_REQUESTED + "." + dir.getName()),
                                new Queue(APP_ISTER_SERVER_TRANSCODE_PASS_REQUESTED + "." + dir.getName())
                        ))
                        .toList()
        );
    }
}
