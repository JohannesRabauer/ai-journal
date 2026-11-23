package dev.rabauer.ai.diary.api.memory;

/** SM-2 quality 0..5. UI maps forgot/hard/good/easy to 1/3/4/5. 409 if no attempt was recorded first. */
public record ReviewGradeRequest(int quality) {
}
