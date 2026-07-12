package app.ister.api.controller;

import app.ister.core.entity.BookEntity;
import app.ister.core.entity.ChapterEntity;
import app.ister.core.entity.EpisodeEntity;
import app.ister.core.entity.MovieEntity;
import app.ister.core.entity.PodcastEpisodeEntity;
import app.ister.core.enums.MediaType;

import java.time.Instant;

record RecentlyWatched(MediaType type, EpisodeEntity episode, MovieEntity movie,
                       ChapterEntity chapter, BookEntity book,
                       PodcastEpisodeEntity podcastEpisode, Instant lastWatched) {

    static RecentlyWatched ofEpisode(EpisodeEntity episode, Instant lastWatched) {
        return new RecentlyWatched(MediaType.EPISODE, episode, null, null, null, null, lastWatched);
    }

    static RecentlyWatched ofMovie(MovieEntity movie, Instant lastWatched) {
        return new RecentlyWatched(MediaType.MOVIE, null, movie, null, null, null, lastWatched);
    }

    static RecentlyWatched ofChapter(ChapterEntity chapter, Instant lastWatched) {
        return new RecentlyWatched(MediaType.CHAPTER, null, null, chapter, chapter.getBookEntity(), null, lastWatched);
    }

    static RecentlyWatched ofBook(BookEntity book, Instant lastWatched) {
        return new RecentlyWatched(MediaType.BOOK, null, null, null, book, null, lastWatched);
    }

    static RecentlyWatched ofPodcastEpisode(PodcastEpisodeEntity podcastEpisode, Instant lastWatched) {
        return new RecentlyWatched(MediaType.PODCAST_EPISODE, null, null, null, null, podcastEpisode, lastWatched);
    }
}
