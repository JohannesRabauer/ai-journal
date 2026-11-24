-- W1e: vector store 'jdbc' (cosine similarity computed in Java; works on PostgreSQL and H2).
-- vector_data = big-endian IEEE-754 float32 array of length 'dimension'.
CREATE TABLE IF NOT EXISTS vector_chunk (
    namespace   varchar(64)  NOT NULL,
    owner_id    varchar(128) NOT NULL,
    chunk_index integer      NOT NULL,
    chunk_text  text         NOT NULL,
    model_id    varchar(200) NOT NULL,
    dimension   integer      NOT NULL,
    vector_data varbinary(1000000) NOT NULL,
    PRIMARY KEY (namespace, owner_id, chunk_index)
);
CREATE INDEX IF NOT EXISTS idx_vector_chunk_model ON vector_chunk (namespace, model_id, dimension);
