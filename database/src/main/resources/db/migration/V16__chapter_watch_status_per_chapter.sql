-- Audiobook progress used to be keyed by play queue item, so every new play queue over the same
-- book produced a fresh watch-status row per chapter. Chapter.watchStatus is a list in the API and
-- clients read the first row, which was therefore arbitrary. Reading progress already avoids this
-- (one row per user per book, with the book id doubling as the play queue item id); chapters now
-- do the same, which is also what lets the reader web app write a listening position without a
-- play queue.

-- Collapse duplicates: keep the furthest progress per (user, chapter), and watched if any row was.
WITH merged AS (
    SELECT user_entity_id,
           chapter_entity_id,
           MAX(COALESCE(progress_in_milliseconds, 0)) AS progress_in_milliseconds,
           bool_or(watched)                           AS watched,
           MIN(id::text)::uuid                        AS keep_id
    FROM watch_status_entity
    WHERE chapter_entity_id IS NOT NULL
    GROUP BY user_entity_id, chapter_entity_id
)
UPDATE watch_status_entity w
SET progress_in_milliseconds = merged.progress_in_milliseconds,
    watched                  = merged.watched,
    play_queue_item_id       = w.chapter_entity_id
FROM merged
WHERE w.id = merged.keep_id;

DELETE FROM watch_status_entity w
WHERE w.chapter_entity_id IS NOT NULL
  AND w.play_queue_item_id IS DISTINCT FROM w.chapter_entity_id;

CREATE UNIQUE INDEX IF NOT EXISTS watch_status_entity_user_chapter_uq
    ON watch_status_entity (user_entity_id, chapter_entity_id) WHERE chapter_entity_id IS NOT NULL;

-- An epubcfi is only meaningful inside the epub file it came from: a book can have both a plain and
-- a read-aloud ("(karaoke)") edition. Remember which one produced the stored reading location so a
-- reader opening the other edition can fall back to deriving the position from the audio progress.
ALTER TABLE watch_status_entity
    ADD COLUMN IF NOT EXISTS reading_location_media_file_id UUID REFERENCES media_file_entity(id) ON DELETE SET NULL;
