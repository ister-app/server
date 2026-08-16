package app.ister.core.entity;

import app.ister.core.enums.SegmentType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;

import java.util.UUID;

/**
 * A detected intro or outro (credits) range of a media file, in absolute file time.
 *
 * <p>Written by season-wide audio-fingerprint detection on the disk module. For a multi-episode
 * file each contained episode gets its own rows, disambiguated by {@code episodeEntityId}; for
 * single-episode files that column is null. "Detection ran but found nothing" is not a row here —
 * it is {@code MediaFileEntity.segmentDetectorVersion} being set.
 */
// Plain UUID columns instead of @ManyToOne: consumers are RabbitMQ listeners and batch
// resolvers where lazy navigation would throw (no Hibernate session on listener threads).
@Entity
@Getter
@Setter
@SuperBuilder
@NoArgsConstructor(access = AccessLevel.PUBLIC)
public class MediaFileSegmentEntity extends BaseEntity {

    @Column(nullable = false)
    private UUID mediaFileEntityId;

    /** Which episode's intro/outro this is, for multi-episode files; null = the file's only episode. */
    private UUID episodeEntityId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private SegmentType type;

    @Column(nullable = false)
    private long startInMilliseconds;

    @Column(nullable = false)
    private long endInMilliseconds;
}
