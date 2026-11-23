-- Wave 0: entry lifecycle (save-first, two-stage) and user settings. Additive only.
ALTER TABLE documents ADD COLUMN IF NOT EXISTS status varchar(32) NOT NULL DEFAULT 'FINISHED';
ALTER TABLE documents ADD COLUMN IF NOT EXISTS source varchar(16) NOT NULL DEFAULT 'TEXT';
ALTER TABLE documents ADD COLUMN IF NOT EXISTS language varchar(8);
ALTER TABLE documents ADD COLUMN IF NOT EXISTS created_at timestamp(6) with time zone;
ALTER TABLE documents ADD COLUMN IF NOT EXISTS updated_at timestamp(6) with time zone;
ALTER TABLE documents ADD COLUMN IF NOT EXISTS finished_at timestamp(6) with time zone;

-- Pre-v1 entries were complete when saved: treat them as FINISHED at their entry time.
UPDATE documents
SET created_at  = COALESCE(created_at, "timestamp"::timestamptz, now()),
    updated_at  = COALESCE(updated_at, "timestamp"::timestamptz, now()),
    finished_at = COALESCE(finished_at, "timestamp"::timestamptz, now())
WHERE created_at IS NULL;

CREATE INDEX IF NOT EXISTS idx_documents_newest ON documents ("timestamp" DESC, id DESC);

CREATE TABLE IF NOT EXISTS app_setting (
    setting_key   varchar(100)  NOT NULL PRIMARY KEY,
    setting_value varchar(4000) NOT NULL
);
