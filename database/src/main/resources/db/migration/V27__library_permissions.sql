-- Admin flag + per-user library visibility.
-- user_entity.admin is a snapshot of the Keycloak 'admin' realm role, refreshed on every
-- authenticated GraphQL request; stream-token requests carry no JWT, so file endpoints rely
-- on this snapshot for the admin bypass.
-- library_entity.visible_to_all defaults TRUE so existing installs keep working unchanged;
-- a restricted library is only visible to admins and to users with a user_library_access row.
ALTER TABLE user_entity ADD COLUMN IF NOT EXISTS admin BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE library_entity ADD COLUMN IF NOT EXISTS visible_to_all BOOLEAN NOT NULL DEFAULT TRUE;

CREATE TABLE IF NOT EXISTS user_library_access (
    id                   UUID NOT NULL PRIMARY KEY,
    date_created         TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    date_updated         TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    user_entity_id       UUID NOT NULL REFERENCES user_entity(id) ON DELETE CASCADE,
    library_entity_id    UUID NOT NULL REFERENCES library_entity(id) ON DELETE CASCADE
);

-- At most one grant row per (user, library).
CREATE UNIQUE INDEX IF NOT EXISTS user_library_access_user_library_uq
    ON user_library_access (user_entity_id, library_entity_id);
