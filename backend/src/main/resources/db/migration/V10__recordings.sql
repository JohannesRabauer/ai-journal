CREATE TABLE IF NOT EXISTS recording (
    id           uuid         NOT NULL PRIMARY KEY,
    entry_id     bigint       NOT NULL,
    mime_type    varchar(100) NOT NULL,
    status       varchar(16)  NOT NULL,
    total_chunks integer,
    duration_ms  bigint,
    size_bytes   bigint       NOT NULL DEFAULT 0,
    created_at   timestamp(6) with time zone NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_recording_entry ON recording (entry_id);
