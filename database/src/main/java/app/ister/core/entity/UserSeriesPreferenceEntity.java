package app.ister.core.entity;

import app.ister.core.enums.ReadingDirection;
import com.fasterxml.jackson.annotation.JsonBackReference;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;

/**
 * A user's reading direction override for one comic/manga series. No row means the series'
 * detected default applies (RTL for manga, else LTR). At most one row per (user, series) —
 * enforced by a unique index in Flyway.
 */
@Entity
@Table(name = "user_series_preference")
@Getter
@Setter
@SuperBuilder
@NoArgsConstructor(access = AccessLevel.PUBLIC)
public class UserSeriesPreferenceEntity extends BaseEntity {

    @ManyToOne(optional = false)
    private UserEntity userEntity;

    @Getter(onMethod = @__(@JsonBackReference))
    @ManyToOne(optional = false)
    private SeriesEntity seriesEntity;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ReadingDirection readingDirection;
}
