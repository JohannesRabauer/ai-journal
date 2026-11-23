-- W1e: vector store 'pgvector' (PostgreSQL only). The column is an UNBOUNDED vector (no fixed dimension) so that
-- switching the embedding model never needs a migration; rows are filtered by model_id and dimension before
-- the cosine-distance operator (<=>) is applied. Independent of the legacy table 'diary' and documents.embedding.
CREATE EXTENSION IF NOT EXISTS vector;
CREATE TABLE IF NOT EXISTS vector_chunk_pg (
    namespace   varchar(64)  NOT NULL,
    owner_id    varchar(128) NOT NULL,
    chunk_index integer      NOT NULL,
    chunk_text  text         NOT NULL,
    model_id    varchar(200) NOT NULL,
    dimension   integer      NOT NULL,
    embedding   vector       NOT NULL,
    PRIMARY KEY (namespace, owner_id, chunk_index)
);
CREATE INDEX IF NOT EXISTS idx_vector_chunk_pg_model ON vector_chunk_pg (namespace, model_id, dimension);
