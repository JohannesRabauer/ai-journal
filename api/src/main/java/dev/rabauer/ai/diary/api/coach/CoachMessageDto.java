package dev.rabauer.ai.diary.api.coach;

import java.time.Instant;

/** @param voice true if a USER_ANSWER was spoken (transcribed) rather than typed */
public record CoachMessageDto(long id, CoachRole role, CoachMessageKind kind, String content, boolean voice, Instant createdAt) {
}
