-- Per-user, per-library grid sort preference (key + direction). Stored server-side (not on the
-- client) so the choice follows the user across phone, desktop and TV. A library without a row
-- falls back to NAME / ASCENDING, the order the grids have always had.
CREATE TABLE IF NOT EXISTS user_library_preference (
    id                   UUID NOT NULL PRIMARY KEY,
    date_created         TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    date_updated         TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    user_entity_id       UUID NOT NULL REFERENCES user_entity(id),
    library_entity_id    UUID NOT NULL REFERENCES library_entity(id),
    sorting              VARCHAR(32) NOT NULL,
    sorting_order        VARCHAR(16) NOT NULL
);

-- At most one preference row per (user, library).
CREATE UNIQUE INDEX IF NOT EXISTS user_library_preference_user_library_uq
    ON user_library_preference (user_entity_id, library_entity_id);
