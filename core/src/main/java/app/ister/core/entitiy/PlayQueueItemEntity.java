package app.ister.core.entitiy;


import app.ister.core.enums.MediaType;
import com.fasterxml.jackson.annotation.JsonBackReference;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;

import java.math.BigDecimal;
import java.util.UUID;

@Entity
@Getter
@Setter
@SuperBuilder
@NoArgsConstructor(access = AccessLevel.PUBLIC)
public class PlayQueueItemEntity extends BaseEntity {

    @ManyToOne(optional = false)
    private PlayQueueEntity playQueueEntity;

    // Gap‑based ordering column
    @Column(nullable = false, precision = 20, scale = 10)
    private BigDecimal position;

    @Column(nullable = false)
    private MediaType type;

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
    @JoinColumn(name = "episode_entity_id", referencedColumnName = "id", insertable = false, updatable = false)
    private EpisodeEntity episodeEntity;

    @Column(name = "episode_entity_id")
    private UUID episodeEntityId; // Store episode entity ID

    public void setEpisodeEntity(EpisodeEntity episodeEntity) {
        this.episodeEntity = episodeEntity;
        this.episodeEntityId = (episodeEntity != null) ? episodeEntity.getId() : null;
    }
}
