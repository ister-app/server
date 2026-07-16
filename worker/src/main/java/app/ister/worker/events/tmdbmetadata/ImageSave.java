package app.ister.worker.events.tmdbmetadata;

import app.ister.core.entity.AlbumEntity;
import app.ister.core.entity.BookEntity;
import app.ister.core.entity.PersonEntity;
import app.ister.core.entity.DirectoryEntity;
import app.ister.core.entity.EpisodeEntity;
import app.ister.core.entity.MovieEntity;
import app.ister.core.entity.PodcastEntity;
import app.ister.core.entity.SeriesEntity;
import app.ister.core.entity.ShowEntity;
import app.ister.core.enums.EventType;
import app.ister.core.enums.ImageType;
import app.ister.core.eventdata.ImageFoundData;
import app.ister.core.service.MessageSender;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class ImageSave {
    private final MessageSender messageSender;

    public record MediaEntityRef(@Nullable MovieEntity movie,
                                  @Nullable ShowEntity show,
                                  @Nullable EpisodeEntity episode,
                                  @Nullable PersonEntity person,
                                  @Nullable AlbumEntity album,
                                  @Nullable BookEntity book,
                                  @Nullable PodcastEntity podcast,
                                  @Nullable SeriesEntity series) {

        public MediaEntityRef(@Nullable MovieEntity movie, @Nullable ShowEntity show,
                              @Nullable EpisodeEntity episode, @Nullable PersonEntity person,
                              @Nullable AlbumEntity album) {
            this(movie, show, episode, person, album, null, null, null);
        }

        public MediaEntityRef(@Nullable MovieEntity movie, @Nullable ShowEntity show,
                              @Nullable EpisodeEntity episode, @Nullable PersonEntity person,
                              @Nullable AlbumEntity album, @Nullable BookEntity book) {
            this(movie, show, episode, person, album, book, null, null);
        }

        public MediaEntityRef(@Nullable MovieEntity movie, @Nullable ShowEntity show,
                              @Nullable EpisodeEntity episode, @Nullable PersonEntity person,
                              @Nullable AlbumEntity album, @Nullable BookEntity book,
                              @Nullable PodcastEntity podcast) {
            this(movie, show, episode, person, album, book, podcast, null);
        }

        /** A comic series' artwork (Wikipedia thumbnail). */
        public static MediaEntityRef ofSeries(SeriesEntity series) {
            return new MediaEntityRef(null, null, null, null, null, null, null, series);
        }
    }

    public void save(DirectoryEntity cacheDisk,
                     String path,
                     ImageType imageType,
                     String language,
                     String sourceUri,
                     MediaEntityRef mediaEntityRef) {
        messageSender.sendImageFound(ImageFoundData.builder()
                .eventType(EventType.IMAGE_FOUND)
                .directoryEntityId(cacheDisk.getId())
                .path(path)
                .imageType(imageType)
                .language(language)
                .sourceUri(sourceUri)
                .movieEntityId(mediaEntityRef.movie() == null ? null : mediaEntityRef.movie().getId())
                .showEntityId(mediaEntityRef.show() == null ? null : mediaEntityRef.show().getId())
                .episodeEntityId(mediaEntityRef.episode() == null ? null : mediaEntityRef.episode().getId())
                .personEntityId(mediaEntityRef.person() == null ? null : mediaEntityRef.person().getId())
                .albumEntityId(mediaEntityRef.album() == null ? null : mediaEntityRef.album().getId())
                .bookEntityId(mediaEntityRef.book() == null ? null : mediaEntityRef.book().getId())
                .podcastEntityId(mediaEntityRef.podcast() == null ? null : mediaEntityRef.podcast().getId())
                .seriesEntityId(mediaEntityRef.series() == null ? null : mediaEntityRef.series().getId())
                // Route by the cache DIRECTORY name (matches HandleImageFound's queue), not the
                // node name — the IMAGE_FOUND queues are named per directory, so using the node
                // name sent every downloaded TMDB image to a queue with no consumer (silently
                // dropped), which is why no artwork was ever created.
                .build(), cacheDisk.getName());
    }
}
