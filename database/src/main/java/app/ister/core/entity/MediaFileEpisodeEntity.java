package app.ister.core.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;

import java.util.UUID;

/**
 * Links one episode of a multi-episode media file (s04e06-e07.mkv) to its time slice in that file.
 *
 * <p>Rows exist only for files that span multiple episodes — one row per contained episode,
 * including the first ({@code startInMilliseconds} 0). {@code MediaFileEntity.episodeEntity} keeps
 * pointing at the first episode, so single-episode files (no rows here) behave exactly as before.
 */
// Plain UUID columns instead of @ManyToOne: every consumer is a RabbitMQ listener or batch
// resolver where lazy navigation would throw (no Hibernate session on listener threads).
// The unique key on (media_file_entity_id, episode_entity_id) lives in the migration.
@Entity
@Getter
@Setter
@SuperBuilder
@NoArgsConstructor(access = AccessLevel.PUBLIC)
public class MediaFileEpisodeEntity extends BaseEntity {

    @Column(nullable = false)
    private UUID mediaFileEntityId;

    @Column(nullable = false)
    private UUID episodeEntityId;

    /** 0-based order of this episode within the file. */
    @Column(nullable = false)
    private int partNumber;

    @Column(nullable = false)
    private long startInMilliseconds;

    /** 0 while analysis has not yet computed the episode boundaries. */
    @Column(nullable = false)
    private long durationInMilliseconds;
}
