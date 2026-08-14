-- One row per episode contained in a multi-episode media file (s04e06-e07.mkv).
--
-- Single-episode files have no rows here: media_file_entity.episode_entity_id stays the
-- first episode of the file in all cases, and absence of link rows means "single episode".
-- duration_in_milliseconds = 0 means the boundaries have not been computed yet (the scanner
-- creates the rows before analysis knows the file duration).
CREATE TABLE IF NOT EXISTS media_file_episode_entity (
    id                       UUID NOT NULL PRIMARY KEY,
    date_created             TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    date_updated             TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    media_file_entity_id     UUID NOT NULL REFERENCES media_file_entity(id) ON DELETE CASCADE,
    episode_entity_id        UUID NOT NULL REFERENCES episode_entity(id) ON DELETE CASCADE,
    part_number              INT NOT NULL,
    start_in_milliseconds    BIGINT NOT NULL,
    duration_in_milliseconds BIGINT NOT NULL
);

CREATE UNIQUE INDEX IF NOT EXISTS media_file_episode_entity_file_episode_uq
    ON media_file_episode_entity (media_file_entity_id, episode_entity_id);

CREATE INDEX IF NOT EXISTS media_file_episode_entity_episode_idx
    ON media_file_episode_entity (episode_entity_id);
