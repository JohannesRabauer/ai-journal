package dev.rabauer.ai.diary.gym;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/** LLM output for a personalized exercise (validated by StructuredOutputService; estimatedMinutes is optional). */
public record GeneratedExercise(
        @NotBlank @Size(max = 120) String title,
        @NotBlank @Size(max = 1000) String prompt,
        @NotNull @Size(min = 2, max = 5) List<@NotBlank @Size(max = 300) String> steps,
        @NotBlank String sourceExcerpt,
        @Min(5) @Max(10) Integer estimatedMinutes) {
}
