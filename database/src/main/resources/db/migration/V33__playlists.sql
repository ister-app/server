-- A per-user playlist over exactly one library. MANUAL playlists hold explicit items;
-- SMART playlists embed a MediaFilter definition (same opaque JSON as saved_view_entity.filter)
-- with the browse kind it targets and the play order of the resolved items.
CREATE TABLE IF NOT EXISTS playlist_entity (
    id                   UUID NOT NULL PRIMARY KEY,
    date_created         TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    date_updated         TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    user_entity_id       UUID NOT NULL REFERENCES user_entity(id) ON DELETE CASCADE,
    library_entity_id    UUID NOT NULL REFERENCES library_entity(id) ON DELETE CASCADE,
    name                 VARCHAR(255) NOT NULL,
    type                 VARCHAR(16) NOT NULL,
    filter_kind          VARCHAR(32),
    filter               TEXT,
    sorting              VARCHAR(32),
    sorting_order        VARCHAR(16)
);

CREATE INDEX IF NOT EXISTS playlist_entity_user_idx    ON playlist_entity (user_entity_id);
CREATE INDEX IF NOT EXISTS playlist_entity_library_idx ON playlist_entity (library_entity_id);

-- MANUAL playlist entries: gap-based ordering like play_queue_item_entity, one nullable media
-- column per playable kind of the owning library's type. Deleting the media deletes the entry
-- (unlike play-queue items, a playlist is durable — a dangling id would surface as a blank tile).
CREATE TABLE IF NOT EXISTS playlist_item_entity (
    id                        UUID NOT NULL PRIMARY KEY,
    date_created              TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    date_updated              TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    playlist_entity_id        UUID NOT NULL REFERENCES playlist_entity(id) ON DELETE CASCADE,
    position                  NUMERIC(20, 10) NOT NULL,
    type                      VARCHAR(32) NOT NULL,
    movie_entity_id           UUID REFERENCES movie_entity(id) ON DELETE CASCADE,
    episode_entity_id         UUID REFERENCES episode_entity(id) ON DELETE CASCADE,
    track_entity_id           UUID REFERENCES track_entity(id) ON DELETE CASCADE,
    book_entity_id            UUID REFERENCES book_entity(id) ON DELETE CASCADE,
    podcast_episode_entity_id UUID REFERENCES podcast_episode_entity(id) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS playlist_item_entity_playlist_idx ON playlist_item_entity (playlist_entity_id);
