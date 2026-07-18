package app.ister.core.entity;

import app.ister.core.enums.MetadataSource;
import com.fasterxml.jackson.annotation.JsonBackReference;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;

import java.time.LocalDate;

@Entity
@Getter
@SuperBuilder
@NoArgsConstructor(access = AccessLevel.PUBLIC)
public class MetadataEntity extends BaseEntity {
    @Getter(onMethod = @__(@JsonBackReference))
    @ManyToOne
    private MovieEntity movieEntity;

    @Getter(onMethod = @__(@JsonBackReference))
    @ManyToOne
    private ShowEntity showEntity;

    @Getter(onMethod = @__(@JsonBackReference))
    @ManyToOne
    private SeasonEntity seasonEntity;

    @Getter(onMethod = @__(@JsonBackReference))
    @ManyToOne
    private EpisodeEntity episodeEntity;

    @Getter(onMethod = @__(@JsonBackReference))
    @ManyToOne
    private PersonEntity personEntity;

    @Getter(onMethod = @__(@JsonBackReference))
    @ManyToOne
    private AlbumEntity albumEntity;

    @Getter(onMethod = @__(@JsonBackReference))
    @ManyToOne
    private TrackEntity trackEntity;

    @Getter(onMethod = @__(@JsonBackReference))
    @ManyToOne
    private BookEntity bookEntity;

    @Getter(onMethod = @__(@JsonBackReference))
    @ManyToOne
    private ChapterEntity chapterEntity;

    @Getter(onMethod = @__(@JsonBackReference))
    @ManyToOne
    private PodcastEntity podcastEntity;

    @Getter(onMethod = @__(@JsonBackReference))
    @ManyToOne
    private PodcastEpisodeEntity podcastEpisodeEntity;

    /** Series-level metadata (comic series description from Wikipedia). */
    @Getter(onMethod = @__(@JsonBackReference))
    @ManyToOne
    private SeriesEntity seriesEntity;

    /** Provenance/dedup URI (e.g. "wikipedia://https://en.wikipedia..."); no natural length bound. */
    @Setter
    @Column(columnDefinition = "text")
    private String sourceUri;

    /** Normalized provider for attribution; derived from sourceUri unless a writer sets it
     * explicitly (person bios: the text is usually Wikipedia's while sourceUri stays the
     * musicbrainz/openlibrary dedup key). */
    @Setter
    @Enumerated(EnumType.STRING)
    private MetadataSource source;

    @PrePersist
    @PreUpdate
    private void deriveSource() {
        if (source == null) {
            source = MetadataSource.fromSourceUri(sourceUri).orElse(null);
        }
    }

    // https://en.wikipedia.org/wiki/ISO_639-3
    @Setter
    private String language;

    @Setter
    private String title;
    @Setter
    @Column(columnDefinition = "text")
    private String description;
    @Setter
    private LocalDate released;
    @Setter
    private String genre;
}
