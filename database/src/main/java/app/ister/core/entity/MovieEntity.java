package app.ister.core.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;

import java.math.BigDecimal;
import java.util.List;

@Entity
@Table(uniqueConstraints = @UniqueConstraint(columnNames = {"libraryEntityId", "name", "releaseYear"}))
@Getter
@SuperBuilder
@NoArgsConstructor(access = AccessLevel.PUBLIC)
public class MovieEntity extends BaseEntity {
    @ManyToOne(optional = false)
    private LibraryEntity libraryEntity;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false)
    private int releaseYear;

    @OneToMany(cascade = CascadeType.ALL, mappedBy = "movieEntity")
    private List<MediaFileEntity> mediaFileEntities;

    @OneToMany(cascade = CascadeType.ALL, mappedBy = "movieEntity")
    private List<ImageEntity> imagesEntities;

    @OneToMany(cascade = CascadeType.ALL, mappedBy = "movieEntity")
    private List<MetadataEntity> metadataEntities;

    @OneToMany(cascade = CascadeType.ALL, mappedBy = "movieEntity")
    @OrderBy("dateUpdated DESC")
    private List<WatchStatusEntity> watchStatusEntities;

    // Language-independent TMDB enrichment; null until the movie has been analyzed.
    @Setter
    private Integer tmdbId;
    @Setter
    @Column(length = 16)
    private String imdbId;
    @Setter
    @Column(precision = 3, scale = 1)
    private BigDecimal voteAverage;
    @Setter
    private Integer voteCount;
    /** Runtime in minutes. */
    @Setter
    private Integer runtime;
    @Setter
    @Column(length = 16)
    private String contentRating;
    @Setter
    @Column(length = 32)
    private String status;
    @Setter
    @Column(columnDefinition = "text")
    private String homepage;
    @Setter
    private Integer collectionTmdbId;
    @Setter
    private String collectionName;
    /** Comma-separated production company names. */
    @Setter
    @Column(columnDefinition = "text")
    private String studios;
    /** Comma-separated ISO 3166-1 country codes. */
    @Setter
    @Column(length = 64)
    private String originCountry;
    /** Comma-separated TMDB keywords (English only). */
    @Setter
    @Column(columnDefinition = "text")
    private String keywords;
    @Setter
    @Column(length = 32)
    private String trailerKey;
    @Setter
    @Column(length = 16)
    private String trailerSite;
}
