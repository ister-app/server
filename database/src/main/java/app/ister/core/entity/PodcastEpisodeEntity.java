package app.ister.core.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;

import java.time.Instant;
import java.util.List;

/**
 * One episode of a {@link PodcastEntity}, parsed from the feed. Deduplicated on the feed item's
 * guid (falling back to the enclosure URL for feeds without guids). The audio only exists locally
 * once it has been downloaded to the cache directory ({@link MediaFileEntity} present); from that
 * moment it streams through the same audio-only HLS path as tracks and chapters.
 */
@Entity
@Table(uniqueConstraints = @UniqueConstraint(columnNames = {"podcastEntityId", "guid"}))
@Getter
@Setter
@SuperBuilder
@NoArgsConstructor(access = AccessLevel.PUBLIC)
public class PodcastEpisodeEntity extends BaseEntity {

    @ManyToOne(optional = false)
    private PodcastEntity podcastEntity;

    @Column(nullable = false, length = 2048)
    private String guid;

    private Instant publishedAt;

    @Column(nullable = false, length = 2048)
    private String enclosureUrl;

    private String enclosureType;

    /** Duration hint from itunes:duration, until ffprobe measures the downloaded file. */
    private long durationHintInMilliseconds;

    private Integer episodeNumber;

    private Integer seasonNumber;

    @OneToMany(cascade = CascadeType.ALL, mappedBy = "podcastEpisodeEntity")
    private List<MediaFileEntity> mediaFileEntities;

    @OneToMany(cascade = CascadeType.ALL, mappedBy = "podcastEpisodeEntity")
    private List<MetadataEntity> metadataEntities;

    @OneToMany(fetch = FetchType.LAZY, cascade = CascadeType.ALL, mappedBy = "podcastEpisodeEntity")
    private List<ImageEntity> imageEntities;
}
