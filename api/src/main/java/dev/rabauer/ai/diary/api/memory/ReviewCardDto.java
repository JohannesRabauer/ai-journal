package dev.rabauer.ai.diary.api.memory;

/** Shown BEFORE an attempt: the prompt only, never the answer or source (SPEC section 6). */
public record ReviewCardDto(long itemId, MemoryItemType type, String prompt) {
}
