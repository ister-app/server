-- Custom views ("smart playlists"): a per-user named filter definition over one browse kind,
-- optionally scoped to one library. The definition itself is opaque JSON — fields/operators are
-- validated application-side and the database never queries into it.
CREATE TABLE IF NOT EXISTS saved_view_entity (
    id                   UUID NOT NULL PRIMARY KEY,
    date_created         TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    date_updated         TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    user_entity_id       UUID NOT NULL REFERENCES user_entity(id),
    library_entity_id    UUID REFERENCES library_entity(id),
    name                 VARCHAR(255) NOT NULL,
    kind                 VARCHAR(32) NOT NULL,
    filter               TEXT NOT NULL,
    sorting              VARCHAR(32),
    sorting_order        VARCHAR(16)
);

CREATE INDEX IF NOT EXISTS saved_view_entity_user_idx ON saved_view_entity (user_entity_id);

-- FILTER play queues pin a copy of their filter definition (kind, groups, limit, sort, library
-- scope) onto the queue, so editing or deleting the saved view never reshapes a queue that is
-- already playing.
ALTER TABLE play_queue_entity ADD COLUMN IF NOT EXISTS source_filter TEXT;
