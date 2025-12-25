package app.ister.worker.config;

import app.ister.worker.clients.TmdbClient;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableFeignClients(clients = TmdbClient.class)
public class FeignConfig {
}
