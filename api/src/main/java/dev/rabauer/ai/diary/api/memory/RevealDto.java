package dev.rabauer.ai.diary.api.memory;

/** Only returned after an attempt was recorded for the current review. */
public record RevealDto(long itemId, String answer, String sourceQuote, SourceType sourceType, Long sourceId, String attemptText) {
}
