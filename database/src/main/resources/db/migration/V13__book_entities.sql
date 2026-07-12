CREATE TABLE IF NOT EXISTS book_entity (
    id                   UUID NOT NULL PRIMARY KEY,
    date_created         TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    date_updated         TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    library_entity_id    UUID NOT NULL REFERENCES library_entity(id),
    person_entity_id     UUID NOT NULL REFERENCES person_entity(id),
    name                 VARCHAR(255) NOT NULL,
    release_year         INTEGER NOT NULL,
    UNIQUE (person_entity_id, name, release_year)
);

CREATE TABLE IF NOT EXISTS chapter_entity (
    id                   UUID NOT NULL PRIMARY KEY,
    date_created         TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    date_updated         TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    person_entity_id     UUID NOT NULL REFERENCES person_entity(id),
    book_entity_id       UUID NOT NULL REFERENCES book_entity(id),
    number               INTEGER NOT NULL,
    UNIQUE (book_entity_id, number)
);

ALTER TABLE media_file_entity
    ADD COLUMN IF NOT EXISTS book_entity_id    UUID REFERENCES book_entity(id),
    ADD COLUMN IF NOT EXISTS chapter_entity_id UUID REFERENCES chapter_entity(id),
    ADD COLUMN IF NOT EXISTS media_overlays    BOOLEAN;

ALTER TABLE image_entity
    ADD COLUMN IF NOT EXISTS book_entity_id UUID REFERENCES book_entity(id);

ALTER TABLE metadata_entity
    ADD COLUMN IF NOT EXISTS book_entity_id    UUID REFERENCES book_entity(id),
    ADD COLUMN IF NOT EXISTS chapter_entity_id UUID REFERENCES chapter_entity(id);

ALTER TABLE play_queue_item_entity
    ADD COLUMN IF NOT EXISTS chapter_entity_id UUID REFERENCES chapter_entity(id);

ALTER TABLE watch_status_entity
    ADD COLUMN IF NOT EXISTS chapter_entity_id UUID REFERENCES chapter_entity(id),
    ADD COLUMN IF NOT EXISTS book_entity_id    UUID REFERENCES book_entity(id),
    ADD COLUMN IF NOT EXISTS reading_location  VARCHAR(255),
    ADD COLUMN IF NOT EXISTS reading_progress  DOUBLE PRECISION;

-- Recreate the watch-status unique constraint to include the new book/chapter columns.
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
    UNIQUE (play_queue_item_id, user_entity_id, movie_entity_id, episode_entity_id, chapter_entity_id, book_entity_id);

ALTER TABLE rating_entity
    ADD COLUMN IF NOT EXISTS book_entity_id UUID REFERENCES book_entity(id);

CREATE UNIQUE INDEX IF NOT EXISTS rating_entity_user_book_uq
    ON rating_entity (user_entity_id, book_entity_id) WHERE book_entity_id IS NOT NULL;
