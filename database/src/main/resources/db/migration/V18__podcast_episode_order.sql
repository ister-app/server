-- Per-user, per-podcast episode sort order. Stored server-side (not on the client) so the
-- choice follows the user across phone, desktop and Android Auto. A podcast without a row
-- falls back to DESCENDING (newest first), which is the order the list has always had.
CREATE TABLE IF NOT EXISTS user_podcast_preference (
    id                   UUID NOT NULL PRIMARY KEY,
    date_created         TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    date_updated         TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    user_entity_id       UUID NOT NULL REFERENCES user_entity(id),
    podcast_entity_id    UUID NOT NULL REFERENCES podcast_entity(id),
    episode_order        VARCHAR(16) NOT NULL
);

-- At most one preference row per (user, podcast).
CREATE UNIQUE INDEX IF NOT EXISTS user_podcast_preference_user_podcast_uq
    ON user_podcast_preference (user_entity_id, podcast_entity_id);

-- The order a queue was built with. A podcast queue materializes its items in chunks as you
-- listen on, so the direction has to live on the queue: reading the preference per chunk would
-- flip a running queue around the moment the user changes the setting. Existing queues were all
-- built newest-first, which is what the default preserves.
ALTER TABLE play_queue_entity
    ADD COLUMN IF NOT EXISTS source_ascending BOOLEAN NOT NULL DEFAULT FALSE;
