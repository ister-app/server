-- V1__initial_schema.sql
-- Initial schema for Ister Server
-- Uses CREATE TABLE IF NOT EXISTS for safety on existing databases

CREATE TABLE IF NOT EXISTS node_entity (
    id                   UUID NOT NULL PRIMARY KEY,
    date_created         TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    date_updated         TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    name                 VARCHAR(255) NOT NULL UNIQUE,
    url                  VARCHAR(255) NOT NULL,
    version              VARCHAR(255)
);

CREATE TABLE IF NOT EXISTS library_entity (
    id                   UUID NOT NULL PRIMARY KEY,
    date_created         TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    date_updated         TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    library_type         VARCHAR(255) NOT NULL,
    name                 VARCHAR(255) NOT NULL UNIQUE
);

CREATE TABLE IF NOT EXISTS user_entity (
    id                   UUID NOT NULL PRIMARY KEY,
    date_created         TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    date_updated         TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    external_id          VARCHAR(255) NOT NULL,
    name                 VARCHAR(255),
    email                VARCHAR(255)
);

CREATE TABLE IF NOT EXISTS directory_entity (
    id                   UUID NOT NULL PRIMARY KEY,
    date_created         TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    date_updated         TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    node_entity_id       UUID NOT NULL REFERENCES node_entity(id),
    library_entity_id    UUID REFERENCES library_entity(id),
    name                 VARCHAR(255) NOT NULL UNIQUE,
    path                 VARCHAR(255) NOT NULL,
    directory_type       VARCHAR(255) NOT NULL
);

CREATE TABLE IF NOT EXISTS movie_entity (
    id                   UUID NOT NULL PRIMARY KEY,
    date_created         TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    date_updated         TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    library_entity_id    UUID NOT NULL REFERENCES library_entity(id),
    name                 VARCHAR(255) NOT NULL,
    release_year         INTEGER NOT NULL,
    UNIQUE (library_entity_id, name, release_year)
);

CREATE TABLE IF NOT EXISTS show_entity (
    id                   UUID NOT NULL PRIMARY KEY,
    date_created         TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    date_updated         TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    library_entity_id    UUID NOT NULL REFERENCES library_entity(id),
    name                 VARCHAR(255) NOT NULL,
    release_year         INTEGER NOT NULL,
    UNIQUE (library_entity_id, name, release_year)
);

CREATE TABLE IF NOT EXISTS season_entity (
    id                   UUID NOT NULL PRIMARY KEY,
    date_created         TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    date_updated         TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    show_entity_id       UUID NOT NULL REFERENCES show_entity(id),
    number               INTEGER NOT NULL,
    UNIQUE (show_entity_id, number)
);

CREATE TABLE IF NOT EXISTS episode_entity (
    id                   UUID NOT NULL PRIMARY KEY,
    date_created         TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    date_updated         TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    show_entity_id       UUID NOT NULL REFERENCES show_entity(id),
    season_entity_id     UUID NOT NULL REFERENCES season_entity(id),
    number               INTEGER NOT NULL,
    UNIQUE (show_entity_id, season_entity_id, number)
);

CREATE TABLE IF NOT EXISTS metadata_entity (
    id                   UUID NOT NULL PRIMARY KEY,
    date_created         TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    date_updated         TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    movie_entity_id      UUID REFERENCES movie_entity(id),
    show_entity_id       UUID REFERENCES show_entity(id),
    season_entity_id     UUID REFERENCES season_entity(id),
    episode_entity_id    UUID REFERENCES episode_entity(id),
    source_uri           VARCHAR(255),
    language             VARCHAR(255),
    title                VARCHAR(255),
    description          TEXT,
    released             DATE
);

CREATE TABLE IF NOT EXISTS image_entity (
    id                        UUID NOT NULL PRIMARY KEY,
    date_created              TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    date_updated              TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    directory_entity_id       UUID NOT NULL REFERENCES directory_entity(id),
    file_creation_time        TIMESTAMP(6) WITH TIME ZONE,
    file_last_modified_time   TIMESTAMP(6) WITH TIME ZONE,
    path                      VARCHAR(255) NOT NULL,
    type                      VARCHAR(255) NOT NULL,
    language                  VARCHAR(255),
    source_uri                VARCHAR(255),
    blur_hash                 VARCHAR(255),
    movie_entity_id           UUID REFERENCES movie_entity(id),
    show_entity_id            UUID REFERENCES show_entity(id),
    season_entity_id          UUID REFERENCES season_entity(id),
    episode_entity_id         UUID REFERENCES episode_entity(id),
    UNIQUE (directory_entity_id, path)
);

