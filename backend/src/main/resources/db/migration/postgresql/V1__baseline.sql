-- Baseline = schema produced by the pre-v1 app (Hibernate schema "update" + quarkus-langchain4j-pgvector).
-- Existing databases are baselined at version 1 (quarkus.flyway.baseline-on-migrate=true) and skip this file;
-- fresh databases run it. Everything is IF NOT EXISTS so partially created schemas also work.
CREATE EXTENSION IF NOT EXISTS vector;

CREATE TABLE IF NOT EXISTS chat_history (
    id bigint NOT NULL PRIMARY KEY,
    serializedmessage text,
    sessionid character varying(255)
);
CREATE SEQUENCE IF NOT EXISTS chat_history_seq START WITH 1 INCREMENT BY 50;

CREATE TABLE IF NOT EXISTS documents (
    id bigint NOT NULL PRIMARY KEY,
    aicontent text,
    embedding vector(1024),
    entrytext text,
    "timestamp" timestamp(6) without time zone
);
CREATE SEQUENCE IF NOT EXISTS documents_seq START WITH 1 INCREMENT BY 50;

-- Created lazily by quarkus-langchain4j-pgvector (table-name=diary). Legacy, no longer written.
CREATE TABLE IF NOT EXISTS diary (
    embedding_id uuid NOT NULL PRIMARY KEY,
    embedding vector(1024),
    text text,
    metadata jsonb
);
