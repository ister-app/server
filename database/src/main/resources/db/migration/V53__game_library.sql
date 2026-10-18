-- GAME libraries: emulator ROMs, PS3 game folders, PC games (portable zip, Linux installer /
-- portable archive) and Steam installs. The game runs on the client (the player launches a local
-- emulator or the game itself); the server is the library plus the per-user save cloud.
--
-- Two file models share one row type:
--   * single-file forms (ROM, zip, sh, tar) attach their file(s) as media_file_entity rows
--     (game_entity_id), like comic volumes;
--   * folder forms (PS3_FOLDER, STEAM) are ONE row with root_path and no per-file rows: a PS3 or
--     Steam game is 5k-100k files, which would swamp media_file_entity, the scan cache and the
--     upload "exists" batches. The server lists the tree live (/game/{id}/manifest) and serves each
--     file by relative path with Range support.
CREATE TABLE game_entity (
    id                  UUID NOT NULL PRIMARY KEY,
    date_created        TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    date_updated        TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    library_entity_id   UUID NOT NULL REFERENCES library_entity (id),
    -- GamePlatform: NES, SNES, N64, GB, GBC, GBA, NDS, PSX, PS2, PS3, GC, WII, PC, LINUX, STEAM.
    platform            VARCHAR(16) NOT NULL,
    -- GameForm: ROM, PS3_FOLDER, PC_PORTABLE_ZIP, PC_LINUX_INSTALLER, PC_LINUX_PORTABLE, STEAM.
    form                VARCHAR(32) NOT NULL,
    -- Path identity: the "Title" of the "Title (Year)" game folder. Never rewritten from metadata.
    name                VARCHAR(255) NOT NULL,
    -- Display override: PARAM.SFO TITLE, the .acf name, later a metadata provider. NULL = name.
    title               VARCHAR(255),
    release_year        INTEGER NOT NULL DEFAULT 0,
    -- The "(YYYY)" of the game folder (0 = none); part of the identity like books.
    path_year           INTEGER NOT NULL DEFAULT 0,
    -- Folder forms: the directory and the game folder (absolute, or s3://bucket/key). NULL otherwise.
    directory_entity_id UUID REFERENCES directory_entity (id) ON DELETE CASCADE,
    root_path           TEXT,
    -- The file the client launches, relative to the game folder (PS3_GAME/USRDIR/EBOOT.BIN,
    -- Game.exe inside the zip, setup.sh). NULL when the single media file is the entry itself.
    entry_path          TEXT,
    -- PS3: PARAM.SFO TITLE_ID (BLUS12345); names the emulator's savedata/trophy folders.
    title_id            VARCHAR(16),
    -- Steam: from appmanifest_<appid>.acf; install_dir is the folder name under steamapps/common.
    steam_app_id        BIGINT,
    steam_install_dir   VARCHAR(255),
    steam_build_id      BIGINT,
    -- Ludusavi says the game syncs its saves through Steam Cloud too (clients warn about double sync).
    steam_cloud         BOOLEAN NOT NULL DEFAULT FALSE,
    size_bytes          BIGINT NOT NULL DEFAULT 0,
    file_count          INTEGER NOT NULL DEFAULT 0,
    -- JSON arrays of save-location templates (Ludusavi placeholders like <winAppData>/Game/Saves).
    -- save_paths is the admin override and always wins; manifest_save_paths is what the worker
    -- resolved from the Ludusavi manifest (manifest_matched_by: STEAM_ID / TITLE / NONE).
    save_paths          TEXT,
    manifest_save_paths TEXT,
    manifest_matched_by VARCHAR(16),
    CONSTRAINT game_entity_identity UNIQUE (library_entity_id, platform, name, path_year)
);
CREATE INDEX game_entity_library_idx ON game_entity (library_entity_id);
CREATE UNIQUE INDEX game_entity_root_uq ON game_entity (directory_entity_id, root_path) WHERE root_path IS NOT NULL;

-- Single-file forms attach their files here. game_entry_file = false marks companions of a
-- multi-file ROM set (the .bin tracks of a .cue): downloaded with the game, never launched.
ALTER TABLE media_file_entity ADD COLUMN game_entity_id UUID REFERENCES game_entity (id);
ALTER TABLE media_file_entity ADD COLUMN game_entry_file BOOLEAN NOT NULL DEFAULT TRUE;
CREATE INDEX media_file_entity_game_idx ON media_file_entity (game_entity_id);

ALTER TABLE image_entity    ADD COLUMN game_entity_id UUID REFERENCES game_entity (id);
ALTER TABLE metadata_entity ADD COLUMN game_entity_id UUID REFERENCES game_entity (id);
ALTER TABLE rating_entity   ADD COLUMN game_entity_id UUID REFERENCES game_entity (id);
CREATE INDEX image_entity_game_idx    ON image_entity (game_entity_id);
CREATE INDEX metadata_entity_game_idx ON metadata_entity (game_entity_id);
CREATE UNIQUE INDEX rating_entity_user_game_uq ON rating_entity (user_entity_id, game_entity_id)
    WHERE game_entity_id IS NOT NULL;

-- A game's watch status is "last played + total play time": one row per user per game
-- (play_queue_item_id holds the game id, like books). The media-item unique key from V29 gains the
-- new column, otherwise two games of one user would collide on an all-NULL item tuple.
ALTER TABLE watch_status_entity ADD COLUMN game_entity_id UUID REFERENCES game_entity (id);
ALTER TABLE watch_status_entity ADD COLUMN play_time_ms BIGINT NOT NULL DEFAULT 0;
ALTER TABLE watch_status_entity DROP CONSTRAINT IF EXISTS watch_status_entity_media_item_key;
ALTER TABLE watch_status_entity
    ADD CONSTRAINT watch_status_entity_media_item_key
    UNIQUE (play_queue_item_id, user_entity_id, movie_entity_id, episode_entity_id, chapter_entity_id,
            book_entity_id, podcast_episode_entity_id, track_entity_id, game_entity_id);
CREATE INDEX watch_status_entity_user_game_idx ON watch_status_entity (user_entity_id, game_entity_id)
    WHERE game_entity_id IS NOT NULL;

-- Continue-watching entry of type GAME: the game itself is both the group and the target.
ALTER TABLE continue_watching ADD COLUMN game_entity_id UUID REFERENCES game_entity (id) ON DELETE SET NULL;

-- Per-user save snapshots: one zip per play session, versions count up per (user, game), the
-- newest N are kept (app.ister.games.save-retention). The zip lives in the cache store under
-- saves/<userId>/<gameId>/<version>.zip; stored_path is the full path the cache cleanup checks.
CREATE TABLE game_save_entity (
    id                        UUID NOT NULL PRIMARY KEY,
    date_created              TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    date_updated              TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    user_entity_id            UUID NOT NULL REFERENCES user_entity (id) ON DELETE CASCADE,
    game_entity_id            UUID NOT NULL REFERENCES game_entity (id) ON DELETE CASCADE,
    version                   INTEGER NOT NULL,
    cache_directory_entity_id UUID NOT NULL REFERENCES directory_entity (id) ON DELETE CASCADE,
    stored_path               TEXT NOT NULL,
    size_bytes                BIGINT NOT NULL,
    sha256                    VARCHAR(64) NOT NULL,
    -- Which client wrote it (hostname), and a free note ("restored from v3").
    device_name               VARCHAR(255),
    note                      VARCHAR(255),
    CONSTRAINT game_save_entity_version_uq UNIQUE (user_entity_id, game_entity_id, version)
);
CREATE INDEX game_save_entity_cache_dir_idx ON game_save_entity (cache_directory_entity_id);

-- Achievements, reported by the client from the local emulator / store files (RPCS3 trophies,
-- Steam's stats cache). The definitions are per game and source; unlocks per user.
CREATE TABLE game_achievement_entity (
    id                   UUID NOT NULL PRIMARY KEY,
    date_created         TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    date_updated         TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    game_entity_id       UUID NOT NULL REFERENCES game_entity (id) ON DELETE CASCADE,
    -- GameAchievementSource: PS3_TROPHY, STEAM (RETROACHIEVEMENTS reserved).
    source               VARCHAR(24) NOT NULL,
    -- The trophy id / Steam api name: stable within (game, source).
    external_id          VARCHAR(128) NOT NULL,
    name                 VARCHAR(255) NOT NULL,
    description          TEXT,
    -- PS3 trophy grade: BRONZE / SILVER / GOLD / PLATINUM; NULL for sources without grades.
    grade                VARCHAR(16),
    hidden               BOOLEAN NOT NULL DEFAULT FALSE,
    sort_order           INTEGER NOT NULL DEFAULT 0,
    icon_image_entity_id UUID REFERENCES image_entity (id) ON DELETE SET NULL,
    CONSTRAINT game_achievement_entity_identity UNIQUE (game_entity_id, source, external_id)
);

CREATE TABLE user_game_achievement_entity (
    id                         UUID NOT NULL PRIMARY KEY,
    date_created               TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    date_updated               TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    user_entity_id             UUID NOT NULL REFERENCES user_entity (id) ON DELETE CASCADE,
    game_achievement_entity_id UUID NOT NULL REFERENCES game_achievement_entity (id) ON DELETE CASCADE,
    unlocked_at                TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    device_name                VARCHAR(255),
    CONSTRAINT user_game_achievement_entity_uq UNIQUE (user_entity_id, game_achievement_entity_id)
);
CREATE INDEX user_game_achievement_entity_user_idx ON user_game_achievement_entity (user_entity_id, unlocked_at DESC);
