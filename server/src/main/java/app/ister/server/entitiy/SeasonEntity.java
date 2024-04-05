package app.ister.server.entitiy;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

@Entity
@Table(uniqueConstraints = @UniqueConstraint(columnNames = {"showEntityId", "number"}))
@Getter
@SuperBuilder
@NoArgsConstructor(access = AccessLevel.PUBLIC)
public class SeasonEntity extends BaseEntity {

    @ManyToOne(optional=false)
    private ShowEntity showEntity;

    @Column(nullable = false)
    private int number;
}
