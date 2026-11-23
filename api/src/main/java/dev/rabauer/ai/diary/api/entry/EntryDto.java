package dev.rabauer.ai.diary.api.entry;

import dev.rabauer.ai.diary.api.common.Language;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * @param entryTimestamp       when the entry was written or spoken (local time, as before v1)
 * @param legacyAiRemark       pre-v1 automatic AI remark, read-only, null for new entries
 * @param recordingId          audio recording of a voice entry, null for text entries
 * @param hasCoachConversation true if at least one coach conversation exists for this entry
 */
public record EntryDto(
        long id,
        LocalDateTime entryTimestamp,
        EntryStatus status,
        EntrySource source,
        Language language,
        String text,
        String legacyAiRemark,
        UUID recordingId,
        boolean hasCoachConversation,
        Instant createdAt,
        Instant updatedAt,
        Instant finishedAt
) {
}
