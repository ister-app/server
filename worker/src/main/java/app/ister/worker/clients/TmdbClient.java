package app.ister.worker.clients;

import app.ister.tmdbapi.api.DefaultApi;
import org.springframework.cloud.openfeign.FeignClient;

@FeignClient(name = "tmdb")
public interface TmdbClient extends DefaultApi {
}
