package dev.rabauer.ai.diary.api.gym;

import java.time.Instant;
import java.util.List;

/** @param feedbackQuestions optional AI follow-up questions, only when requested (SPEC section 7) */
public record AttemptDto(long id, long exerciseId, GymCategory category, String answer, int durationSeconds,
                         List<String> feedbackQuestions, Instant createdAt) {
}
