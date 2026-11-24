package dev.rabauer.ai.diary.insights;

import dev.rabauer.ai.diary.api.insights.WeeklyReportDto;

import java.util.List;

/** Stored JSON of a report's six sections. Mirrors the DTO sections so the schema is the API contract. */
record WeeklyContent(
        List<WeeklyReportDto.Theme> recurringThemes,
        List<WeeklyReportDto.Quote> inYourOwnWords,
        List<String> openThreads,
        WeeklyReportDto.Learned whatYouLearned,
        List<String> noticedPatterns,
        String questionForNextWeek
) {
}
