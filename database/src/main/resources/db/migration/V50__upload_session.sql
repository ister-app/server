-- Admin media upload: a session is one "upload this folder into that directory" action, a file is
-- one target path inside it. The bytes arrive in chunks on the node that can write the directory;
-- these rows are what makes an interrupted upload resumable and an abandoned one cleanable.
CREATE TABLE upload_session (
    id                  UUID NOT NULL PRIMARY KEY,
    date_created        TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    date_updated        TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    directory_entity_id UUID NOT NULL REFERENCES directory_entity (id) ON DELETE CASCADE,
    user_entity_id      UUID REFERENCES user_entity (id) ON DELETE SET NULL,
    -- Relative path inside the directory the upload lands under; empty = the directory root.
    target_parent       VARCHAR(4096) NOT NULL,
    -- Name the picked folder gets on the server; NULL = its children become the roots.
    root_name           VARCHAR(255),
    overwrite           BOOLEAN NOT NULL,
    -- UploadSessionStatus: ACTIVE / COMPLETED / ABORTED / EXPIRED.
    status              VARCHAR(16) NOT NULL,
    last_activity_at    TIMESTAMP(6) WITH TIME ZONE NOT NULL
);

-- The cleanup sweep: active sessions nobody touched for a while.
CREATE INDEX upload_session_status_activity_idx ON upload_session (status, last_activity_at);

CREATE TABLE upload_file (
    id                UUID NOT NULL PRIMARY KEY,
    date_created      TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    date_updated      TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    upload_session_id UUID NOT NULL REFERENCES upload_session (id) ON DELETE CASCADE,
    -- Path relative to the folder the admin picked: how a resuming client finds its local file again.
    relative_path     TEXT NOT NULL,
    -- Full path in the format of media_file_entity.path: absolute for LOCAL, s3://bucket/key for S3.
    target_path       TEXT NOT NULL,
    size              BIGINT NOT NULL,
    chunk_size        BIGINT NOT NULL,
    received_bytes    BIGINT NOT NULL,
    -- UploadFileStatus: PENDING / UPLOADING / COMPLETED / SKIPPED / FAILED.
    status            VARCHAR(16) NOT NULL,
    -- The S3 multipart upload this file is assembled in; NULL for LOCAL directories.
    s3_upload_id      VARCHAR(1024),
    version           BIGINT NOT NULL
);

CREATE INDEX upload_file_session_idx ON upload_file (upload_session_id);

-- Two sessions must never assemble the same target at once. The path carries the directory's own
-- path as prefix, so it is unique across directories by itself.
CREATE UNIQUE INDEX upload_file_active_target_uq
    ON upload_file (target_path) WHERE status IN ('PENDING', 'UPLOADING');

-- Parts of an S3 multipart upload: completing it needs every part's ETag.
CREATE TABLE upload_part (
    id             UUID NOT NULL PRIMARY KEY,
    date_created   TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    date_updated   TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    upload_file_id UUID NOT NULL REFERENCES upload_file (id) ON DELETE CASCADE,
    part_number    INTEGER NOT NULL,
    etag           VARCHAR(255) NOT NULL,
    size           BIGINT NOT NULL
);

-- A retried chunk replaces its part instead of adding a second one.
CREATE UNIQUE INDEX upload_part_file_number_uq ON upload_part (upload_file_id, part_number);
