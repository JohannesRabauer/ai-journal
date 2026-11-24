package dev.rabauer.ai.diary.spi;

import java.util.List;

/**
 * Similarity search over embedded chunks. Implementations: {@code jdbc} (cosine in Java over stored float
 * arrays in the embedded H2 database; plenty for a journal), {@code memory}.
 *
 * <p>{@code namespace} separates owners, e.g. {@code "entry"}; {@code ownerId} is the owner's id as string.
 */
public interface VectorStore {

    /** Replaces all chunks of the owner atomically. */
    void upsert(String namespace, String ownerId, List<Chunk> chunks);

    /** Results sorted by descending cosine similarity, only vectors with the same {@code modelId}. */
    List<Match> search(String namespace, float[] query, String modelId, int limit, double minScore);

    void delete(String namespace, String ownerId);

    /** Number of chunks currently stored for the owner (all models). */
    int count(String namespace, String ownerId);

    /** Deletes everything. @return number of chunks removed */
    long deleteAll();

    record Chunk(int index, String text, float[] vector, String modelId) {
    }

    record Match(String namespace, String ownerId, int chunkIndex, String text, double score) {
    }
}
