package dev.rabauer.ai.diary.api.transcription;

import dev.rabauer.ai.diary.api.common.Language;

import java.time.Instant;
import java.util.UUID;

/**
 * On success the transcript is written into the entry text (entry status TRANSCRIBED); the job carries no text.
 *
 * @param errorCode e.g. {@code PROVIDER_UNREACHABLE}, {@code PROVIDER_ERROR}, {@code AUDIO_MISSING}; null unless FAILED
 */
public record TranscriptionJobDto(
        long id,
        long entryId,
        UUID recordingId,
        TranscriptionStatus status,
        Language requestedLanguage,
        Language detectedLanguage,
        int attempts,
        String errorCode,
        Instant createdAt,
        Instant finishedAt
) {
}
