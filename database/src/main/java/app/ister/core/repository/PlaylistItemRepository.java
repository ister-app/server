package app.ister.core.repository;

import app.ister.core.entity.PlaylistItemEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface PlaylistItemRepository extends JpaRepository<PlaylistItemEntity, UUID> {

    /** A page of a MANUAL playlist's media ids in play order (whichever media column is set). */
    @Query(value = """
            SELECT COALESCE(i.movie_entity_id, i.episode_entity_id, i.track_entity_id, i.book_entity_id, i.podcast_episode_entity_id)
            FROM playlist_item_entity i
            WHERE i.playlist_entity_id = :playlistId
            ORDER BY i.position, i.id
            LIMIT :limit OFFSET :offset""", nativeQuery = true)
    List<UUID> findMediaIdsForPlaylistOrdered(@Param("playlistId") UUID playlistId, @Param("limit") int limit, @Param("offset") int offset);

    /**
     * A page of a MANUAL playlist's media ids in a deterministic shuffled order derived from the
     * seed. Shuffles by item id, so a media item added twice occupies two independent slots.
     */
    @Query(value = """
            SELECT COALESCE(i.movie_entity_id, i.episode_entity_id, i.track_entity_id, i.book_entity_id, i.podcast_episode_entity_id)
            FROM playlist_item_entity i
            WHERE i.playlist_entity_id = :playlistId
              AND COALESCE(i.movie_entity_id, i.episode_entity_id, i.track_entity_id, i.book_entity_id, i.podcast_episode_entity_id) <> :excludeId
            ORDER BY md5(i.id::text || :seed), i.id
            LIMIT :limit OFFSET :offset""", nativeQuery = true)
    List<UUID> findMediaIdsForPlaylistShuffled(@Param("playlistId") UUID playlistId, @Param("seed") String seed, @Param("excludeId") UUID excludeId, @Param("limit") int limit, @Param("offset") int offset);

    long countByPlaylistEntityId(UUID playlistId);
}
