package app.ister.api.controller;

import app.ister.core.entity.BookEntity;
import app.ister.core.entity.ChapterEntity;
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

    @SchemaMapping(typeName = "WatchStatus", field = "chapter")
    public ChapterEntity chapter(WatchStatusEntity watchStatusEntity) {
        return watchStatusEntity.getChapterEntity();
    }

    @SchemaMapping(typeName = "WatchStatus", field = "book")
    public BookEntity book(WatchStatusEntity watchStatusEntity) {
        return watchStatusEntity.getBookEntity();
    }

    @SchemaMapping(typeName = "WatchStatus", field = "podcastEpisode")
    public app.ister.core.entity.PodcastEpisodeEntity podcastEpisode(WatchStatusEntity watchStatusEntity) {
        return watchStatusEntity.getPodcastEpisodeEntity();
    }
}
