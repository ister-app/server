CREATE TABLE IF NOT EXISTS podcast_entity (
    id                   UUID NOT NULL PRIMARY KEY,
    date_created         TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    date_updated         TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    library_entity_id    UUID NOT NULL REFERENCES library_entity(id),
    feed_url             VARCHAR(2048) NOT NULL,
    title                VARCHAR(255) NOT NULL,
    author               VARCHAR(255),
    language             VARCHAR(255),
    active               BOOLEAN NOT NULL,
    last_refreshed_at    TIMESTAMP(6) WITH TIME ZONE,
    feed_etag            VARCHAR(255),
    feed_last_modified   VARCHAR(255),
    UNIQUE (feed_url)
);

CREATE TABLE IF NOT EXISTS podcast_episode_entity (
    id                            UUID NOT NULL PRIMARY KEY,
    date_created                  TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    date_updated                  TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    podcast_entity_id             UUID NOT NULL REFERENCES podcast_entity(id),
    guid                          VARCHAR(2048) NOT NULL,
    published_at                  TIMESTAMP(6) WITH TIME ZONE,
    enclosure_url                 VARCHAR(2048) NOT NULL,
    enclosure_type                VARCHAR(255),
    duration_hint_in_milliseconds BIGINT NOT NULL DEFAULT 0,
    episode_number                INTEGER,
    season_number                 INTEGER,
    UNIQUE (podcast_entity_id, guid)
);

ALTER TABLE media_file_entity
    ADD COLUMN IF NOT EXISTS podcast_episode_entity_id UUID REFERENCES podcast_episode_entity(id);

ALTER TABLE image_entity
    ADD COLUMN IF NOT EXISTS podcast_entity_id         UUID REFERENCES podcast_entity(id),
    ADD COLUMN IF NOT EXISTS podcast_episode_entity_id UUID REFERENCES podcast_episode_entity(id);

ALTER TABLE metadata_entity
    ADD COLUMN IF NOT EXISTS podcast_entity_id         UUID REFERENCES podcast_entity(id),
    ADD COLUMN IF NOT EXISTS podcast_episode_entity_id UUID REFERENCES podcast_episode_entity(id);

ALTER TABLE play_queue_item_entity
    ADD COLUMN IF NOT EXISTS podcast_episode_entity_id UUID REFERENCES podcast_episode_entity(id);

ALTER TABLE watch_status_entity
    ADD COLUMN IF NOT EXISTS podcast_episode_entity_id UUID REFERENCES podcast_episode_entity(id);

-- Recreate the watch-status unique constraint to include the new podcast episode column
-- (same recipe as V13).
DO $$
DECLARE
    con text;
BEGIN
    SELECT conname INTO con
    FROM pg_constraint
    WHERE conrelid = 'watch_status_entity'::regclass AND contype = 'u';
    IF con IS NOT NULL THEN
        EXECUTE format('ALTER TABLE watch_status_entity DROP CONSTRAINT %I', con);
    END IF;
END $$;

ALTER TABLE watch_status_entity
    ADD CONSTRAINT watch_status_entity_media_item_key
    UNIQUE (play_queue_item_id, user_entity_id, movie_entity_id, episode_entity_id, chapter_entity_id, book_entity_id, podcast_episode_entity_id);

ALTER TABLE rating_entity
    ADD COLUMN IF NOT EXISTS podcast_entity_id UUID REFERENCES podcast_entity(id);

CREATE UNIQUE INDEX IF NOT EXISTS rating_entity_user_podcast_uq
    ON rating_entity (user_entity_id, podcast_entity_id) WHERE podcast_entity_id IS NOT NULL;
