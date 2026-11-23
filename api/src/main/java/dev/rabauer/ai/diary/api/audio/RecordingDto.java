package dev.rabauer.ai.diary.api.audio;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * @param receivedChunks sorted chunk indices already stored server-side; the client uses it to resume after reload
 */
public record RecordingDto(
        UUID id,
        long entryId,
        String mimeType,
        RecordingStatus status,
        List<Integer> receivedChunks,
        Integer totalChunks,
        Long durationMs,
        long sizeBytes,
        Instant createdAt
) {
}
