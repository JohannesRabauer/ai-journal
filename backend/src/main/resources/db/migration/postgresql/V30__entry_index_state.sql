-- W1e: which embedding model has indexed which finished entry (backfill bookkeeping; derived data).
-- An entry is (re-)indexed when it has no row here or the row's model_id differs from the current embedding model id.
CREATE TABLE IF NOT EXISTS entry_index_state (
    entry_id   bigint       NOT NULL PRIMARY KEY,
    model_id   varchar(200) NOT NULL,
    indexed_at timestamp(6) with time zone NOT NULL
);
