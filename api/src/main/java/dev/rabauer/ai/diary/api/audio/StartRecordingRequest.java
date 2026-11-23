package dev.rabauer.ai.diary.api.audio;

import dev.rabauer.ai.diary.api.common.Language;

import java.time.LocalDateTime;

/**
 * Starts a recording. If {@code entryId} is null a new VOICE entry is created first (save-first),
 * so a recording always belongs to a persisted entry.
 *
 * @param mimeType e.g. {@code audio/webm;codecs=opus}
 */
public record StartRecordingRequest(Long entryId, String mimeType, Language language, LocalDateTime entryTimestamp) {
}
