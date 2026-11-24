package dev.rabauer.ai.diary.coach;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/** LLM output for the opening questions: 2 to 3 single questions. */
public record OpeningQuestions(@NotNull @Size(min = 2, max = 3) List<@NotBlank String> questions) {
}
