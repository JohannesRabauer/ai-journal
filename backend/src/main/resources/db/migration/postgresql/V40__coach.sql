CREATE TABLE IF NOT EXISTS coach_conversation (
    id         bigint       NOT NULL PRIMARY KEY,
    entry_id   bigint       NOT NULL,
    status     varchar(16)  NOT NULL,
    language   varchar(8)   NOT NULL,
    created_at timestamp(6) with time zone NOT NULL,
    updated_at timestamp(6) with time zone NOT NULL,
    ended_at   timestamp(6) with time zone
);
CREATE SEQUENCE IF NOT EXISTS coach_conversation_seq START WITH 1 INCREMENT BY 50;
CREATE INDEX IF NOT EXISTS idx_coach_conversation_entry ON coach_conversation (entry_id);

-- seq_no is NULL while an opening question is planned but not yet shown to the user.
CREATE TABLE IF NOT EXISTS coach_message (
    id              bigint       NOT NULL PRIMARY KEY,
    conversation_id bigint       NOT NULL,
    role            varchar(8)   NOT NULL,
    kind            varchar(24)  NOT NULL,
    content         text         NOT NULL,
    voice           boolean      NOT NULL DEFAULT false,
    seq_no          integer,
    created_at      timestamp(6) with time zone NOT NULL
);
CREATE SEQUENCE IF NOT EXISTS coach_message_seq START WITH 1 INCREMENT BY 50;
CREATE INDEX IF NOT EXISTS idx_coach_message_conversation ON coach_message (conversation_id);