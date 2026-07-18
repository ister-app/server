package app.ister.core.entity;

import app.ister.core.enums.ImageType;
import app.ister.core.enums.MetadataSource;
import com.fasterxml.jackson.annotation.JsonBackReference;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

import java.util.UUID;

@Entity
@Table(uniqueConstraints = @UniqueConstraint(columnNames = {"directoryEntityId", "path"}))
@Data
@EqualsAndHashCode(callSuper = true)
@SuperBuilder
@NoArgsConstructor(access = AccessLevel.PUBLIC)
public class ImageEntity extends FileFromPathEntity {

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ImageType type;

    // https://en.wikipedia.org/wiki/ISO_639-3
    private String language;

    /** Provenance/dedup URI (e.g. "wikipedia://https://upload..."); no natural length bound. */
    @Column(columnDefinition = "text")
    private String sourceUri;

    /** Normalized provider for attribution; derived from sourceUri at persist time. */
    @Enumerated(EnumType.STRING)
    private MetadataSource source;

    @PrePersist
    @PreUpdate
    private void deriveSource() {
        if (source == null) {
            source = MetadataSource.fromSourceUri(sourceUri).orElse(null);
        }
    }

    private String blurHash;

    @Getter(onMethod = @__(@JsonBackReference))
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "movie_entity_id", referencedColumnName = "id", insertable = false, updatable = false)
    private MovieEntity movieEntity;

    @Column(name = "movie_entity_id")
    private UUID movieEntityId; // Store movie entity ID

    public void setMovieEntity(MovieEntity movieEntity) {
        this.movieEntity = movieEntity;
        this.movieEntityId = (movieEntity != null) ? movieEntity.getId() : null;
    }

    @Getter(onMethod = @__(@JsonBackReference))
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "show_entity_id", referencedColumnName = "id", insertable = false, updatable = false)
    private ShowEntity showEntity;

    @Column(name = "show_entity_id")
    private UUID showEntityId; // Store show entity ID

    public void setShowEntity(ShowEntity showEntity) {
        this.showEntity = showEntity;
        this.showEntityId = (showEntity != null) ? showEntity.getId() : null;
    }

    @Getter(onMethod = @__(@JsonBackReference))
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "season_entity_id", referencedColumnName = "id", insertable = false, updatable = false)
    private SeasonEntity seasonEntity;

    @Column(name = "season_entity_id")
    private UUID seasonEntityId; // Store season entity ID

    public void setSeasonEntity(SeasonEntity seasonEntity) {
        this.seasonEntity = seasonEntity;
        this.seasonEntityId = (seasonEntity != null) ? seasonEntity.getId() : null;
    }

    @Getter(onMethod = @__(@JsonBackReference))
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "episode_entity_id", referencedColumnName = "id", insertable = false, updatable = false)
    private EpisodeEntity episodeEntity;

    @Column(name = "episode_entity_id")
    private UUID episodeEntityId; // Store episode entity ID

    public void setEpisodeEntity(EpisodeEntity episodeEntity) {
        this.episodeEntity = episodeEntity;
        this.episodeEntityId = (episodeEntity != null) ? episodeEntity.getId() : null;
    }

    @Getter(onMethod = @__(@JsonBackReference))
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "person_entity_id", referencedColumnName = "id", insertable = false, updatable = false)
    private PersonEntity personEntity;

    @Column(name = "person_entity_id")
    private UUID personEntityId;

    public void setPersonEntity(PersonEntity personEntity) {
        this.personEntity = personEntity;
        this.personEntityId = (personEntity != null) ? personEntity.getId() : null;
    }

    @Getter(onMethod = @__(@JsonBackReference))
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "album_entity_id", referencedColumnName = "id", insertable = false, updatable = false)
    private AlbumEntity albumEntity;

    @Column(name = "album_entity_id")
    private UUID albumEntityId;

    public void setAlbumEntity(AlbumEntity albumEntity) {
        this.albumEntity = albumEntity;
        this.albumEntityId = (albumEntity != null) ? albumEntity.getId() : null;
    }

    @Getter(onMethod = @__(@JsonBackReference))
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "book_entity_id", referencedColumnName = "id", insertable = false, updatable = false)
    private BookEntity bookEntity;

    @Column(name = "book_entity_id")
    private UUID bookEntityId;

    public void setBookEntity(BookEntity bookEntity) {
        this.bookEntity = bookEntity;
        this.bookEntityId = (bookEntity != null) ? bookEntity.getId() : null;
    }

    @Getter(onMethod = @__(@JsonBackReference))
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "series_entity_id", referencedColumnName = "id", insertable = false, updatable = false)
    private SeriesEntity seriesEntity;

    @Column(name = "series_entity_id")
    private UUID seriesEntityId;

    public void setSeriesEntity(SeriesEntity seriesEntity) {
        this.seriesEntity = seriesEntity;
        this.seriesEntityId = (seriesEntity != null) ? seriesEntity.getId() : null;
    }

    @Getter(onMethod = @__(@JsonBackReference))
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "podcast_entity_id", referencedColumnName = "id", insertable = false, updatable = false)
    private PodcastEntity podcastEntity;

    @Column(name = "podcast_entity_id")
    private UUID podcastEntityId;

    public void setPodcastEntity(PodcastEntity podcastEntity) {
        this.podcastEntity = podcastEntity;
        this.podcastEntityId = (podcastEntity != null) ? podcastEntity.getId() : null;
    }

    @Getter(onMethod = @__(@JsonBackReference))
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "podcast_episode_entity_id", referencedColumnName = "id", insertable = false, updatable = false)
    private PodcastEpisodeEntity podcastEpisodeEntity;

    @Column(name = "podcast_episode_entity_id")
    private UUID podcastEpisodeEntityId;

    public void setPodcastEpisodeEntity(PodcastEpisodeEntity podcastEpisodeEntity) {
        this.podcastEpisodeEntity = podcastEpisodeEntity;
        this.podcastEpisodeEntityId = (podcastEpisodeEntity != null) ? podcastEpisodeEntity.getId() : null;
    }
}
