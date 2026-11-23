package dev.rabauer.ai.diary.api.memory;

/** @param attemptText optional typed answer; null or blank means "I answered in my head" */
public record ReviewAttemptRequest(String attemptText) {
}
