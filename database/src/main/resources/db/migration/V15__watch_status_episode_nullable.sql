-- Pre-Flyway (Hibernate-era) databases still carry a NOT NULL on episode_entity_id from
-- when watch status was episode-only. It silently broke every non-episode watch status
-- (movies, and now chapters/books/podcast episodes). No-op on fresh databases: V1 creates
-- the column nullable.
ALTER TABLE watch_status_entity ALTER COLUMN episode_entity_id DROP NOT NULL;
