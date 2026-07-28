-- The library Discover top-lists aggregate a user's watch rows per media column. V29 added the
-- partial index for tracks; these mirror it for the other playable media so the per-user joins
-- stay indexed. Ratings already have per-type partial (unique) indexes from V12/V13/V14.

CREATE INDEX IF NOT EXISTS watch_status_entity_user_movie_idx
    ON watch_status_entity (user_entity_id, movie_entity_id)
    WHERE movie_entity_id IS NOT NULL;

CREATE INDEX IF NOT EXISTS watch_status_entity_user_episode_idx
    ON watch_status_entity (user_entity_id, episode_entity_id)
    WHERE episode_entity_id IS NOT NULL;

CREATE INDEX IF NOT EXISTS watch_status_entity_user_podcast_episode_idx
    ON watch_status_entity (user_entity_id, podcast_episode_entity_id)
    WHERE podcast_episode_entity_id IS NOT NULL;

CREATE INDEX IF NOT EXISTS watch_status_entity_user_book_idx
    ON watch_status_entity (user_entity_id, book_entity_id)
    WHERE book_entity_id IS NOT NULL;

CREATE INDEX IF NOT EXISTS watch_status_entity_user_chapter_idx
    ON watch_status_entity (user_entity_id, chapter_entity_id)
    WHERE chapter_entity_id IS NOT NULL;
