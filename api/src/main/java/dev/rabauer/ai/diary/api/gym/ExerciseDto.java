package dev.rabauer.ai.diary.api.gym;

import dev.rabauer.ai.diary.api.common.Language;

import java.util.List;

/**
 * @param personalized  true if generated from journal history by the LLM; false for built-in templates
 * @param sourceEntryId entry the exercise draws on, if any
 */
public record ExerciseDto(
        long id,
        GymCategory category,
        Language language,
        String title,
        String prompt,
        String sourceExcerpt,
        Long sourceEntryId,
        List<String> steps,
        int estimatedMinutes,
        boolean personalized
) {
}
