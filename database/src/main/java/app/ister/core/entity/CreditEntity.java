package app.ister.core.entity;

import app.ister.core.enums.CreditType;
import com.fasterxml.jackson.annotation.JsonBackReference;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;

import java.util.UUID;

/**
 * Links a person to a movie, show or episode as a cast member.
 * Exactly one of movie/show/episode is set.
 */
@Entity
@Getter
@SuperBuilder
@NoArgsConstructor(access = AccessLevel.PUBLIC)
public class CreditEntity extends BaseEntity {

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    private PersonEntity personEntity;

    @Getter(onMethod = @__(@JsonBackReference))
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "movie_entity_id", referencedColumnName = "id", insertable = false, updatable = false)
    private MovieEntity movieEntity;

    @Column(name = "movie_entity_id")
    private UUID movieEntityId;

    public void setMovieEntity(MovieEntity movieEntity) {
        this.movieEntity = movieEntity;
        this.movieEntityId = (movieEntity != null) ? movieEntity.getId() : null;
    }

    @Getter(onMethod = @__(@JsonBackReference))
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "show_entity_id", referencedColumnName = "id", insertable = false, updatable = false)
    private ShowEntity showEntity;

    @Column(name = "show_entity_id")
    private UUID showEntityId;

    public void setShowEntity(ShowEntity showEntity) {
        this.showEntity = showEntity;
        this.showEntityId = (showEntity != null) ? showEntity.getId() : null;
    }

    @Getter(onMethod = @__(@JsonBackReference))
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "episode_entity_id", referencedColumnName = "id", insertable = false, updatable = false)
    private EpisodeEntity episodeEntity;

    @Column(name = "episode_entity_id")
    private UUID episodeEntityId;

    public void setEpisodeEntity(EpisodeEntity episodeEntity) {
        this.episodeEntity = episodeEntity;
        this.episodeEntityId = (episodeEntity != null) ? episodeEntity.getId() : null;
    }

    @Setter
    @Column(length = 512)
    private String characterName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private CreditType creditType;

    @Setter
    private Integer castOrder;

    @Column(length = 64)
    private String tmdbCreditId;
}
