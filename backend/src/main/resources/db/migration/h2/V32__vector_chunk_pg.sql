-- W1e: twin of the PostgreSQL migration V32 (table vector_chunk_pg for the 'pgvector' store).
-- H2 has no pgvector; lite-cloud uses the 'jdbc' vector store (table vector_chunk, V31). Intentionally creates nothing.
SELECT 1;
