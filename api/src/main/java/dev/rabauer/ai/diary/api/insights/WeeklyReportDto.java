package dev.rabauer.ai.diary.api.insights;

import dev.rabauer.ai.diary.api.common.Language;

import java.time.Instant;
import java.util.List;

/** The six sections of SPEC section 8. No scores. */
public record WeeklyReportDto(
        long id,
        int isoYear,
        int isoWeek,
        Language language,
        List<Theme> recurringThemes,
        List<Quote> inYourOwnWords,
        List<String> openThreads,
        Learned whatYouLearned,
        List<String> noticedPatterns,
        String questionForNextWeek,
        Instant generatedAt,
        Instant editedAt
) {
    public record Theme(String title, String description, List<Long> entryIds) {
    }

    public record Quote(String text, long entryId) {
    }

    public record Learned(int itemsCreated, int reviewsDone, List<String> itemPrompts) {
    }
}
