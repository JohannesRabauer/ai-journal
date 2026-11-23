package dev.rabauer.ai.diary.api.insights;

/** Generates (or regenerates) the report for an ISO week. */
public record GenerateWeeklyReportRequest(int isoYear, int isoWeek) {
}