CREATE TABLE IF NOT EXISTS media_file_entity (
    id                        UUID NOT NULL PRIMARY KEY,
    date_created              TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    date_updated              TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    directory_entity_id       UUID NOT NULL REFERENCES directory_entity(id),
    file_creation_time        TIMESTAMP(6) WITH TIME ZONE,
    file_last_modified_time   TIMESTAMP(6) WITH TIME ZONE,
    path                      VARCHAR(255) NOT NULL,
    movie_entity_id           UUID REFERENCES movie_entity(id),
    episode_entity_id         UUID REFERENCES episode_entity(id),
    size                      BIGINT NOT NULL,
    duration_in_milliseconds  BIGINT,
    UNIQUE (directory_entity_id, path)
);

CREATE TABLE IF NOT EXISTS media_file_stream_entity (
    id                   UUID NOT NULL PRIMARY KEY,
    date_created         TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    date_updated         TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    media_file_entity_id UUID NOT NULL REFERENCES media_file_entity(id),
    stream_index         INTEGER,
    codec_name           VARCHAR(255) NOT NULL,
    codec_type           VARCHAR(255) NOT NULL,
    width                INTEGER NOT NULL,
    height               INTEGER NOT NULL,
    path                 VARCHAR(255) NOT NULL,
    language             VARCHAR(255),
    title                VARCHAR(255),
    UNIQUE (media_file_entity_id, stream_index, path)
);

CREATE TABLE IF NOT EXISTS play_queue_entity (
    id                        UUID NOT NULL PRIMARY KEY,
    date_created              TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    date_updated              TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    user_entity_id            UUID NOT NULL REFERENCES user_entity(id),
    current_item              UUID,
    progress_in_milliseconds  BIGINT
);

CREATE TABLE IF NOT EXISTS play_queue_item_entity (
    id                   UUID NOT NULL PRIMARY KEY,
    date_created         TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    date_updated         TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    play_queue_entity_id UUID NOT NULL REFERENCES play_queue_entity(id),
    position             NUMERIC(20, 10) NOT NULL,
    type                 VARCHAR(255) NOT NULL,
    movie_entity_id      UUID REFERENCES movie_entity(id),
    episode_entity_id    UUID REFERENCES episode_entity(id)
);

CREATE TABLE IF NOT EXISTS watch_status_entity (
    id                        UUID NOT NULL PRIMARY KEY,
    date_created              TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    date_updated              TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    play_queue_item_id        UUID NOT NULL,
    user_entity_id            UUID NOT NULL REFERENCES user_entity(id),
    movie_entity_id           UUID REFERENCES movie_entity(id),
    episode_entity_id         UUID REFERENCES episode_entity(id),
    watched                   BOOLEAN NOT NULL,
    progress_in_milliseconds  BIGINT,
    UNIQUE (play_queue_item_id, user_entity_id, movie_entity_id, episode_entity_id)
);

CREATE TABLE IF NOT EXISTS server_event_entity (
    id                   UUID NOT NULL PRIMARY KEY,
    date_created         TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    date_updated         TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    event_type           VARCHAR(255) NOT NULL,
    failed               BOOLEAN NOT NULL DEFAULT FALSE,
    directory_entity_id  UUID REFERENCES directory_entity(id),
    episode_entity_id    UUID REFERENCES episode_entity(id),
    path                 VARCHAR(255),
    data                 TEXT
);

CREATE TABLE IF NOT EXISTS other_path_file_entity (
    id                        UUID NOT NULL PRIMARY KEY,
    date_created              TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    date_updated              TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    directory_entity_id       UUID NOT NULL REFERENCES directory_entity(id),
    file_creation_time        TIMESTAMP(6) WITH TIME ZONE,
    file_last_modified_time   TIMESTAMP(6) WITH TIME ZONE,
    path                      VARCHAR(255) NOT NULL,
    path_file_type            VARCHAR(255) NOT NULL,
    UNIQUE (directory_entity_id, path)
);
