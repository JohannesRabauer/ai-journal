package dev.rabauer.ai.diary.spi;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.UUID;

/**
 * Stores recording chunks and the assembled audio. Implementations: {@code filesystem}, {@code memory}.
 * All methods are idempotent where it makes sense; writing an existing chunk index replaces it.
 */
public interface AudioStorage {

    void writeChunk(UUID recordingId, int chunkIndex, InputStream data) throws IOException;

    /** Sorted indices of stored chunks; empty if unknown recording. */
    List<Integer> chunkIndices(UUID recordingId);

    /**
     * Concatenates chunks 0..totalChunks-1 into the final audio and removes the chunks.
     *
     * @throws IllegalStateException if a chunk is missing
     */
    void assemble(UUID recordingId, int totalChunks) throws IOException;

    boolean hasAudio(UUID recordingId);

    /** Assembled audio. @throws java.io.FileNotFoundException if absent */
    InputStream openAudio(UUID recordingId) throws IOException;

    long audioSize(UUID recordingId);

    /** Deletes chunks and audio of one recording. @return true if anything was deleted */
    boolean delete(UUID recordingId);

    /** @return number of recordings removed */
    long deleteAll();
}
