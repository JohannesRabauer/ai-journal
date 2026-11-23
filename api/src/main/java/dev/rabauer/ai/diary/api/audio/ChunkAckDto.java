package dev.rabauer.ai.diary.api.audio;

import java.util.UUID;

/** Acknowledges a chunk. Uploading the same index again is idempotent ({@code duplicate=true}). */
public record ChunkAckDto(UUID recordingId, int chunkIndex, boolean duplicate, int receivedCount) {
}
