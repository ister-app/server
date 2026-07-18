-- Playback-session sharing / privacy.
--
-- No user_sharing_settings row = defaults: now-playing EVERYONE (preserves the previous
-- all-sessions-visible behaviour) and remote control PRIVATE (owner only — the intended
-- tightening of party mode, which previously let any user control any session).
CREATE TABLE IF NOT EXISTS user_sharing_settings (
    id                 UUID NOT NULL PRIMARY KEY,
    date_created       TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    date_updated       TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    user_entity_id     UUID NOT NULL REFERENCES user_entity(id) ON DELETE CASCADE,
    now_playing_scope  VARCHAR(32) NOT NULL DEFAULT 'EVERYONE',
    control_scope      VARCHAR(32) NOT NULL DEFAULT 'PRIVATE'
);

-- At most one settings row per user.
CREATE UNIQUE INDEX IF NOT EXISTS user_sharing_settings_user_uq
    ON user_sharing_settings (user_entity_id);

-- Account-level allowlist: the owner grants a grantee a capability
-- (VIEW = see now-playing, CONTROL = remote control). One row per (owner, grantee, capability).
CREATE TABLE IF NOT EXISTS user_sharing_grant (
    id                 UUID NOT NULL PRIMARY KEY,
    date_created       TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    date_updated       TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    owner_entity_id    UUID NOT NULL REFERENCES user_entity(id) ON DELETE CASCADE,
    grantee_entity_id  UUID NOT NULL REFERENCES user_entity(id) ON DELETE CASCADE,
    capability         VARCHAR(16) NOT NULL
);

CREATE UNIQUE INDEX IF NOT EXISTS user_sharing_grant_uq
    ON user_sharing_grant (owner_entity_id, grantee_entity_id, capability);

-- Per-session remote-control override; NULL = fall back to the owner's control_scope default.
ALTER TABLE play_queue_entity ADD COLUMN IF NOT EXISTS control_scope_override VARCHAR(32);

-- Per-session control allowlist, used when control_scope_override = ALLOWLIST. Its own list,
-- independent of the account-level user_sharing_grant CONTROL grants.
CREATE TABLE IF NOT EXISTS play_queue_control_grant (
    id                    UUID NOT NULL PRIMARY KEY,
    date_created          TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    date_updated          TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    play_queue_entity_id  UUID NOT NULL REFERENCES play_queue_entity(id) ON DELETE CASCADE,
    grantee_entity_id     UUID NOT NULL REFERENCES user_entity(id) ON DELETE CASCADE
);

CREATE UNIQUE INDEX IF NOT EXISTS play_queue_control_grant_uq
    ON play_queue_control_grant (play_queue_entity_id, grantee_entity_id);
