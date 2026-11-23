package dev.rabauer.ai.diary.api.transcription;

import dev.rabauer.ai.diary.api.common.Language;

/** Starts (or retries) transcription of the entry's recording. {@code AUTO} = provider auto-detect. */
public record StartTranscriptionRequest(Language language) {
}
