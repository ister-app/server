package app.ister.core.entity;

import com.fasterxml.jackson.annotation.JsonBackReference;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;

import java.util.UUID;

@Entity
@Table(uniqueConstraints = @UniqueConstraint(columnNames = {"playQueueItemId", "userEntityId", "movieEntityId", "episodeEntityId", "chapterEntityId", "bookEntityId", "podcastEpisodeEntityId"}))
@Getter
@Setter
@SuperBuilder
@NoArgsConstructor(access = AccessLevel.PUBLIC)
public class WatchStatusEntity extends BaseEntity {

    /**
     * The play queue item id. Every user can have multiple WatchStatusEntity of the same media item.
     * When watching a media item it needs to update the correct WatchStatusEntity.
     *
     * <p>Epub reading has no play queue; for reading rows ({@link #bookEntity} set) this holds the
     * book id, which keeps the column non-null and gives one row per user per book.
     */
    @Column(nullable = false)
    private UUID playQueueItemId;

    @ManyToOne(optional = false)
    private UserEntity userEntity;

    @Getter(onMethod = @__(@JsonBackReference))
    @ManyToOne(optional = true)
    private MovieEntity movieEntity;

    @Getter(onMethod = @__(@JsonBackReference))
    @ManyToOne(optional = true)
    private EpisodeEntity episodeEntity;

    /** Set for audiobook listening progress; behaves like {@link #episodeEntity}. */
    @Getter(onMethod = @__(@JsonBackReference))
    @ManyToOne(optional = true)
    private ChapterEntity chapterEntity;

    /** Set for epub reading progress; see {@link #readingLocation}. */
    @Getter(onMethod = @__(@JsonBackReference))
    @ManyToOne(optional = true)
    private BookEntity bookEntity;

    /** Set for podcast listening progress; behaves like {@link #episodeEntity}. */
    @Getter(onMethod = @__(@JsonBackReference))
    @ManyToOne(optional = true)
    private PodcastEpisodeEntity podcastEpisodeEntity;

    @Column(nullable = false)
    private boolean watched;

    private long progressInMilliseconds;

    /** Epub reading position as an epubcfi string; only set on reading rows. */
    private String readingLocation;

    /** Epub reading progress 0.0–1.0; only set on reading rows. */
    private Double readingProgress;
}
