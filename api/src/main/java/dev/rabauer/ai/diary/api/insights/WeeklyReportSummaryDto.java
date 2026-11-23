package dev.rabauer.ai.diary.api.insights;

import java.time.Instant;

public record WeeklyReportSummaryDto(long id, int isoYear, int isoWeek, Instant generatedAt) {
}
