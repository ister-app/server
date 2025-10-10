package app.ister.server.entitiy;

import app.ister.server.enums.ImageType;
import com.fasterxml.jackson.annotation.JsonBackReference;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Data;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

import java.util.UUID;

@Entity
@Table(uniqueConstraints = @UniqueConstraint(columnNames = {"directoryEntityId", "path"}))
@Data
@SuperBuilder
@NoArgsConstructor(access = AccessLevel.PUBLIC)
public class ImageEntity extends FileFromPathEntity {

    @Column(nullable = false)
    private ImageType type;

    // https://en.wikipedia.org/wiki/ISO_639-3
    private String language;

    private String sourceUri;

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
}
