CREATE TABLE IF NOT EXISTS rating_entity (
    id                   UUID NOT NULL PRIMARY KEY,
    date_created         TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    date_updated         TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    user_entity_id       UUID NOT NULL REFERENCES user_entity(id),
    movie_entity_id      UUID REFERENCES movie_entity(id),
    show_entity_id       UUID REFERENCES show_entity(id),
    episode_entity_id    UUID REFERENCES episode_entity(id),
    album_entity_id      UUID REFERENCES album_entity(id),
    track_entity_id      UUID REFERENCES track_entity(id),
    value                INTEGER NOT NULL CHECK (value BETWEEN 1 AND 10)
);

-- At most one rating per (user, item). Partial indexes are used because a plain composite
-- unique constraint would treat the NULL item columns as distinct and never fire.
CREATE UNIQUE INDEX IF NOT EXISTS rating_entity_user_movie_uq
    ON rating_entity (user_entity_id, movie_entity_id) WHERE movie_entity_id IS NOT NULL;
CREATE UNIQUE INDEX IF NOT EXISTS rating_entity_user_show_uq
    ON rating_entity (user_entity_id, show_entity_id) WHERE show_entity_id IS NOT NULL;
CREATE UNIQUE INDEX IF NOT EXISTS rating_entity_user_episode_uq
    ON rating_entity (user_entity_id, episode_entity_id) WHERE episode_entity_id IS NOT NULL;
CREATE UNIQUE INDEX IF NOT EXISTS rating_entity_user_album_uq
    ON rating_entity (user_entity_id, album_entity_id) WHERE album_entity_id IS NOT NULL;
CREATE UNIQUE INDEX IF NOT EXISTS rating_entity_user_track_uq
    ON rating_entity (user_entity_id, track_entity_id) WHERE track_entity_id IS NOT NULL;
