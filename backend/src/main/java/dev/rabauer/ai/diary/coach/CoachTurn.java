package dev.rabauer.ai.diary.coach;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** LLM output for one coach turn after a user answer. */
public record CoachTurn(@NotNull CoachTurnKind kind, @NotBlank @Size(max = 2000) String text) {
}
