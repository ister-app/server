-- Registered client devices (one row per app install per user).
--
-- device_id is the client-generated install UUID. It is only unique per user: the client
-- value is not globally trusted, so every lookup and command route is scoped by owner.
CREATE TABLE IF NOT EXISTS device_entity (
    id             UUID NOT NULL PRIMARY KEY,
    date_created   TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    date_updated   TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    user_entity_id UUID NOT NULL REFERENCES user_entity(id) ON DELETE CASCADE,
    device_id      UUID NOT NULL,
    name           VARCHAR(255) NOT NULL,
    platform       VARCHAR(32) NOT NULL,
    last_seen_at   TIMESTAMP(6) WITH TIME ZONE NOT NULL
);

CREATE UNIQUE INDEX IF NOT EXISTS device_entity_user_device_uq
    ON device_entity (user_entity_id, device_id);
