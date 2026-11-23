package dev.rabauer.ai.diary.api.coach;

import dev.rabauer.ai.diary.api.common.Language;

import java.time.Instant;
import java.util.List;

/** @param messages full transcript in chronological order */
public record ConversationDto(
        long id,
        long entryId,
        ConversationStatus status,
        Language language,
        List<CoachMessageDto> messages,
        Instant startedAt,
        Instant updatedAt,
        Instant endedAt
) {
}
