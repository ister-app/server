package app.ister.core.artwork;

import app.ister.core.entity.ImageEntity;
import app.ister.core.repository.ImageRepository;
import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.UUID;

/**
 * The item an artwork action is about: exactly one kind of entity plus its id. Every image-bearing
 * entity hangs its images off a nullable foreign key on {@link ImageEntity}; this record is the
 * one place that maps a kind to that column, so the admin artwork API (candidates, select, upload,
 * video frame) never grows a switch per entity kind in each caller.
 */
public record ImageTarget(Kind kind, UUID id) {

    public enum Kind { MOVIE, SHOW, EPISODE, ALBUM, PERSON, BOOK, PODCAST, GAME, SERIES }

    public ImageTarget {
        if (kind == null || id == null) {
            throw new IllegalArgumentException("An image target needs a kind and an id");
        }
    }

    /**
     * Builds the target from the GraphQL/REST shape: one optional id per kind, exactly one set.
     * Throws {@link IllegalArgumentException} (→ BAD_REQUEST) when none or several are given.
     */
    public static ImageTarget of(@Nullable UUID movieId, @Nullable UUID showId, @Nullable UUID episodeId,
                                 @Nullable UUID albumId, @Nullable UUID personId, @Nullable UUID bookId,
                                 @Nullable UUID podcastId, @Nullable UUID gameId, @Nullable UUID seriesId) {
        ImageTarget found = null;
        UUID[] ids = {movieId, showId, episodeId, albumId, personId, bookId, podcastId, gameId, seriesId};
        Kind[] kinds = Kind.values();
        for (int i = 0; i < ids.length; i++) {
            if (ids[i] == null) {
                continue;
            }
            if (found != null) {
                throw new IllegalArgumentException("An image target must name exactly one item");
            }
            found = new ImageTarget(kinds[i], ids[i]);
        }
        if (found == null) {
            throw new IllegalArgumentException("An image target must name exactly one item");
        }
        return found;
    }

    /** The target an existing image row belongs to, or null for a row without any parent (seasons, achievements). */
    public static @Nullable ImageTarget ofImage(ImageEntity image) {
        if (image.getMovieEntityId() != null) return new ImageTarget(Kind.MOVIE, image.getMovieEntityId());
        if (image.getShowEntityId() != null) return new ImageTarget(Kind.SHOW, image.getShowEntityId());
        if (image.getEpisodeEntityId() != null) return new ImageTarget(Kind.EPISODE, image.getEpisodeEntityId());
        if (image.getAlbumEntityId() != null) return new ImageTarget(Kind.ALBUM, image.getAlbumEntityId());
        if (image.getPersonEntityId() != null) return new ImageTarget(Kind.PERSON, image.getPersonEntityId());
        if (image.getBookEntityId() != null) return new ImageTarget(Kind.BOOK, image.getBookEntityId());
        if (image.getPodcastEntityId() != null) return new ImageTarget(Kind.PODCAST, image.getPodcastEntityId());
        if (image.getGameEntityId() != null) return new ImageTarget(Kind.GAME, image.getGameEntityId());
        if (image.getSeriesEntityId() != null) return new ImageTarget(Kind.SERIES, image.getSeriesEntityId());
        return null;
    }

    /** Points the image row at this item (and at nothing else). */
    public void applyTo(ImageEntity image) {
        image.setMovieEntityId(kind == Kind.MOVIE ? id : null);
        image.setShowEntityId(kind == Kind.SHOW ? id : null);
        image.setEpisodeEntityId(kind == Kind.EPISODE ? id : null);
        image.setAlbumEntityId(kind == Kind.ALBUM ? id : null);
        image.setPersonEntityId(kind == Kind.PERSON ? id : null);
        image.setBookEntityId(kind == Kind.BOOK ? id : null);
        image.setPodcastEntityId(kind == Kind.PODCAST ? id : null);
        image.setGameEntityId(kind == Kind.GAME ? id : null);
        image.setSeriesEntityId(kind == Kind.SERIES ? id : null);
    }

    /** Every image row of this item, in id order (all types). */
    public List<ImageEntity> imagesOf(ImageRepository images) {
        return switch (kind) {
            case MOVIE -> images.findByMovieEntityId(id);
            case SHOW -> images.findByShowEntityId(id);
            case EPISODE -> images.findByEpisodeEntityId(id);
            case ALBUM -> images.findByAlbumEntityId(id);
            case PERSON -> images.findByPersonEntityId(id);
            case BOOK -> images.findByBookEntityId(id);
            case PODCAST -> images.findByPodcastEntityId(id);
            case GAME -> images.findByGameEntityId(id);
            case SERIES -> images.findBySeriesEntityId(id);
        };
    }

    /** Whether a video frame can be the source: only items that own video files. */
    public boolean hasVideo() {
        return kind == Kind.MOVIE || kind == Kind.EPISODE;
    }
}
