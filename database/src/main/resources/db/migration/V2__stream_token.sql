CREATE TABLE IF NOT EXISTS stream_token_entity (
    id              UUID NOT NULL PRIMARY KEY,
    date_created    TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    date_updated    TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    user_entity_id  UUID NOT NULL REFERENCES user_entity(id),
    token           UUID NOT NULL UNIQUE,
    expires_at      TIMESTAMP(6) WITH TIME ZONE NOT NULL
);
