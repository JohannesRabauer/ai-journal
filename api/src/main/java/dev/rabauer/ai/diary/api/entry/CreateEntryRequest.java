package dev.rabauer.ai.diary.api.entry;

import dev.rabauer.ai.diary.api.common.Language;

import java.time.LocalDateTime;

/** Creates an entry immediately (save-first). All fields optional; {@code text} may be empty for a draft. */
public record CreateEntryRequest(String text, LocalDateTime entryTimestamp, Language language, EntrySource source) {
}
