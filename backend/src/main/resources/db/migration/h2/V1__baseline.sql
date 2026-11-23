-- H2 (lite-cloud profile and tests) equivalent of the PostgreSQL baseline. No pgvector: the legacy
-- documents.embedding column and the legacy diary table only exist on PostgreSQL.
CREATE TABLE IF NOT EXISTS chat_history (
    id bigint NOT NULL PRIMARY KEY,
    serializedmessage text,
    sessionid character varying(255)
);
CREATE SEQUENCE IF NOT EXISTS chat_history_seq START WITH 1 INCREMENT BY 50;

CREATE TABLE IF NOT EXISTS documents (
    id bigint NOT NULL PRIMARY KEY,
    aicontent text,
    entrytext text,
    "TIMESTAMP" timestamp(6) without time zone
);
CREATE SEQUENCE IF NOT EXISTS documents_seq START WITH 1 INCREMENT BY 50;
