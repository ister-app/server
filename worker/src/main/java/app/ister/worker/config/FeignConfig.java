package app.ister.worker.config;

import app.ister.worker.clients.TmdbClient;
import app.ister.worker.clients.TmdbRateLimiter;
import feign.RequestInterceptor;
import feign.RetryableException;
import feign.Retryer;
import feign.codec.Decoder;
import feign.codec.ErrorDecoder;
import feign.jackson.JacksonDecoder;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.cloud.openfeign.support.ResponseEntityDecoder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Collection;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@Configuration
@EnableFeignClients(clients = TmdbClient.class)
public class FeignConfig {

    @Bean
    public Decoder feignDecoder() {
        return new ResponseEntityDecoder(new JacksonDecoder());
    }

    @Bean
    public RequestInterceptor tmdbRateLimitInterceptor(TmdbRateLimiter tmdbRateLimiter) {
        return template -> tmdbRateLimiter.acquire();
    }

    /**
     * TMDB asks clients to respect 429 responses. Map them to RetryableException so the
     * Retryer below retries with backoff (honouring Retry-After when present).
     */
    @Bean
    public ErrorDecoder tmdbErrorDecoder() {
        ErrorDecoder defaultDecoder = new ErrorDecoder.Default();
        return (methodKey, response) -> {
            if (response.status() == 429) {
                Long retryAfterMillis = retryAfterMillis(response.headers());
                return new RetryableException(
                        response.status(),
                        "TMDB rate limit hit (429)",
                        response.request().httpMethod(),
                        retryAfterMillis,
                        response.request());
            }
            return defaultDecoder.decode(methodKey, response);
        };
    }

    private static Long retryAfterMillis(Map<String, Collection<String>> headers) {
        return headers.entrySet().stream()
                .filter(e -> "retry-after".equalsIgnoreCase(e.getKey()))
                .flatMap(e -> e.getValue().stream())
                .findFirst()
                .map(value -> {
                    try {
                        return TimeUnit.SECONDS.toMillis(Long.parseLong(value.trim()));
                    } catch (NumberFormatException _) {
                        return null;
                    }
                })
                .orElse(null);
    }

    @Bean
    public Retryer tmdbRetryer() {
        return new Retryer.Default(1000, TimeUnit.SECONDS.toMillis(30), 4);
    }
}
