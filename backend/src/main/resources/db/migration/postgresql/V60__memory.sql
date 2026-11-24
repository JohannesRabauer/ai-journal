-- Memory (SPEC section 6): extraction candidates, approved items (SM-2 state) and the review log.
CREATE TABLE IF NOT EXISTS memory_candidate (
    id           bigint       NOT NULL PRIMARY KEY,
    source_type  varchar(16)  NOT NULL,
    source_id    bigint       NOT NULL,
    entry_id     bigint       NOT NULL,
    item_type    varchar(16)  NOT NULL,
    prompt       text         NOT NULL,
    answer       text         NOT NULL,
    source_quote text         NOT NULL,
    status       varchar(16)  NOT NULL,
    created_at   timestamp(6) with time zone NOT NULL
);
CREATE SEQUENCE IF NOT EXISTS memory_candidate_seq START WITH 1 INCREMENT BY 50;
CREATE INDEX IF NOT EXISTS idx_memory_candidate_status ON memory_candidate (status);
CREATE INDEX IF NOT EXISTS idx_memory_candidate_entry ON memory_candidate (entry_id);

-- source_* and entry_id become NULL when the source entry is deleted (the item itself is kept).
CREATE TABLE IF NOT EXISTS memory_item (
    id             bigint       NOT NULL PRIMARY KEY,
    item_type      varchar(16)  NOT NULL,
    prompt         text         NOT NULL,
    answer         text         NOT NULL,
    source_quote   text,
    source_type    varchar(16),
    source_id      bigint,
    entry_id       bigint,
    repetitions    integer      NOT NULL,
    interval_days  integer      NOT NULL,
    ease_factor    double precision NOT NULL,
    next_review_on date         NOT NULL,
    created_at     timestamp(6) with time zone NOT NULL
);
CREATE SEQUENCE IF NOT EXISTS memory_item_seq START WITH 1 INCREMENT BY 50;
CREATE INDEX IF NOT EXISTS idx_memory_item_due ON memory_item (next_review_on);
CREATE INDEX IF NOT EXISTS idx_memory_item_entry ON memory_item (entry_id);

-- One row per review cycle: created by the attempt (quality NULL), completed by the grade. After an item is deleted
-- the rows stay as an anonymous log (attempt_text cleared) so counts do not shrink.
CREATE TABLE IF NOT EXISTS memory_review (
    id            bigint       NOT NULL PRIMARY KEY,
    item_id       bigint       NOT NULL,
    attempt_text  text,
    attempted_at  timestamp(6) with time zone NOT NULL,
    quality       integer,
    graded_at     timestamp(6) with time zone,
    interval_days integer
);
CREATE SEQUENCE IF NOT EXISTS memory_review_seq START WITH 1 INCREMENT BY 50;
CREATE INDEX IF NOT EXISTS idx_memory_review_item ON memory_review (item_id);
CREATE INDEX IF NOT EXISTS idx_memory_review_graded ON memory_review (graded_at);
