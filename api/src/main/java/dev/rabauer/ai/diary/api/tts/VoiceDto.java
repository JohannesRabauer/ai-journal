package dev.rabauer.ai.diary.api.tts;

import dev.rabauer.ai.diary.api.common.Language;

public record VoiceDto(String id, Language language, String label) {
}
