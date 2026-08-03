package app.ister.core.entity;

import app.ister.core.enums.MediaType;
import com.fasterxml.jackson.annotation.JsonBackReference;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * One entry of a MANUAL playlist: a playable media item of the playlist's library. Exactly one
 * media column is set, matching {@code type}. Uses the same gap-based position scheme as
 * {@link PlayQueueItemEntity}; note that a BOOK library's playlist stores whole books — the
 * play queue expands them to chapters.
 */
@Entity
@Getter
@Setter
@SuperBuilder
@NoArgsConstructor(access = AccessLevel.PUBLIC)
public class PlaylistItemEntity extends BaseEntity implements Positioned {

    @Getter(onMethod = @__(@JsonBackReference))
    @ManyToOne(optional = false)
    private PlaylistEntity playlistEntity;

    // Gap-based ordering column
    @Column(nullable = false, precision = 20, scale = 10)
    private BigDecimal position;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private MediaType type;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "movie_entity_id", referencedColumnName = "id", insertable = false, updatable = false)
    private MovieEntity movieEntity;

    @Column(name = "movie_entity_id")
    private UUID movieEntityId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "episode_entity_id", referencedColumnName = "id", insertable = false, updatable = false)
    private EpisodeEntity episodeEntity;

    @Column(name = "episode_entity_id")
    private UUID episodeEntityId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "track_entity_id", referencedColumnName = "id", insertable = false, updatable = false)
    private TrackEntity trackEntity;

    @Column(name = "track_entity_id")
    private UUID trackEntityId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "book_entity_id", referencedColumnName = "id", insertable = false, updatable = false)
    private BookEntity bookEntity;

    @Column(name = "book_entity_id")
    private UUID bookEntityId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "podcast_episode_entity_id", referencedColumnName = "id", insertable = false, updatable = false)
    private PodcastEpisodeEntity podcastEpisodeEntity;

    @Column(name = "podcast_episode_entity_id")
    private UUID podcastEpisodeEntityId;

    /** The id of whichever media column is set. */
    public UUID getMediaId() {
        if (movieEntityId != null) {
            return movieEntityId;
        }
        if (episodeEntityId != null) {
            return episodeEntityId;
        }
        if (trackEntityId != null) {
            return trackEntityId;
        }
        if (bookEntityId != null) {
            return bookEntityId;
        }
        return podcastEpisodeEntityId;
    }
}
