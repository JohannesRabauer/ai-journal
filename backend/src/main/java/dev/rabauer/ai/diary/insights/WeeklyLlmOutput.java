package dev.rabauer.ai.diary.insights;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * LLM output for a weekly reflection. "What you learned" is deliberately absent: it is filled from memory statistics,
 * never generated.
 */
public record WeeklyLlmOutput(
        @NotNull @Size(min = 2, max = 4) List<@NotNull @Valid Theme> recurringThemes,
        @NotNull @Size(min = 2, max = 3) List<@NotNull @Valid Quote> inYourOwnWords,
        @NotNull @Size(max = 5) List<@NotBlank @Size(max = 400) String> openThreads,
        @NotNull @Size(min = 1, max = 4) List<@NotBlank @Size(max = 500) String> noticedPatterns,
        @NotBlank @Size(max = 400) String questionForNextWeek
) {
    public record Theme(@NotBlank @Size(max = 120) String title,
                        @NotBlank @Size(max = 600) String description,
                        @NotEmpty @Size(max = 20) List<@NotNull Long> entryIds) {
    }

    public record Quote(@NotBlank @Size(max = 400) String text, @NotNull Long entryId) {
    }
}
