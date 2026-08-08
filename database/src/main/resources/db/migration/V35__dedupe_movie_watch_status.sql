-- The movie watch-status lookup reused the episode-scoped query with a null episode; a
-- derived query binds null as `= null`, which matches nothing, so every ~10s progress
-- heartbeat inserted a fresh row instead of updating the existing one. The lookup is
-- fixed in code; this collapses the rows it left behind and blocks the pattern for good.

-- Collapse duplicates: keep the furthest progress per (user, play queue item), and
-- watched if any row was.
WITH merged AS (
    SELECT user_entity_id,
           play_queue_item_id,
           MAX(COALESCE(progress_in_milliseconds, 0)) AS progress_in_milliseconds,
           bool_or(watched)                           AS watched,
           MIN(id::text)::uuid                        AS keep_id
    FROM watch_status_entity
    WHERE movie_entity_id IS NOT NULL
    GROUP BY user_entity_id, play_queue_item_id
)
UPDATE watch_status_entity w
SET progress_in_milliseconds = merged.progress_in_milliseconds,
    watched                  = merged.watched
FROM merged
WHERE w.id = merged.keep_id;

DELETE FROM watch_status_entity w
WHERE w.movie_entity_id IS NOT NULL
  AND w.id NOT IN (SELECT MIN(id::text)::uuid
                   FROM watch_status_entity
                   WHERE movie_entity_id IS NOT NULL
                   GROUP BY user_entity_id, play_queue_item_id);

CREATE UNIQUE INDEX IF NOT EXISTS watch_status_entity_user_queue_item_movie_uq
    ON watch_status_entity (user_entity_id, play_queue_item_id)
    WHERE movie_entity_id IS NOT NULL;
