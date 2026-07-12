package app.ister.core.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;

import java.time.Instant;
import java.util.List;

/**
 * A podcast subscription: an RSS feed the server refreshes periodically. Unlike the other library
 * types there is no library directory on disk — episodes are downloaded to the node's cache
 * directory on demand. Subscriptions are server-wide; listening progress is per user via
 * {@link WatchStatusEntity}. The author is a plain string (a publisher), deliberately not a
 * {@link PersonEntity}.
 */
@Entity
@Table(uniqueConstraints = @UniqueConstraint(columnNames = {"feedUrl"}))
@Getter
@Setter
@SuperBuilder
@NoArgsConstructor(access = AccessLevel.PUBLIC)
public class PodcastEntity extends BaseEntity {

    @ManyToOne(optional = false)
    private LibraryEntity libraryEntity;

    @Column(nullable = false, length = 2048)
    private String feedUrl;

    @Column(nullable = false)
    private String title;

    private String author;

    // https://en.wikipedia.org/wiki/ISO_639-3
    private String language;

    /** Unsubscribing deactivates instead of deleting, so listening history survives. */
    @Column(nullable = false)
    private boolean active;

    private Instant lastRefreshedAt;

    /** ETag/Last-Modified of the last fetch, for conditional GETs on refresh. */
    private String feedEtag;
    private String feedLastModified;

    @OneToMany(fetch = FetchType.LAZY, cascade = CascadeType.ALL, mappedBy = "podcastEntity")
    private List<PodcastEpisodeEntity> episodeEntities;

    @OneToMany(fetch = FetchType.LAZY, cascade = CascadeType.ALL, mappedBy = "podcastEntity")
    private List<ImageEntity> imageEntities;

    @OneToMany(fetch = FetchType.LAZY, cascade = CascadeType.ALL, mappedBy = "podcastEntity")
    private List<MetadataEntity> metadataEntities;
}
