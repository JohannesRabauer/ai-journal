CREATE TABLE IF NOT EXISTS transcription_job (
    id                 bigint       NOT NULL PRIMARY KEY,
    entry_id           bigint       NOT NULL,
    recording_id       uuid         NOT NULL,
    status             varchar(16)  NOT NULL,
    requested_language varchar(8)   NOT NULL,
    detected_language  varchar(8),
    attempts           integer      NOT NULL DEFAULT 0,
    error_code         varchar(40),
    created_at         timestamp(6) with time zone NOT NULL,
    finished_at        timestamp(6) with time zone
);
CREATE SEQUENCE IF NOT EXISTS transcription_job_seq START WITH 1 INCREMENT BY 50;
CREATE INDEX IF NOT EXISTS idx_transcription_job_entry ON transcription_job (entry_id);
