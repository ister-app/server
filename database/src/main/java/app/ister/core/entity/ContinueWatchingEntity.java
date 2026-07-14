package app.ister.core.entity;

import app.ister.core.enums.MediaType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;

import java.time.Instant;
import java.util.UUID;

/**
 * One entry of a user's "continue watching" list: a show they are midway through, a movie they
 * started, an audiobook, an epub, a podcast episode. Derived state — the truth lives in
 * {@link WatchStatusEntity} — kept up to date by app.ister.core.service.ContinueWatchingService.
 *
 * <p>The entry is keyed by its container ({@link #groupId}: show / movie / book / podcast episode)
 * and points at the item to resume with. All target references null means the container has nothing
 * left to continue with; the row survives so that a newly scanned episode can put the show back in
 * the list.
 */
// The unique key on (user_entity_id, entry_type, group_id) lives in the migration, not here: it is the
// conflict target of ContinueWatchingRepository.upsert, and declaring it a second time makes Hibernate
// add a redundant duplicate of it on dev databases (ddl-auto=update).
@Entity
@Table(name = "continue_watching")
@Getter
@Setter
@SuperBuilder
@NoArgsConstructor(access = AccessLevel.PUBLIC)
public class ContinueWatchingEntity extends BaseEntity {

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    private UserEntity userEntity;

    /** Which of the target references is meaningful, and what the GraphQL RecentlyWatched type reports. */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 31)
    private MediaType entryType;

    /** Show / movie / book / podcast episode id: the thing this entry deduplicates on. */
    @Column(nullable = false)
    private UUID groupId;

    // Lazy, and batch-fetched (hibernate.default_batch_fetch_size): hydrating a whole list costs
    // one query per media type, not one per row.
    @ManyToOne(fetch = FetchType.LAZY)
    private EpisodeEntity episodeEntity;

    @ManyToOne(fetch = FetchType.LAZY)
    private MovieEntity movieEntity;

    @ManyToOne(fetch = FetchType.LAZY)
    private ChapterEntity chapterEntity;

    @ManyToOne(fetch = FetchType.LAZY)
    private BookEntity bookEntity;

    @ManyToOne(fetch = FetchType.LAZY)
    private PodcastEpisodeEntity podcastEpisodeEntity;

    /** Sort key of the list: when the user last played something in this container. */
    @Column(nullable = false)
    private Instant lastWatched;
}
