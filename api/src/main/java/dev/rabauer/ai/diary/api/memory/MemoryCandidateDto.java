package dev.rabauer.ai.diary.api.memory;

import java.time.Instant;

public record MemoryCandidateDto(
        long id,
        SourceType sourceType,
        long sourceId,
        MemoryItemType type,
        String prompt,
        String answer,
        String sourceQuote,
        CandidateStatus status,
        Instant createdAt
) {
}
