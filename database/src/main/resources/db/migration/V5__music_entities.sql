CREATE TABLE IF NOT EXISTS artist_entity (
    id                   UUID NOT NULL PRIMARY KEY,
    date_created         TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    date_updated         TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    library_entity_id    UUID NOT NULL REFERENCES library_entity(id),
    name                 VARCHAR(255) NOT NULL,
    UNIQUE (library_entity_id, name)
);

CREATE TABLE IF NOT EXISTS album_entity (
    id                   UUID NOT NULL PRIMARY KEY,
    date_created         TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    date_updated         TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    library_entity_id    UUID NOT NULL REFERENCES library_entity(id),
    artist_entity_id     UUID NOT NULL REFERENCES artist_entity(id),
    name                 VARCHAR(255) NOT NULL,
    release_year         INTEGER NOT NULL,
    UNIQUE (artist_entity_id, name, release_year)
);

CREATE TABLE IF NOT EXISTS track_entity (
    id                   UUID NOT NULL PRIMARY KEY,
    date_created         TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    date_updated         TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    artist_entity_id     UUID NOT NULL REFERENCES artist_entity(id),
    album_entity_id      UUID NOT NULL REFERENCES album_entity(id),
    number               INTEGER NOT NULL,
    disc_number          INTEGER NOT NULL,
    UNIQUE (album_entity_id, number, disc_number)
);

ALTER TABLE image_entity
    ADD COLUMN IF NOT EXISTS artist_entity_id UUID REFERENCES artist_entity(id),
    ADD COLUMN IF NOT EXISTS album_entity_id  UUID REFERENCES album_entity(id);

ALTER TABLE metadata_entity
    ADD COLUMN IF NOT EXISTS artist_entity_id UUID REFERENCES artist_entity(id),
    ADD COLUMN IF NOT EXISTS album_entity_id  UUID REFERENCES album_entity(id),
    ADD COLUMN IF NOT EXISTS track_entity_id  UUID REFERENCES track_entity(id);

ALTER TABLE media_file_entity
    ADD COLUMN IF NOT EXISTS track_entity_id UUID REFERENCES track_entity(id);

ALTER TABLE play_queue_item_entity
    ADD COLUMN IF NOT EXISTS track_entity_id UUID REFERENCES track_entity(id);
