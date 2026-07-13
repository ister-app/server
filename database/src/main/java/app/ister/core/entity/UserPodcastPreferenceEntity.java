package app.ister.core.entity;

import app.ister.core.enums.SortingOrder;
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
 * A user's settings for one podcast. Currently only the episode sort order: DESCENDING (newest
 * first, the default when no row exists) or ASCENDING for a serial podcast you want to hear
 * chronologically. At most one row per (user, podcast) — enforced by a unique index in Flyway.
 */
@Entity
@Table(name = "user_podcast_preference")
@Getter
@Setter
@SuperBuilder
@NoArgsConstructor(access = AccessLevel.PUBLIC)
public class UserPodcastPreferenceEntity extends BaseEntity {

    @ManyToOne(optional = false)
    private UserEntity userEntity;

    @Getter(onMethod = @__(@JsonBackReference))
    @ManyToOne(optional = false)
    private PodcastEntity podcastEntity;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private SortingOrder episodeOrder;
}
