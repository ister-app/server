package app.ister.core.entity;

import com.fasterxml.jackson.annotation.JsonBackReference;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;

/**
 * A user's rating (1-10) for a single media item. Exactly one of the item associations
 * (movie/show/episode/album/track/book) is non-null; the rest are null. There is at most one
 * rating per (user, item) — enforced by the partial unique indexes in the Flyway migration.
 * Clearing a rating deletes the row rather than storing a null value.
 */
@Entity
@Table(name = "rating_entity")
@Getter
@Setter
@SuperBuilder
@NoArgsConstructor(access = AccessLevel.PUBLIC)
public class RatingEntity extends BaseEntity {

    @ManyToOne(optional = false)
    private UserEntity userEntity;

    @Getter(onMethod = @__(@JsonBackReference))
    @ManyToOne(optional = true)
    private MovieEntity movieEntity;

    @Getter(onMethod = @__(@JsonBackReference))
    @ManyToOne(optional = true)
    private ShowEntity showEntity;

    @Getter(onMethod = @__(@JsonBackReference))
    @ManyToOne(optional = true)
    private EpisodeEntity episodeEntity;

    @Getter(onMethod = @__(@JsonBackReference))
    @ManyToOne(optional = true)
    private AlbumEntity albumEntity;

    @Getter(onMethod = @__(@JsonBackReference))
    @ManyToOne(optional = true)
    private TrackEntity trackEntity;

    @Getter(onMethod = @__(@JsonBackReference))
    @ManyToOne(optional = true)
    private BookEntity bookEntity;

    @Getter(onMethod = @__(@JsonBackReference))
    @ManyToOne(optional = true)
    private PodcastEntity podcastEntity;

    @Column(nullable = false)
    private int value;
}
