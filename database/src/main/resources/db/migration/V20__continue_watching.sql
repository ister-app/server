-- Precomputed "continue watching" list, one row per user per container (show / movie / book /
-- podcast episode). The GraphQL recentlyWatched query used to rebuild this on every call: it walked
-- the watch history, loaded every episode of every show the user had touched just to find the next
-- unwatched one, and filtered and sorted the result in memory. This table turns that into one
-- indexed read.
--
-- The row is maintained incrementally by the playback heartbeat (ContinueWatchingService) and
-- rebuilt from watch_status_entity nightly, so drift can never outlive a day.
CREATE TABLE IF NOT EXISTS continue_watching (
    id                        UUID NOT NULL PRIMARY KEY,
    date_created              TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    date_updated              TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    user_entity_id            UUID NOT NULL REFERENCES user_entity(id) ON DELETE CASCADE,
    -- MediaType: EPISODE / MOVIE / CHAPTER / BOOK / PODCAST_EPISODE.
    entry_type                VARCHAR(31) NOT NULL,
    -- Dedup key: show id / movie id / book id / podcast episode id. Deliberately without a foreign
    -- key: it points at a different table per entry_type.
    group_id                  UUID NOT NULL,
    -- What to show. All of them NULL means "nothing left to continue with" (series or book finished);
    -- the row stays so a later added episode can make the show resurface, and so its last_watched
    -- keeps ordering the history.
    episode_entity_id         UUID REFERENCES episode_entity(id) ON DELETE SET NULL,
    movie_entity_id           UUID REFERENCES movie_entity(id) ON DELETE SET NULL,
    chapter_entity_id         UUID REFERENCES chapter_entity(id) ON DELETE SET NULL,
    book_entity_id            UUID REFERENCES book_entity(id) ON DELETE SET NULL,
    podcast_episode_entity_id UUID REFERENCES podcast_episode_entity(id) ON DELETE SET NULL,
    last_watched              TIMESTAMP(6) WITH TIME ZONE NOT NULL
);

-- One row per container per user; the upsert in ContinueWatchingRepository conflicts on this.
CREATE UNIQUE INDEX IF NOT EXISTS continue_watching_group_uq
    ON continue_watching (user_entity_id, entry_type, group_id);

-- The read path: the user's entries, most recently watched first.
CREATE INDEX IF NOT EXISTS continue_watching_user_last_watched_idx
    ON continue_watching (user_entity_id, last_watched DESC);
