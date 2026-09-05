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
public class ShowEntity extends BaseEntity {

    @ManyToOne(optional = false)
    private LibraryEntity libraryEntity;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false)
    private int releaseYear;

    @OneToMany(fetch = FetchType.LAZY, cascade = CascadeType.ALL, mappedBy = "showEntity")
    @OrderBy("number ASC")
    private List<SeasonEntity> seasonEntities;

    @OneToMany(fetch = FetchType.LAZY, cascade = CascadeType.ALL, mappedBy = "showEntity")
    @OrderBy("id ASC")
    private List<ImageEntity> imageEntities;

    @OneToMany(fetch = FetchType.LAZY, cascade = CascadeType.ALL, mappedBy = "showEntity")
    private List<MetadataEntity> metadataEntities;

    // Language-independent TMDB enrichment; null until the show has been analyzed.
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
    @Setter
    @Column(length = 16)
    private String contentRating;
    @Setter
    @Column(length = 32)
    private String status;
    @Setter
    @Column(columnDefinition = "text")
    private String homepage;
    /** Comma-separated network names. */
    @Setter
    @Column(columnDefinition = "text")
    private String networks;
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
