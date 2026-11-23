package dev.rabauer.ai.diary.api.memory;

public record LessonDto(String id, int order, String title, String body, boolean unlocked, String unlockHint) {
}
