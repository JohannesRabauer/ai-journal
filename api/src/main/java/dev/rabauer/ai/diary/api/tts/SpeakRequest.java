package dev.rabauer.ai.diary.api.tts;

import dev.rabauer.ai.diary.api.common.Language;

/** POST /api/tts returns audio bytes (provider content type, e.g. audio/wav) or 204 when TTS is disabled or unavailable. */
public record SpeakRequest(String text, Language language, String voice) {
}
