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

    private final TranscoderQueueNamingConfig namingConfig;

    @Bean
    public Declarables transcoderQueueDeclarables() {
        return new Declarables(
                namingConfig.effectiveNames().stream()
                        .flatMap(name -> Stream.of(
                                new Queue(APP_ISTER_SERVER_TRANSCODE_REQUESTED + "." + name),
                                new Queue(APP_ISTER_SERVER_TRANSCODE_PASS_REQUESTED + "." + name)
                        ))
                        .toList()
        );
    }
}
