package dev.rabauer.ai.diary.gym;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/** LLM output for optional feedback on an attempt: one or two follow-up questions, nothing else. */
public record GymFeedback(@NotNull @Size(min = 1, max = 2) List<@NotBlank @Size(max = 400) String> questions) {
}
