package dev.rabauer.ai.diary.memory;

import dev.rabauer.ai.diary.api.memory.MemoryItemType;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/** The LLM's structured extraction answer: {@code {"candidates":[{type,prompt,answer,sourceQuote}]}}, 0..7 items. */
public record ExtractedCandidates(
        @NotNull @Size(max = MemoryService.MAX_CANDIDATES) List<@Valid @NotNull Candidate> candidates) {

    public record Candidate(
            @NotNull MemoryItemType type,
            @NotBlank @Size(max = MemoryService.MAX_PROMPT_CHARS) String prompt,
            @NotBlank @Size(max = MemoryService.MAX_ANSWER_CHARS) String answer,
            @NotBlank @Size(max = MemoryService.MAX_QUOTE_CHARS) String sourceQuote) {
    }
}
