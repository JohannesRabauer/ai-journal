package dev.rabauer.ai.diary.rest.stub;

import dev.rabauer.ai.diary.api.insights.GenerateWeeklyReportRequest;
import dev.rabauer.ai.diary.api.insights.WeeklyReportDto;
import dev.rabauer.ai.diary.api.insights.WeeklyReportSummaryDto;

import java.util.List;
import dev.rabauer.ai.diary.rest.WeeklyReportsApi;
import dev.rabauer.ai.diary.web.ApiException;

/**
 * Placeholder returning 501 until the owning worker implements {@link WeeklyReportsApi}. DELETE this class in the same
 * commit that adds the real implementation.
 */
public class WeeklyReportsApiStub implements WeeklyReportsApi {
    @Override
    public WeeklyReportDto generate(GenerateWeeklyReportRequest request) {
        throw ApiException.notImplemented("Weekly reflection");
    }

    @Override
    public List<WeeklyReportSummaryDto> list() {
        throw ApiException.notImplemented("Weekly reflection");
    }

    @Override
    public WeeklyReportDto get(long id) {
        throw ApiException.notImplemented("Weekly reflection");
    }

    @Override
    public WeeklyReportDto update(long id, WeeklyReportDto report) {
        throw ApiException.notImplemented("Weekly reflection");
    }

    @Override
    public void delete(long id) {
        throw ApiException.notImplemented("Weekly reflection");
    }
}
