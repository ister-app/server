package app.ister.api.controller;

import app.ister.core.entity.EpisodeEntity;
import app.ister.core.entity.MovieEntity;
import app.ister.core.entity.WatchStatusEntity;
import org.springframework.graphql.data.method.annotation.SchemaMapping;
import org.springframework.stereotype.Controller;

@Controller
public class WatchStatusController {

    @SchemaMapping(typeName = "WatchStatus", field = "episode")
    public EpisodeEntity episode(WatchStatusEntity watchStatusEntity) {
        return watchStatusEntity.getEpisodeEntity();
    }

    @SchemaMapping(typeName = "WatchStatus", field = "movie")
    public MovieEntity movie(WatchStatusEntity watchStatusEntity) {
        return watchStatusEntity.getMovieEntity();
    }
}
