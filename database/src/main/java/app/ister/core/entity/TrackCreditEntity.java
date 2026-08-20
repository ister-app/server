package app.ister.core.entity;

import app.ister.core.enums.TrackCreditType;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;

/**
 * Links a person to a track as its performer. The primary artist is also kept on
 * {@link TrackEntity#getPersonEntity()}; featured guests only exist here.
 */
@Entity
@Table(uniqueConstraints = @UniqueConstraint(columnNames = {"trackEntityId", "personEntityId"}))
@Getter
@SuperBuilder
@NoArgsConstructor(access = AccessLevel.PUBLIC)
public class TrackCreditEntity extends BaseEntity {

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    private TrackEntity trackEntity;

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    private PersonEntity personEntity;

    @Setter
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private TrackCreditType creditType;

    @Setter
    @Column(nullable = false)
    private int position;
}
