package app.ister.core.entitiy;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

import java.util.List;

@Entity
@Table(uniqueConstraints = @UniqueConstraint(columnNames = {"showEntityId", "number"}))
@Getter
@SuperBuilder
@NoArgsConstructor(access = AccessLevel.PUBLIC)
public class SeasonEntity extends BaseEntity {

    @ManyToOne(optional = false)
    private ShowEntity showEntity;

    @Column(nullable = false)
    private int number;

    @OneToMany(fetch = FetchType.LAZY, cascade = CascadeType.ALL, mappedBy = "seasonEntity")
    @OrderBy("number ASC")
    private List<EpisodeEntity> episodeEntities;
}
