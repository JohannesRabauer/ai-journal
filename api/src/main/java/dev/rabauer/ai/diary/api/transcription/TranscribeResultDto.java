package dev.rabauer.ai.diary.api.transcription;

import dev.rabauer.ai.diary.api.common.Language;

/**
 * Result of a transcribe-only request (e.g. a spoken coach answer). Nothing is persisted; the audio is discarded.
 *
 * @param detectedLanguage DE, EN, or AUTO if unknown
 */
public record TranscribeResultDto(String text, Language detectedLanguage) {
}
