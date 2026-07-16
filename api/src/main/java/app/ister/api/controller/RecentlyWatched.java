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

    /**
     * A book's single continue-watching entry. It can carry a reading target ({@code book}, resume
     * the epub) and/or a listening target ({@code chapter}, resume the audiobook). When only the
     * audio slot is set the book is derived from the chapter so the tile still has a title and cover.
     */
    static RecentlyWatched ofBook(BookEntity book, ChapterEntity chapter, Instant lastWatched) {
        BookEntity resolvedBook = book != null ? book : (chapter != null ? chapter.getBookEntity() : null);
        return new RecentlyWatched(MediaType.BOOK, null, null, chapter, resolvedBook, null, lastWatched);
    }

    static RecentlyWatched ofPodcastEpisode(PodcastEpisodeEntity podcastEpisode, Instant lastWatched) {
        return new RecentlyWatched(MediaType.PODCAST_EPISODE, null, null, null, null, podcastEpisode, lastWatched);
    }

    /** A comic series' entry: {@code book} is the volume to resume or start next. */
    static RecentlyWatched ofComic(BookEntity volume, Instant lastWatched) {
        return new RecentlyWatched(MediaType.COMIC, null, null, null, volume, null, lastWatched);
    }
}
