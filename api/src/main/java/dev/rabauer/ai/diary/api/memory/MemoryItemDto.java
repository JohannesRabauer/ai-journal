package dev.rabauer.ai.diary.api.memory;

import java.time.Instant;
import java.time.LocalDate;

public record MemoryItemDto(
        long id,
        MemoryItemType type,
        String prompt,
        String answer,
        String sourceQuote,
        SourceType sourceType,
        Long sourceId,
        int repetitions,
        int intervalDays,
        double easeFactor,
        LocalDate nextReviewOn,
        Instant createdAt
) {
}
