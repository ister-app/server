-- Track plays are recorded as watch-status rows, one per played play-queue item, exactly
-- like episodes and movies. A play count is then COUNT(*) per (user, track) and the
-- row's date_updated doubles as "last played".
ALTER TABLE watch_status_entity
    ADD COLUMN IF NOT EXISTS track_entity_id UUID REFERENCES track_entity(id);

-- Recreate the watch-status unique constraint to include the new track column. Drops ALL
-- unique constraints first (see V14: pre-Flyway databases can carry a legacy uk… constraint
-- next to the named one, and dropping only the first breaks the ADD below).
DO $$
DECLARE
    con text;
BEGIN
    FOR con IN
        SELECT conname FROM pg_constraint
        WHERE conrelid = 'watch_status_entity'::regclass AND contype = 'u'
    LOOP
        EXECUTE format('ALTER TABLE watch_status_entity DROP CONSTRAINT %I', con);
    END LOOP;
END $$;

ALTER TABLE watch_status_entity
    ADD CONSTRAINT watch_status_entity_media_item_key
    UNIQUE (play_queue_item_id, user_entity_id, movie_entity_id, episode_entity_id, chapter_entity_id, book_entity_id, podcast_episode_entity_id, track_entity_id);

-- The per-artist top lists and play-count lookups aggregate over a user's track rows.
CREATE INDEX IF NOT EXISTS watch_status_entity_user_track_idx
    ON watch_status_entity (user_entity_id, track_entity_id)
    WHERE track_entity_id IS NOT NULL;
