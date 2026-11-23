ALTER TABLE documents ADD COLUMN IF NOT EXISTS status varchar(32) DEFAULT 'FINISHED' NOT NULL;
ALTER TABLE documents ADD COLUMN IF NOT EXISTS source varchar(16) DEFAULT 'TEXT' NOT NULL;
ALTER TABLE documents ADD COLUMN IF NOT EXISTS language varchar(8);
ALTER TABLE documents ADD COLUMN IF NOT EXISTS created_at timestamp(6) with time zone;
ALTER TABLE documents ADD COLUMN IF NOT EXISTS updated_at timestamp(6) with time zone;
ALTER TABLE documents ADD COLUMN IF NOT EXISTS finished_at timestamp(6) with time zone;

UPDATE documents
SET created_at  = COALESCE(created_at, CAST("TIMESTAMP" AS timestamp with time zone), CURRENT_TIMESTAMP),
    updated_at  = COALESCE(updated_at, CAST("TIMESTAMP" AS timestamp with time zone), CURRENT_TIMESTAMP),
    finished_at = COALESCE(finished_at, CAST("TIMESTAMP" AS timestamp with time zone), CURRENT_TIMESTAMP)
WHERE created_at IS NULL;

CREATE INDEX IF NOT EXISTS idx_documents_newest ON documents ("TIMESTAMP" DESC, id DESC);

CREATE TABLE IF NOT EXISTS app_setting (
    setting_key   varchar(100)  NOT NULL PRIMARY KEY,
    setting_value varchar(4000) NOT NULL
);
