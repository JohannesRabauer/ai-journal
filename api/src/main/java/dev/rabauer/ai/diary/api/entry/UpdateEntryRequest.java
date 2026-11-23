package dev.rabauer.ai.diary.api.entry;

import dev.rabauer.ai.diary.api.common.Language;

/** Autosave or transcript edit. Null fields are left unchanged. Never triggers AI. */
public record UpdateEntryRequest(String text, Language language) {
}
